package io.github.kr9ly.daybook.kv

import io.github.kr9ly.daybook.journal.JournalFile
import io.github.kr9ly.daybook.journal.open
import io.github.kr9ly.daybook.journal.JournalFormatException
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KvStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun openStore(): KvStore = KvStore.open(tmp.root, "store")

    private fun generationFile(generation: Long = 1): File =
        File(tmp.root, "store.$generation.journal")

    /** 変更イベントを記録し、期待件数まで待てるリスナー。 */
    private class RecordingListener(expectedCount: Int) : DaybookChangeListener {
        val events = mutableListOf<Pair<String, Any?>>()
        private val latch = CountDownLatch(expectedCount)

        override fun onChange(key: String, newValue: Any?) {
            synchronized(events) { events.add(key to newValue) }
            latch.countDown()
        }

        fun awaitAll() {
            assertTrue("listener did not receive expected events", latch.await(5, TimeUnit.SECONDS))
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
        KvStore.open(tmp.root).use { store ->
            store.put("key", 1)
        }
        assertTrue(File(tmp.root, "daybook.1.journal").exists())
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
            assertThrows(IllegalArgumentException::class.java) {
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
        generationFile().appendBytes(byteArrayOf(0, 0, 0, 5, 1, 2))
        openStore().use { store ->
            assertTrue(store.recoveredFromCorruption)
            assertEquals("value", store.get("key"))
        }
    }

    @Test
    fun openNonJournalFile_throwsFormatException() {
        generationFile().writeBytes("not a journal".toByteArray())
        assertThrows(JournalFormatException::class.java) {
            openStore()
        }
    }

    @Test
    fun openJournalWithUndecodableRecord_throwsEncodingException() {
        // CRC は正しいが KV 操作として読めないレコード（ジャーナル層は通る）
        JournalFile.open(generationFile()).use { journal ->
            journal.append(byteArrayOf(99))
        }
        assertThrows(KvEncodingException::class.java) {
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

    @Test
    fun listener_canWriteBackToStoreWithoutDeadlock() {
        openStore().use { store ->
            val done = CountDownLatch(1)
            store.addListener { key, _ ->
                if (key == "trigger") {
                    store.put("echo", "reentrant")
                    done.countDown()
                }
            }
            store.put("trigger", 1)
            assertTrue(done.await(5, TimeUnit.SECONDS))
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
}
