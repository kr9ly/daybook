package io.github.kr9ly.daybook.kv

import io.github.kr9ly.daybook.concurrent.Lock
import io.github.kr9ly.daybook.concurrent.waitUntil
import io.github.kr9ly.daybook.concurrent.withLock
import io.github.kr9ly.daybook.io.FilePath
import io.github.kr9ly.daybook.io.createTempDirectory
import io.github.kr9ly.daybook.io.fileExists
import io.github.kr9ly.daybook.io.renameFile
import io.github.kr9ly.daybook.io.writeFileBytes
import io.github.kr9ly.daybook.journal.JournalFile
import io.github.kr9ly.daybook.journal.SyncMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KvStoreCompactionTest {

    private val tmp = createTempDirectory()

    private class SimulatedCrash : RuntimeException("simulated crash")

    private fun generationFile(generation: Long): FilePath =
        tmp.resolve("store.$generation.journal")

    private fun tempFile(generation: Long): FilePath =
        tmp.resolve("store.$generation.journal.tmp")

    /** threshold = 1 は「最初の追記で必ず compaction する」設定（baseline は 0 始まり）。 */
    private fun openStore(
        compactionThreshold: Long = 1,
        compactionHook: (CompactionPhase) -> Unit = {},
    ): KvStore = KvStore.open(
        directory = tmp,
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
        assertFalse(fileExists(generationFile(1)))
        assertTrue(fileExists(generationFile(2)))
        // 大きい閾値で開き直しても状態が保たれている
        KvStore.open(tmp, "store").use { store ->
            assertEquals(mapOf<String, Any>("a" to 1, "b" to 2), store.getAll())
        }
    }

    @Test
    fun compactedJournal_containsOnlyLiveEntries() {
        // 大きい閾値でごみ（上書き・削除済みキー）を溜める
        KvStore.open(tmp, "store").use { store ->
            store.put("keep", "value")
            store.put("keep", "value") // 上書きのごみ
            store.put("dead", "x")
            store.remove("dead")
        }
        // 小さい閾値で開き直して compaction を踏ませる
        openStore().use { store ->
            store.put("trigger", 1)
        }
        // 新世代にはライブなエントリの Put + 末尾の境界マーカーだけが残る
        val records = JournalFile.open(generationFile(2)).use { it.replayedRecords }
        val ops = records.map { KvOperationCodec.decode(it) }
        assertEquals(KvOperation.SnapshotBoundary, ops.last())
        assertEquals(
            setOf<KvOperation>(
                KvOperation.Put("keep", "value"),
                KvOperation.Put("trigger", 1),
            ),
            ops.dropLast(1).toSet(),
        )
        assertEquals(3, ops.size)
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
            val lock = Lock()
            val events = mutableListOf<Pair<String, Any?>>()
            store.addListener { key, newValue ->
                lock.withLock { events.add(key to newValue) }
            }
            store.put("a", 1) // compaction を伴う
            store.put("b", 2)
            store.remove("a")
            assertTrue(waitUntil { lock.withLock { events.size } >= 3 })
            // compaction 由来の余計なイベントが混ざっていない
            assertEquals(
                listOf<Pair<String, Any?>>("a" to 1, "b" to 2, "a" to null),
                lock.withLock { events.toList() },
            )
        }
    }

    // --- クラッシュ復旧（一時停止フックによる決定的注入） ---

    @Test
    fun crashBeforeRename_keepsOldGenerationWithAllWrites() {
        openStore(compactionHook = { phase ->
            if (phase == CompactionPhase.SNAPSHOT_WRITTEN) throw SimulatedCrash()
        }).use { store ->
            assertFailsWith<SimulatedCrash> {
                store.put("key", "value")
            }
        }
        // 追記自体はジャーナルに載っているため値は失われない
        KvStore.open(tmp, "store").use { store ->
            assertEquals("value", store.get("key"))
        }
        assertTrue(fileExists(generationFile(1)))
        assertFalse(fileExists(tempFile(2))) // 残骸はオープン時に掃除される
        assertFalse(fileExists(generationFile(2)))
    }

    @Test
    fun crashAfterRename_adoptsNewGenerationAndCleansOld() {
        openStore(compactionHook = { phase ->
            if (phase == CompactionPhase.GENERATION_COMMITTED) throw SimulatedCrash()
        }).use { store ->
            assertFailsWith<SimulatedCrash> {
                store.put("key", "value")
            }
        }
        // rename 済みの新世代が正となり、取り残された旧世代は次のオープンで削除される
        KvStore.open(tmp, "store").use { store ->
            assertEquals("value", store.get("key"))
        }
        assertTrue(fileExists(generationFile(2)))
        assertFalse(fileExists(generationFile(1)))
    }

    // --- ディレクトリ状態からの復旧 ---

    @Test
    fun tempOnlyDirectory_adoptsTempAsCurrentGeneration() {
        // 「旧世代の削除は届いたが rename が巻き戻った」電源断相当の状態を作る
        KvStore.open(tmp, "store").use { it.put("key", "value") }
        assertTrue(renameFile(generationFile(1), tempFile(2)))
        KvStore.open(tmp, "store").use { store ->
            assertEquals("value", store.get("key"))
        }
        assertTrue(fileExists(generationFile(2)))
        assertFalse(fileExists(tempFile(2)))
    }

    @Test
    fun multipleGenerations_highestWinsAndOthersAreDeleted() {
        JournalFile.open(generationFile(1)).use {
            it.append(KvOperationCodec.encode(KvOperation.Put("key", "old")))
        }
        JournalFile.open(generationFile(2)).use {
            it.append(KvOperationCodec.encode(KvOperation.Put("key", "new")))
        }
        KvStore.open(tmp, "store").use { store ->
            assertEquals("new", store.get("key"))
        }
        assertFalse(fileExists(generationFile(1)))
    }

    // --- ディレクトリ fsync（SYNC モードの耐久性契約） ---

    @Test
    fun syncMode_syncsDirectoryAtOpenAndAfterRename() {
        val synced = mutableListOf<FilePath>()
        KvStore.open(
            directory = tmp,
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
        assertEquals(listOf(tmp, tmp), synced)
    }

    @Test
    fun asyncMode_neverSyncsDirectory() {
        var syncs = 0
        KvStore.open(
            directory = tmp,
            name = "store",
            compactionThreshold = 1,
            directorySync = { syncs++ },
        ).use { store ->
            store.put("key", "v") // compaction は走るがディレクトリ fsync はしない
        }
        assertEquals(0, syncs)
    }

    @Test
    fun syncModeWithRealDirectorySync_persistsAcrossReopen() {
        // デフォルトの DirectorySync（プラットフォーム実装）で実際に fsync まで通す
        KvStore.open(tmp, "store", syncMode = SyncMode.SYNC, compactionThreshold = 1).use { store ->
            store.put("key", "value")
        }
        KvStore.open(tmp, "store", syncMode = SyncMode.SYNC).use { store ->
            assertEquals("value", store.get("key"))
        }
    }

    @Test
    fun staleTempAlongsideGeneration_isDeletedAtOpen() {
        KvStore.open(tmp, "store").use { it.put("key", 1) }
        writeFileBytes(tempFile(1), byteArrayOf(1, 2, 3))
        KvStore.open(tmp, "store").use { store ->
            assertEquals(1, store.get("key"))
        }
        assertFalse(fileExists(tempFile(1)))
    }
}
