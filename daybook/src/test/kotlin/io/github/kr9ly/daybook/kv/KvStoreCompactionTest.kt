package io.github.kr9ly.daybook.kv

import io.github.kr9ly.daybook.journal.JournalFile
import io.github.kr9ly.daybook.journal.SyncMode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KvStoreCompactionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class SimulatedCrash : RuntimeException("simulated crash")

    private fun generationFile(generation: Long): File =
        File(tmp.root, "store.$generation.journal")

    private fun tempFile(generation: Long): File =
        File(tmp.root, "store.$generation.journal.tmp")

    /** threshold = 1 は「最初の追記で必ず compaction する」設定（baseline は 0 始まり）。 */
    private fun openStore(
        compactionThreshold: Long = 1,
        compactionHook: (CompactionPhase) -> Unit = {},
    ): KvStore = KvStore.open(
        directory = tmp.root,
        name = "store",
        compactionThreshold = compactionThreshold,
        compactionHook = compactionHook,
    )

    // --- 正常系 ---

    @Test
    fun compaction_advancesGenerationAndPreservesState() {
        val phases = mutableListOf<CompactionPhase>()
        openStore(compactionHook = phases::add).use { store ->
            store.put("a", 1)
            store.put("b", 2)
            assertEquals(1, store.get("a"))
            assertEquals(2, store.get("b"))
        }
        assertEquals(
            listOf(CompactionPhase.SNAPSHOT_WRITTEN, CompactionPhase.GENERATION_COMMITTED),
            phases,
        )
        assertFalse(generationFile(1).exists())
        assertTrue(generationFile(2).exists())
        // 大きい閾値で開き直しても状態が保たれている
        KvStore.open(tmp.root, "store").use { store ->
            assertEquals(mapOf<String, Any>("a" to 1, "b" to 2), store.getAll())
        }
    }

    @Test
    fun compactedJournal_containsOnlyLiveEntries() {
        // 大きい閾値でごみ（上書き・削除済みキー）を溜める
        KvStore.open(tmp.root, "store").use { store ->
            store.put("keep", "value")
            store.put("keep", "value") // 上書きのごみ
            store.put("dead", "x")
            store.remove("dead")
        }
        // 小さい閾値で開き直して compaction を踏ませる
        openStore().use { store ->
            store.put("trigger", 1)
        }
        // 新世代にはライブなエントリの Put だけが残る
        val records = JournalFile.open(generationFile(2)).use { it.replayedRecords }
        val ops = records.map { KvOperationCodec.decode(it) }
        assertEquals(
            setOf<KvOperation>(
                KvOperation.Put("keep", "value"),
                KvOperation.Put("trigger", 1),
            ),
            ops.toSet(),
        )
        assertEquals(2, ops.size)
    }

    @Test
    fun baselineGuard_preventsRecompactionUntilJournalDoubles() {
        var compactions = 0
        openStore(compactionHook = { phase ->
            if (phase == CompactionPhase.GENERATION_COMMITTED) compactions++
        }).use { store ->
            store.put("key", "v") // baseline 0 → 即 compaction
            assertEquals(1, compactions)
            store.put("key", "v") // 同サイズの追記では 2 倍に届かない
            assertEquals(1, compactions)
            store.put("key", "x".repeat(200)) // 大きな追記で 2 倍超え
            assertEquals(2, compactions)
        }
    }

    @Test
    fun compaction_emitsNoChangeEvents() {
        openStore().use { store ->
            val events = mutableListOf<Pair<String, Any?>>()
            val latch = CountDownLatch(3)
            store.addListener { key, newValue ->
                synchronized(events) { events.add(key to newValue) }
                latch.countDown()
            }
            store.put("a", 1) // compaction を伴う
            store.put("b", 2)
            store.remove("a")
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            // compaction 由来の余計なイベントが混ざっていない
            assertEquals(
                listOf<Pair<String, Any?>>("a" to 1, "b" to 2, "a" to null),
                events,
            )
        }
    }

    // --- クラッシュ復旧（一時停止フックによる決定的注入） ---

    @Test
    fun crashBeforeRename_keepsOldGenerationWithAllWrites() {
        openStore(compactionHook = { phase ->
            if (phase == CompactionPhase.SNAPSHOT_WRITTEN) throw SimulatedCrash()
        }).use { store ->
            assertThrows(SimulatedCrash::class.java) {
                store.put("key", "value")
            }
        }
        // 追記自体はジャーナルに載っているため値は失われない
        KvStore.open(tmp.root, "store").use { store ->
            assertEquals("value", store.get("key"))
        }
        assertTrue(generationFile(1).exists())
        assertFalse(tempFile(2).exists()) // 残骸はオープン時に掃除される
        assertFalse(generationFile(2).exists())
    }

    @Test
    fun crashAfterRename_adoptsNewGenerationAndCleansOld() {
        openStore(compactionHook = { phase ->
            if (phase == CompactionPhase.GENERATION_COMMITTED) throw SimulatedCrash()
        }).use { store ->
            assertThrows(SimulatedCrash::class.java) {
                store.put("key", "value")
            }
        }
        // rename 済みの新世代が正となり、取り残された旧世代は次のオープンで削除される
        KvStore.open(tmp.root, "store").use { store ->
            assertEquals("value", store.get("key"))
        }
        assertTrue(generationFile(2).exists())
        assertFalse(generationFile(1).exists())
    }

    // --- ディレクトリ状態からの復旧 ---

    @Test
    fun tempOnlyDirectory_adoptsTempAsCurrentGeneration() {
        // 「旧世代の削除は届いたが rename が巻き戻った」電源断相当の状態を作る
        KvStore.open(tmp.root, "store").use { it.put("key", "value") }
        assertTrue(generationFile(1).renameTo(tempFile(2)))
        KvStore.open(tmp.root, "store").use { store ->
            assertEquals("value", store.get("key"))
        }
        assertTrue(generationFile(2).exists())
        assertFalse(tempFile(2).exists())
    }

    @Test
    fun multipleGenerations_highestWinsAndOthersAreDeleted() {
        JournalFile.open(generationFile(1)).use {
            it.append(KvOperationCodec.encode(KvOperation.Put("key", "old")))
        }
        JournalFile.open(generationFile(2)).use {
            it.append(KvOperationCodec.encode(KvOperation.Put("key", "new")))
        }
        KvStore.open(tmp.root, "store").use { store ->
            assertEquals("new", store.get("key"))
        }
        assertFalse(generationFile(1).exists())
    }

    // --- ディレクトリ fsync（SYNC モードの耐久性契約） ---

    @Test
    fun syncMode_syncsDirectoryAtOpenAndAfterRename() {
        val synced = mutableListOf<File>()
        KvStore.open(
            directory = tmp.root,
            name = "store",
            syncMode = SyncMode.SYNC,
            compactionThreshold = 1,
            directorySync = { synced.add(it) },
        ).use { store ->
            // オープン直後: ファイル作成（や採用 rename）の永続化
            assertEquals(1, synced.size)
            store.put("key", "v") // compaction が走り、rename 後にもう一度
            assertEquals(2, synced.size)
        }
        assertEquals(listOf(tmp.root, tmp.root), synced)
    }

    @Test
    fun asyncMode_neverSyncsDirectory() {
        var syncs = 0
        KvStore.open(
            directory = tmp.root,
            name = "store",
            compactionThreshold = 1,
            directorySync = { syncs++ },
        ).use { store ->
            store.put("key", "v") // compaction は走るがディレクトリ fsync はしない
        }
        assertEquals(0, syncs)
    }

    @Test
    fun syncModeWithRealDirectorySync_worksOnJvm() {
        // デフォルトの DirectorySync（JVM では nio 実装）で実際に fsync まで通す
        KvStore.open(tmp.root, "store", syncMode = SyncMode.SYNC, compactionThreshold = 1).use { store ->
            store.put("key", "value")
        }
        KvStore.open(tmp.root, "store", syncMode = SyncMode.SYNC).use { store ->
            assertEquals("value", store.get("key"))
        }
    }

    @Test
    fun staleTempAlongsideGeneration_isDeletedAtOpen() {
        KvStore.open(tmp.root, "store").use { it.put("key", 1) }
        tempFile(1).writeBytes(byteArrayOf(1, 2, 3))
        KvStore.open(tmp.root, "store").use { store ->
            assertEquals(1, store.get("key"))
        }
        assertFalse(tempFile(1).exists())
    }
}
