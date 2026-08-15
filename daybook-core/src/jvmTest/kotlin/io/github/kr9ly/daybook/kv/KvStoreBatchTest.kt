package io.github.kr9ly.daybook.kv

import io.github.kr9ly.daybook.journal.FileSink
import io.github.kr9ly.daybook.journal.JournalFile
import io.github.kr9ly.daybook.journal.JournalSink
import io.github.kr9ly.daybook.journal.open
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [KvStore.writeBatch] の結合テスト。
 * バッチのアトミック性（1 ジャーナルレコード = 全適用か全消失か）と、
 * 並び順どおりの適用・通知を検証する。
 */
class KvStoreBatchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun openStore(): KvStore = KvStore.open(tmp.root, "store")

    private fun generationFile(root: File = tmp.root, generation: Long = 1): File =
        File(root, "store.$generation.journal")

    /** クローズ済みジャーナルのレコード列を KV 操作として読み戻す。 */
    private fun journalOperations(root: File = tmp.root): List<KvOperation> =
        JournalFile.open(generationFile(root)).use { journal ->
            journal.replayedRecords.map(KvOperationCodec::decode)
        }

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

    // --- 適用と通知 ---

    @Test
    fun writeBatch_appliesAllOperationsInOrder() {
        openStore().use { store ->
            store.put("base", "x")
            val listener = RecordingListener(3)
            store.addListener(listener)
            store.writeBatch(
                listOf(
                    KvOperation.Put("a", 1),
                    KvOperation.Remove("base"),
                    KvOperation.Put("b", 2L),
                ),
            )
            listener.awaitAll()
            assertEquals(
                listOf<Pair<String, Any?>>("a" to 1, "base" to null, "b" to 2L),
                listener.events,
            )
            assertEquals(mapOf<String, Any>("a" to 1, "b" to 2L), store.getAll())
        }
    }

    @Test
    fun writeBatch_clearInsideBatch_clearsBeforeLaterPuts() {
        // SharedPreferences の Editor 互換の要: clear + put を 1 バッチにしても put が生き残る
        openStore().use { store ->
            store.put("old", 1)
            store.writeBatch(
                listOf(
                    KvOperation.Clear,
                    KvOperation.Put("new", 2),
                ),
            )
            assertEquals(mapOf<String, Any>("new" to 2), store.getAll())
        }
    }

    @Test
    fun writeBatch_storesDefensiveCopyOfSets() {
        openStore().use { store ->
            val mutable = mutableSetOf("a")
            store.writeBatch(
                listOf(
                    KvOperation.Put("set", mutable),
                    KvOperation.Put("other", 1),
                ),
            )
            mutable.add("b")
            assertEquals(setOf("a"), store.get("set"))
        }
    }

    @Test
    fun writeBatch_rejectsUnsupportedValueWithoutJournaling() {
        openStore().use { store ->
            assertThrows(IllegalArgumentException::class.java) {
                store.writeBatch(
                    listOf(
                        KvOperation.Put("ok", 1),
                        KvOperation.Put("bad", 'x'), // Char は非対応
                    ),
                )
            }
            // 型検査は追記前に行われるため、バッチの一部だけが残ることもない
            assertTrue(store.getAll().isEmpty())
        }
        openStore().use { store ->
            assertTrue(store.getAll().isEmpty())
        }
    }

    // --- ジャーナル表現 ---

    @Test
    fun writeBatch_persistsAsSingleRecord() {
        openStore().use { store ->
            store.writeBatch(
                listOf(
                    KvOperation.Put("a", 1),
                    KvOperation.Put("b", 2),
                ),
            )
        }
        val ops = journalOperations()
        assertEquals(1, ops.size)
        assertEquals(
            KvOperation.Batch(listOf(KvOperation.Put("a", 1), KvOperation.Put("b", 2))),
            ops.single(),
        )
    }

    @Test
    fun writeBatch_withSingleOperation_persistsAsPlainRecord() {
        openStore().use { store ->
            store.writeBatch(listOf(KvOperation.Put("a", 1)))
        }
        assertEquals(listOf<KvOperation>(KvOperation.Put("a", 1)), journalOperations())
    }

    @Test
    fun writeBatch_withEmptyList_writesNothing() {
        openStore().use { store ->
            store.writeBatch(emptyList())
        }
        assertEquals(emptyList<KvOperation>(), journalOperations())
    }

    // --- リプレイ ---

    @Test
    fun reopen_restoresBatchState() {
        openStore().use { store ->
            store.put("old", 1)
            store.writeBatch(
                listOf(
                    KvOperation.Clear,
                    KvOperation.Put("a", 1),
                    KvOperation.Remove("a"),
                    KvOperation.Put("b", setOf("x")),
                ),
            )
        }
        openStore().use { store ->
            assertEquals(mapOf<String, Any>("b" to setOf("x")), store.getAll())
        }
    }

    // --- クラッシュ注入（アトミック性の本丸） ---

    /** 永続化が [persistLimit] バイトで止まる sink（JournalFileTest と同じパターン）。 */
    private class CrashingSink(
        private val delegate: JournalSink,
        private val persistLimit: Long,
    ) : JournalSink {
        private var written = 0L

        override fun write(data: ByteArray) {
            val remaining = persistLimit - written
            if (remaining > 0) {
                delegate.write(data.copyOfRange(0, minOf(remaining, data.size.toLong()).toInt()))
            }
            written += data.size
        }

        override fun force() = delegate.force()

        override fun truncate(size: Long) {
            delegate.truncate(size)
            written = size
        }

        override fun close() = delegate.close()
    }

    @Test
    fun crashAtEveryBytePosition_batchIsAllOrNothing() {
        val batch = listOf(
            KvOperation.Put("a", 1),
            KvOperation.Remove("base"),
            KvOperation.Put("b", 2L),
        )
        // レコード境界: [header 8][base レコード][batch レコード]（レコード = len 4B + payload + crc 4B）
        val basePayload = KvOperationCodec.encode(KvOperation.Put("base", "x"))
        val batchPayload = KvOperationCodec.encode(KvOperation.Batch(batch))
        val afterBase = 8 + 4 + basePayload.size + 4
        val total = afterBase + 4 + batchPayload.size + 4

        for (limit in 0..total) {
            val dir = tmp.newFolder("crash-$limit")
            KvStore.open(
                directory = dir,
                name = "store",
                sinkFactory = { CrashingSink(FileSink(it), limit.toLong()) },
            ).use { store ->
                store.put("base", "x")
                store.writeBatch(batch)
            }
            // クラッシュ後の再オープン（通常の sink）。バッチの一部だけが適用された状態は存在しない
            KvStore.open(directory = dir, name = "store").use { store ->
                val expected: Map<String, Any> = when {
                    // 型引数の明示は必須: 推論に任せると 2L に引きずられてリテラル 1 が Long 化する
                    limit >= total -> mapOf<String, Any>("a" to 1, "b" to 2L)

                    limit >= afterBase -> mapOf("base" to "x")

                    else -> emptyMap()
                }
                assertEquals("persistLimit=$limit", expected, store.getAll())
            }
        }
    }
}
