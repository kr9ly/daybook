package io.github.kr9ly.daybook.kv

import io.github.kr9ly.daybook.journal.InterProcessLock
import io.github.kr9ly.daybook.journal.JournalFile
import io.github.kr9ly.daybook.journal.JournalFormatException
import io.github.kr9ly.daybook.journal.JournalWatcherFactory
import java.io.Closeable
import java.io.File
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * マルチプロセスモードの結合テスト。
 *
 * 実 FileLock は同一 JVM 内で重複取得できず、FileObserver は JVM で動かないため、
 * どちらも偽物を注入して「1 JVM 内の 2 store = 2 プロセス」を模す。
 * 実物どうしの結合（別プロセス間の FileLock・inotify）は Instrumentation テストの守備範囲。
 */
class KvStoreMultiProcessTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 同一 JVM 内で複数 store が共有する、プロセス間ロックの偽物。 */
    private class FakeInterProcessLock(private val mutex: ReentrantLock) : InterProcessLock {
        var closeCount = 0

        override fun <T> withLock(body: () -> T): T {
            mutex.lock()
            try {
                return body()
            } finally {
                mutex.unlock()
            }
        }

        override fun close() {
            closeCount++
        }
    }

    /** 手動トリガの watcher。inotify の代わりにテストが [trigger] で検知を起こす。 */
    private class ManualWatcherFactory : JournalWatcherFactory {
        private val callbacks = CopyOnWriteArrayList<() -> Unit>()

        override fun watch(directory: File, onChange: () -> Unit): Closeable {
            callbacks.add(onChange)
            return Closeable { callbacks.remove(onChange) }
        }

        /** 登録済みコールバックを同期的に呼ぶ（検知イベントの決定的な注入）。 */
        fun trigger() {
            callbacks.forEach { it() }
        }
    }

    /** 配送スレッドから届く変更イベントの記録。 */
    private class Events {
        private val list = Collections.synchronizedList(mutableListOf<Pair<String, Any?>>())

        val listener = KvChangeListener { key, newValue -> list.add(key to newValue) }

        fun await(count: Int, timeoutMs: Long = 5000): List<Pair<String, Any?>> {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (list.size < count && System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
            }
            return list.toList()
        }
    }

    private val sharedMutex = ReentrantLock()

    private fun openStore(
        watcherFactory: ManualWatcherFactory,
        compactionThreshold: Long = KvStore.DEFAULT_COMPACTION_THRESHOLD,
    ): KvStore = KvStore.open(
        directory = tmp.root,
        name = "store",
        multiProcess = true,
        compactionThreshold = compactionThreshold,
        lockFactory = { FakeInterProcessLock(sharedMutex) },
        watcherFactory = watcherFactory,
    )

    // --- 差分リプレイによる伝播 ---

    @Test
    fun writeInOneStore_becomesVisibleInOtherAfterDetection() {
        val watcherB = ManualWatcherFactory()
        openStore(ManualWatcherFactory()).use { a ->
            openStore(watcherB).use { b ->
                a.put("key", "value")
                assertNull(b.get("key")) // 検知前は見えない（既知のウィンドウ）
                watcherB.trigger()
                assertEquals("value", b.get("key"))
            }
        }
    }

    @Test
    fun crossProcessChange_notifiesListenerWithValue() {
        val watcherB = ManualWatcherFactory()
        openStore(ManualWatcherFactory()).use { a ->
            openStore(watcherB).use { b ->
                val events = Events()
                b.addListener(events.listener)
                a.put("key", 42)
                watcherB.trigger()
                assertEquals(listOf<Pair<String, Any?>>("key" to 42), events.await(1))
            }
        }
    }

    @Test
    fun crossProcessBatch_arrivesAtomicallyInOperationOrder() {
        val watcherB = ManualWatcherFactory()
        openStore(ManualWatcherFactory()).use { a ->
            openStore(watcherB).use { b ->
                val events = Events()
                b.addListener(events.listener)
                a.writeBatch(
                    listOf(
                        KvOperation.Put("x", 1),
                        KvOperation.Remove("x"),
                        KvOperation.Put("y", "z"),
                    ),
                )
                assertNull(b.get("y")) // 検知前は見えない（既知のウィンドウ）
                watcherB.trigger()
                assertEquals(
                    listOf<Pair<String, Any?>>("x" to 1, "x" to null, "y" to "z"),
                    events.await(3),
                )
                assertEquals(mapOf<String, Any>("y" to "z"), b.getAll())
            }
        }
    }

    @Test
    fun removeAndClear_propagateAcrossStores() {
        val watcherB = ManualWatcherFactory()
        openStore(ManualWatcherFactory()).use { a ->
            openStore(watcherB).use { b ->
                a.put("k1", 1)
                a.put("k2", 2)
                watcherB.trigger()
                a.remove("k1")
                watcherB.trigger()
                assertNull(b.get("k1"))
                assertEquals(2, b.get("k2"))
                a.clear()
                watcherB.trigger()
                assertTrue(b.getAll().isEmpty())
            }
        }
    }

    @Test
    fun sameValuePut_inSameGeneration_isNotifiedOperationBased() {
        val watcherB = ManualWatcherFactory()
        openStore(ManualWatcherFactory()).use { a ->
            openStore(watcherB).use { b ->
                a.put("key", 1)
                watcherB.trigger()
                val events = Events()
                b.addListener(events.listener)
                a.put("key", 1) // 同値 Put も操作ベースで通知される（原則の維持）
                watcherB.trigger()
                assertEquals(listOf<Pair<String, Any?>>("key" to 1), events.await(1))
            }
        }
    }

    @Test
    fun ownWriteEcho_doesNotReapplyOrDuplicateNotifications() {
        // inotify は自分の書き込みにも発火する。オフセットが進んでいるため二重適用されない
        val watcherA = ManualWatcherFactory()
        openStore(watcherA).use { a ->
            val events = Events()
            a.addListener(events.listener)
            a.put("key", 1)
            watcherA.trigger()
            a.put("other", 2) // 後続イベントで「key の通知が 1 回だった」ことを確定させる
            val received = events.await(2)
            assertEquals(listOf<Pair<String, Any?>>("key" to 1, "other" to 2), received)
        }
    }

    // --- 書き込みプロトコル（追記前のキャッチアップ） ---

    @Test
    fun interleavedWrites_withoutDetection_doNotClobberEachOther() {
        val watcherA = ManualWatcherFactory()
        openStore(watcherA).use { a ->
            openStore(ManualWatcherFactory()).use { b ->
                a.put("a", 1)
                b.put("b", 2) // 検知イベントなしでも、書き込み前のキャッチアップで a を取り込む
                assertEquals(1, b.get("a"))
                assertEquals(2, b.get("b"))
                watcherA.trigger()
                assertEquals(mapOf<String, Any>("a" to 1, "b" to 2), a.getAll())
            }
        }
    }

    @Test
    fun concurrentWriters_allWritesSurvive() {
        val watcherA = ManualWatcherFactory()
        val watcherB = ManualWatcherFactory()
        openStore(watcherA).use { a ->
            openStore(watcherB).use { b ->
                val writers = listOf(
                    Thread { repeat(100) { a.put("a$it", it) } },
                    Thread { repeat(100) { b.put("b$it", it) } },
                )
                writers.forEach { it.start() }
                writers.forEach { it.join() }
                watcherA.trigger()
                watcherB.trigger()
                assertEquals(200, a.getAll().size)
                assertEquals(a.getAll(), b.getAll())
            }
        }
    }

    // --- readFresh（強一貫読み出し） ---

    @Test
    fun readFresh_catchesUpBeforeReading() {
        openStore(ManualWatcherFactory()).use { a ->
            openStore(ManualWatcherFactory()).use { b ->
                a.put("key", "value")
                assertNull(b.get("key")) // 検知イベントはまだ届いていない
                assertEquals("value", b.readFresh("key"))
                assertEquals("value", b.get("key")) // キャッシュにも取り込まれている
            }
        }
    }

    @Test
    fun readFresh_onSingleProcessStore_behavesLikeGet() {
        KvStore.open(tmp.root, "store").use { store ->
            store.put("key", "value")
            assertEquals("value", store.readFresh("key"))
            assertNull(store.readFresh("missing"))
        }
    }

    // --- 世代切替（他プロセスの compaction）の検知と通知 ---

    @Test
    fun generationSwitch_preservesStateAndNotifiesOnlyDifferences() {
        val watcherB = ManualWatcherFactory()
        // A は最初の書き込みで必ず compaction する設定
        openStore(ManualWatcherFactory(), compactionThreshold = 1).use { a ->
            openStore(watcherB).use { b ->
                a.put("known", "same")
                watcherB.trigger() // B は known を取り込み済み
                val events = Events()
                b.addListener(events.listener)
                a.put("x", 1) // B は見ていない追記 → compaction で世代が進む
                watcherB.trigger()
                assertEquals(mapOf<String, Any>("known" to "same", "x" to 1), b.getAll())
                // known は値が変わっていないため通知されない。差分の x だけ
                assertEquals(listOf<Pair<String, Any?>>("x" to 1), events.await(1))
            }
        }
    }

    @Test
    fun generationSwitch_notifiesKeysRemovedInSnapshot() {
        val watcherB = ManualWatcherFactory()
        openStore(ManualWatcherFactory(), compactionThreshold = 1).use { a ->
            openStore(watcherB).use { b ->
                a.put("gone", 1)
                watcherB.trigger()
                val events = Events()
                b.addListener(events.listener)
                a.remove("gone") // remove を含む状態で compaction → 新世代に gone はない
                watcherB.trigger()
                assertNull(b.get("gone"))
                assertEquals(listOf<Pair<String, Any?>>("gone" to null), events.await(1))
            }
        }
    }

    @Test
    fun generationSwitch_opsAfterMarker_areNotifiedOperationBased() {
        val watcherB = ManualWatcherFactory()
        openStore(ManualWatcherFactory(), compactionThreshold = 1).use { a ->
            openStore(watcherB).use { b ->
                val events = Events()
                b.addListener(events.listener)
                a.put("x", 1) // compaction（スナップショット + マーカー）
                a.put("y", 2) // baseline ガードにより再 compaction されず、マーカー後の追記になる
                watcherB.trigger()
                assertEquals(mapOf<String, Any>("x" to 1, "y" to 2), b.getAll())
                assertEquals(listOf<Pair<String, Any?>>("x" to 1, "y" to 2), events.await(2))
            }
        }
    }

    @Test
    fun generationFileWithoutMarker_isTreatedEntirelyAsSnapshot() {
        val watcherB = ManualWatcherFactory()
        openStore(ManualWatcherFactory()).use { a ->
            openStore(watcherB).use { b ->
                a.put("same", "value")
                watcherB.trigger()
                val events = Events()
                b.addListener(events.listener)
                // マーカーを経ない世代ファイル（防御パス）を直接作る
                JournalFile.open(File(tmp.root, "store.2.journal")).use { journal ->
                    journal.append(KvOperationCodec.encode(KvOperation.Put("same", "value")))
                    journal.append(KvOperationCodec.encode(KvOperation.Put("new", 9)))
                }
                watcherB.trigger()
                assertEquals(mapOf<String, Any>("same" to "value", "new" to 9), b.getAll())
                // 全体をスナップショット = 状態差分扱い: 同値の same は通知されない
                assertEquals(listOf<Pair<String, Any?>>("new" to 9), events.await(1))
            }
        }
    }

    @Test
    fun snapshotSection_appliesRemoveAndClearGenerically() {
        val watcherB = ManualWatcherFactory()
        openStore(ManualWatcherFactory()).use { a ->
            openStore(watcherB).use { b ->
                a.put("old", 1)
                watcherB.trigger()
                // スナップショット部に Put 以外が混ざった世代ファイル（形式上は合法）
                JournalFile.open(File(tmp.root, "store.2.journal")).use { journal ->
                    listOf(
                        KvOperation.Put("dead", 1),
                        KvOperation.Clear,
                        KvOperation.Put("kept", 2),
                        KvOperation.Put("removed", 3),
                        KvOperation.Remove("removed"),
                        KvOperation.SnapshotBoundary,
                    ).forEach { journal.append(KvOperationCodec.encode(it)) }
                }
                watcherB.trigger()
                assertEquals(mapOf<String, Any>("kept" to 2), b.getAll())
            }
        }
    }

    // --- ライフサイクル ---

    @Test
    fun lateWatcherEvent_afterClose_isIgnored() {
        // close が watcher を止めた「後」に届いてしまうイベントを模す:
        // close しても登録を外さない watcher を注入する
        var captured: (() -> Unit)? = null
        val store = KvStore.open(
            directory = tmp.root,
            name = "store",
            multiProcess = true,
            lockFactory = { FakeInterProcessLock(sharedMutex) },
            watcherFactory = { _, onChange ->
                captured = onChange
                Closeable {}
            },
        )
        store.put("key", 1)
        store.close()
        captured!!() // 例外なく無視されること
    }

    @Test
    fun close_isIdempotent() {
        val store = openStore(ManualWatcherFactory())
        store.put("key", 1)
        store.close()
        store.close()
    }

    @Test
    fun openFailure_releasesInterProcessLock() {
        File(tmp.root, "store.1.journal").writeBytes("not a journal".toByteArray())
        val lock = FakeInterProcessLock(sharedMutex)
        assertThrows(JournalFormatException::class.java) {
            KvStore.open(
                directory = tmp.root,
                name = "store",
                multiProcess = true,
                lockFactory = { lock },
                watcherFactory = ManualWatcherFactory(),
            )
        }
        assertEquals(1, lock.closeCount)
    }

    @Test
    fun close_releasesInterProcessLock() {
        val lock = FakeInterProcessLock(sharedMutex)
        KvStore.open(
            directory = tmp.root,
            name = "store",
            multiProcess = true,
            lockFactory = { lock },
            watcherFactory = ManualWatcherFactory(),
        ).close()
        assertEquals(1, lock.closeCount)
    }
}
