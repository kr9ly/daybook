package io.github.kr9ly.daybook.kv

import io.github.kr9ly.daybook.concurrent.Lock
import io.github.kr9ly.daybook.concurrent.startTestThread
import io.github.kr9ly.daybook.concurrent.waitUntil
import io.github.kr9ly.daybook.concurrent.withLock
import io.github.kr9ly.daybook.io.FilePath
import io.github.kr9ly.daybook.io.appendFileBytes
import io.github.kr9ly.daybook.io.createTempDirectory
import io.github.kr9ly.daybook.io.fileExists
import io.github.kr9ly.daybook.io.writeFileBytes
import io.github.kr9ly.daybook.journal.JournalFile
import io.github.kr9ly.daybook.journal.JournalFormatException
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KvStoreTest {

    private val tmp = createTempDirectory()

    private fun openStore(): KvStore = KvStore.open(tmp, "store")

    private fun generationFile(generation: Long = 1): FilePath =
        tmp.resolve("store.$generation.journal")

    /** 変更イベントを記録し、期待件数まで待てるリスナー。 */
    private class RecordingListener(private val expectedCount: Int) : DaybookChangeListener {
        private val lock = Lock()
        private val recorded = mutableListOf<Pair<String, Any?>>()

        val events: List<Pair<String, Any?>>
            get() = lock.withLock { recorded.toList() }

        override fun onChange(key: String, newValue: Any?) {
            lock.withLock { recorded.add(key to newValue) }
        }

        fun awaitAll() {
            assertTrue(
                waitUntil { lock.withLock { recorded.size } >= expectedCount },
                "listener did not receive expected events",
            )
        }
    }

    // --- 読み書きの基本 ---

    @Test
    fun putAndGet_allSupportedTypes() {
        openStore().use { store ->
            store.put("string", "value")
            store.put("int", 42)
            store.put("long", 42L)
            store.put("float", 1.5f)
            store.put("boolean", true)
            store.put("set", setOf("a", "b"))

            assertEquals("value", store.get("string"))
            assertEquals(42, store.get("int"))
            assertEquals(42L, store.get("long"))
            assertEquals(1.5f, store.get("float"))
            assertEquals(true, store.get("boolean"))
            assertEquals(setOf("a", "b"), store.get("set"))
        }
    }

    @Test
    fun openWithDefaultName_usesDaybookFiles() {
        KvStore.open(tmp).use { store ->
            store.put("key", 1)
        }
        assertTrue(fileExists(tmp.resolve("daybook.1.journal")))
    }

    @Test
    fun getMissingKey_returnsNull() {
        openStore().use { store ->
            assertNull(store.get("missing"))
        }
    }

    @Test
    fun putSameKey_overwrites() {
        openStore().use { store ->
            store.put("key", "old")
            store.put("key", "new")
            assertEquals("new", store.get("key"))
        }
    }

    @Test
    fun putUnsupportedType_isRejectedWithoutJournaling() {
        openStore().use { store ->
            assertFailsWith<IllegalArgumentException> {
                store.put("key", 'x') // Char は非対応
            }
        }
        // 型検査は追記前に行われるため、ジャーナルに不正レコードは残らない
        openStore().use { store ->
            assertTrue(store.getAll().isEmpty())
        }
    }

    @Test
    fun remove_deletesKey() {
        openStore().use { store ->
            store.put("key", "value")
            store.remove("key")
            assertNull(store.get("key"))
            assertFalse(store.contains("key"))
        }
    }

    @Test
    fun clear_deletesAllKeys() {
        openStore().use { store ->
            store.put("a", 1)
            store.put("b", 2)
            store.clear()
            assertTrue(store.getAll().isEmpty())
        }
    }

    @Test
    fun contains_reflectsCurrentState() {
        openStore().use { store ->
            assertFalse(store.contains("key"))
            store.put("key", "value")
            assertTrue(store.contains("key"))
        }
    }

    @Test
    fun getAll_isSnapshotUnaffectedByLaterWrites() {
        openStore().use { store ->
            store.put("key", "value")
            val snapshot = store.getAll()
            store.put("key2", "value2")
            store.remove("key")
            assertEquals(mapOf<String, Any>("key" to "value"), snapshot)
        }
    }

    @Test
    fun putSet_storesDefensiveCopy() {
        openStore().use { store ->
            val mutable = mutableSetOf("a")
            store.put("set", mutable)
            mutable.add("b")
            assertEquals(setOf("a"), store.get("set"))
        }
    }

    // --- リプレイ（永続化との統合） ---

    @Test
    fun reopen_restoresStateFromJournal() {
        openStore().use { store ->
            store.put("string", "value")
            store.put("int", 42)
            store.put("removed", "gone")
            store.remove("removed")
        }
        openStore().use { store ->
            assertEquals(
                mapOf<String, Any>("string" to "value", "int" to 42),
                store.getAll(),
            )
            assertFalse(store.recoveredFromCorruption)
        }
    }

    @Test
    fun reopen_replaysClear() {
        openStore().use { store ->
            store.put("before", 1)
            store.clear()
            store.put("after", 2)
        }
        openStore().use { store ->
            assertEquals(mapOf<String, Any>("after" to 2), store.getAll())
        }
    }

    @Test
    fun openWithCorruptedTail_recoversToLastGoodState() {
        openStore().use { store ->
            store.put("key", "value")
        }
        // 追記途中のクラッシュを模して中途半端なバイトを足す
        appendFileBytes(generationFile(), byteArrayOf(0, 0, 0, 5, 1, 2))
        openStore().use { store ->
            assertTrue(store.recoveredFromCorruption)
            assertEquals("value", store.get("key"))
        }
    }

    @Test
    fun openNonJournalFile_throwsFormatException() {
        writeFileBytes(generationFile(), "not a journal".encodeToByteArray())
        assertFailsWith<JournalFormatException> {
            openStore()
        }
    }

    @Test
    fun openJournalWithUndecodableRecord_throwsEncodingException() {
        // CRC は正しいが KV 操作として読めないレコード（ジャーナル層は通る）
        JournalFile.open(generationFile()).use { journal ->
            journal.append(byteArrayOf(99))
        }
        assertFailsWith<KvEncodingException> {
            openStore()
        }
    }

    // --- 変更通知 ---

    @Test
    fun listener_receivesPutWithNewValue() {
        openStore().use { store ->
            val listener = RecordingListener(1)
            store.addListener(listener)
            store.put("key", "value")
            listener.awaitAll()
            assertEquals(listOf<Pair<String, Any?>>("key" to "value"), listener.events)
        }
    }

    @Test
    fun listener_receivesRemoveWithNull() {
        openStore().use { store ->
            store.put("key", "value")
            val listener = RecordingListener(1)
            store.addListener(listener)
            store.remove("key")
            listener.awaitAll()
            assertEquals(listOf<Pair<String, Any?>>("key" to null), listener.events)
        }
    }

    @Test
    fun listener_receivesRemoveOfAbsentKey() {
        // 通知は状態差分でなく操作ベース: 存在しないキーの Remove も通知される
        openStore().use { store ->
            val listener = RecordingListener(1)
            store.addListener(listener)
            store.remove("absent")
            listener.awaitAll()
            assertEquals(listOf<Pair<String, Any?>>("absent" to null), listener.events)
        }
    }

    @Test
    fun listener_receivesClearAsPerKeyNulls() {
        openStore().use { store ->
            store.put("a", 1)
            store.put("b", 2)
            val listener = RecordingListener(2)
            store.addListener(listener)
            store.clear()
            listener.awaitAll()
            assertEquals(
                setOf<Pair<String, Any?>>("a" to null, "b" to null),
                listener.events.toSet(),
            )
        }
    }

    @Test
    fun listener_eventsArriveInWriteOrder() {
        openStore().use { store ->
            val listener = RecordingListener(3)
            store.addListener(listener)
            store.put("key", 1)
            store.put("key", 2)
            store.remove("key")
            listener.awaitAll()
            assertEquals(
                listOf<Pair<String, Any?>>("key" to 1, "key" to 2, "key" to null),
                listener.events,
            )
        }
    }

    @Test
    fun multipleListeners_allReceiveEvents() {
        openStore().use { store ->
            val first = RecordingListener(1)
            val second = RecordingListener(1)
            store.addListener(first)
            store.addListener(second)
            store.put("key", "value")
            first.awaitAll()
            second.awaitAll()
            assertEquals(first.events, second.events)
        }
    }

    @Test
    fun removedListener_stopsReceivingEvents() {
        openStore().use { store ->
            val removed = RecordingListener(1)
            val kept = RecordingListener(2)
            store.addListener(removed)
            store.addListener(kept)
            store.put("key", 1)
            store.removeListener(removed)
            store.put("key", 2)
            kept.awaitAll()
            assertEquals(listOf<Pair<String, Any?>>("key" to 1), removed.events)
        }
    }

    @Test
    fun listenerAddedAfterWrite_doesNotReceivePastEvents() {
        openStore().use { store ->
            store.put("before", 1)
            val listener = RecordingListener(1)
            store.addListener(listener)
            store.put("after", 2)
            listener.awaitAll()
            assertEquals(listOf<Pair<String, Any?>>("after" to 2), listener.events)
        }
    }

    private class Flag {
        @Volatile
        var raised = false
    }

    @Test
    fun listener_canWriteBackToStoreWithoutDeadlock() {
        openStore().use { store ->
            val done = Flag()
            store.addListener { key, _ ->
                if (key == "trigger") {
                    store.put("echo", "reentrant")
                    done.raised = true
                }
            }
            store.put("trigger", 1)
            assertTrue(waitUntil { done.raised })
            assertEquals("reentrant", store.get("echo"))
        }
    }

    @Test
    fun close_deliversAlreadyEnqueuedEvents() {
        val listener = RecordingListener(1)
        openStore().use { store ->
            store.addListener(listener)
            store.put("key", "value")
        }
        listener.awaitAll()
        assertEquals(listOf<Pair<String, Any?>>("key" to "value"), listener.events)
    }

    @Test
    fun writeAfterClose_throwsIllegalStateException() {
        val store = openStore()
        store.put("key", "value")
        store.close()
        assertFailsWith<IllegalStateException> { store.put("key", "other") }
        assertFailsWith<IllegalStateException> { store.remove("key") }
        assertFailsWith<IllegalStateException> { store.clear() }
        assertFailsWith<IllegalStateException> {
            store.writeBatch(listOf(KvOperation.Put("a", 1), KvOperation.Remove("key")))
        }
        // キャッシュ読みは close 後も許容（最後に見えていた状態を返す）
        assertEquals("value", store.get("key"))
    }

    @Test
    fun closeDuringConcurrentWrites_failsWritersWithContractException() {
        val store = openStore()
        val lock = Lock()
        var stopped = 0
        var unexpected: Throwable? = null
        val writers = 4
        repeat(writers) { n ->
            startTestThread {
                var i = 0
                try {
                    while (true) {
                        store.put("key$n", i++)
                    }
                } catch (_: IllegalStateException) {
                    // close 後の書き込みは契約どおりこの例外で終わる
                } catch (e: Throwable) {
                    lock.withLock { unexpected = e }
                } finally {
                    lock.withLock { stopped++ }
                }
            }
        }
        // 書き込みが実際に走り始めてから閉じる
        assertTrue(waitUntil { store.get("key0") != null })
        store.close()
        store.close() // 冪等
        assertTrue(waitUntil { lock.withLock { stopped } == writers })
        assertNull(lock.withLock { unexpected })
    }
}
