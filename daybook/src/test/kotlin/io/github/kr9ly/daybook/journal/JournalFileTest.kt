package io.github.kr9ly.daybook.journal

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JournalFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun journalFile(): File = File(tmp.root, "journal.db")

    private fun assertRecords(expected: List<ByteArray>, actual: List<ByteArray>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEachIndexed { i, (e, a) ->
            assertArrayEquals("record $i", e, a)
        }
    }

    // --- 基本の往復 ---

    @Test
    fun openFreshJournal_hasNoRecords() {
        JournalFile.open(journalFile()).use {
            assertTrue(it.replayedRecords.isEmpty())
            assertFalse(it.recoveredFromCorruption)
        }
    }

    @Test
    fun appendAndReplay_roundTrip() {
        val records = listOf(
            byteArrayOf(),                                  // 空 payload
            byteArrayOf(1),
            byteArrayOf(0, -1, 127, -128, 42),              // バイナリ値
            ByteArray(64 * 1024) { (it % 251).toByte() },   // 大きめ
        )
        JournalFile.open(journalFile()).use { journal ->
            records.forEach { journal.append(it) }
        }
        JournalFile.open(journalFile()).use { journal ->
            assertRecords(records, journal.replayedRecords)
            assertFalse(journal.recoveredFromCorruption)
        }
    }

    @Test
    fun reopen_appendsContinueAfterExistingRecords() {
        JournalFile.open(journalFile()).use { it.append(byteArrayOf(1)) }
        JournalFile.open(journalFile()).use { it.append(byteArrayOf(2)) }
        JournalFile.open(journalFile()).use { journal ->
            assertRecords(listOf(byteArrayOf(1), byteArrayOf(2)), journal.replayedRecords)
        }
    }

    @Test
    fun append_rejectsOversizedPayload() {
        JournalFile.open(journalFile()).use { journal ->
            assertThrows(IllegalArgumentException::class.java) {
                journal.append(ByteArray(JournalFile.MAX_PAYLOAD_SIZE + 1))
            }
        }
    }

    // --- 破損テールの切り捨て ---

    @Test
    fun corruptedLastRecord_isDroppedAndFileTruncated() {
        val file = journalFile()
        JournalFile.open(file).use {
            it.append(byteArrayOf(1, 1, 1))
            it.append(byteArrayOf(2, 2, 2))
        }
        // 最後のレコードの payload を 1 バイト破壊（CRC 不一致にする）
        val bytes = file.readBytes()
        val sizeBefore = bytes.size
        bytes[bytes.size - 6]++  // 最終レコード payload 末尾
        file.writeBytes(bytes)

        JournalFile.open(file).use { journal ->
            assertRecords(listOf(byteArrayOf(1, 1, 1)), journal.replayedRecords)
            assertTrue(journal.recoveredFromCorruption)
        }
        assertTrue(file.length() < sizeBefore)

        // 復旧後は正常に追記でき、破壊されたレコードは戻らない
        JournalFile.open(file).use { it.append(byteArrayOf(3)) }
        JournalFile.open(file).use { journal ->
            assertRecords(listOf(byteArrayOf(1, 1, 1), byteArrayOf(3)), journal.replayedRecords)
            assertFalse(journal.recoveredFromCorruption)
        }
    }

    @Test
    fun corruptedMiddleRecord_dropsEverythingAfterIt() {
        val file = journalFile()
        JournalFile.open(file).use {
            it.append(byteArrayOf(1))
            it.append(byteArrayOf(2))
            it.append(byteArrayOf(3))
        }
        // 2 レコード目（ヘッダ 8B + レコード1 9B の直後）の payload を破壊
        val bytes = file.readBytes()
        bytes[8 + 9 + 4]++
        file.writeBytes(bytes)

        JournalFile.open(file).use { journal ->
            assertRecords(listOf(byteArrayOf(1)), journal.replayedRecords)
            assertTrue(journal.recoveredFromCorruption)
        }
    }

    @Test
    fun insaneLengthField_isTreatedAsCorruption() {
        val file = journalFile()
        JournalFile.open(file).use { it.append(byteArrayOf(1)) }
        // 2 レコード目として「長さ = Int.MAX_VALUE」の残骸を書く
        file.appendBytes(byteArrayOf(0x7F, -1, -1, -1, 0, 0))

        JournalFile.open(file).use { journal ->
            assertRecords(listOf(byteArrayOf(1)), journal.replayedRecords)
            assertTrue(journal.recoveredFromCorruption)
        }
    }

    @Test
    fun truncatedTail_everyCutPosition_recoversFullyPersistedRecords() {
        val records = listOf(byteArrayOf(1, 1), byteArrayOf(2), byteArrayOf(3, 3, 3))
        val reference = journalFile()
        JournalFile.open(reference).use { j -> records.forEach { j.append(it) } }
        val full = reference.readBytes()

        // レコード境界 = ヘッダ 8B + 各レコード (4 + len + 4)B の累積
        val boundaries = mutableListOf(8)
        records.forEach { boundaries.add(boundaries.last() + 4 + it.size + 4) }
        assertEquals(full.size, boundaries.last())

        for (cut in 0..full.size) {
            val file = File(tmp.root, "cut-$cut.db")
            file.writeBytes(full.copyOfRange(0, cut))
            val expectedCount = boundaries.count { it <= cut } - 1
            JournalFile.open(file).use { journal ->
                assertRecords(
                    records.take(expectedCount.coerceAtLeast(0)),
                    journal.replayedRecords,
                )
                // cut=0 は空ファイル = 新規扱い。それ以外の中途半端な位置は破損復旧
                assertEquals(
                    "cut=$cut",
                    cut != 0 && cut !in boundaries,
                    journal.recoveredFromCorruption,
                )
            }
        }
    }

    // --- 別物ファイルの保護 ---

    @Test
    fun foreignFile_throwsInsteadOfSilentlyOverwriting() {
        val file = journalFile()
        file.writeBytes("this is not a journal".toByteArray())
        assertThrows(JournalFormatException::class.java) { JournalFile.open(file) }
        // ファイルは無傷のまま
        assertEquals("this is not a journal", file.readText())
    }

    @Test
    fun unknownVersion_throws() {
        val file = journalFile()
        JournalFile.open(file).use {}
        val bytes = file.readBytes()
        bytes[7] = 99  // version フィールド
        file.writeBytes(bytes)
        assertThrows(JournalFormatException::class.java) { JournalFile.open(file) }
    }

    // --- クラッシュ注入 ---

    /**
     * 永続化が [persistLimit] バイトで止まる sink。
     * 「そのバイト位置でプロセスが死んだ」あとのファイル状態を決定的に作る。
     */
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
    fun crashAtEveryBytePosition_recoversAllFullyPersistedRecords() {
        val records = listOf(byteArrayOf(1, 1), byteArrayOf(2), byteArrayOf(3, 3, 3))
        val boundaries = mutableListOf(8)
        records.forEach { boundaries.add(boundaries.last() + 4 + it.size + 4) }
        val totalBytes = boundaries.last()

        for (limit in 0..totalBytes) {
            val file = File(tmp.root, "crash-$limit.db")
            JournalFile.open(
                file,
                sinkFactory = { CrashingSink(FileSink(it), limit.toLong()) },
            ).use { journal ->
                records.forEach { journal.append(it) }
            }
            // クラッシュ後の再オープン（通常の sink）
            JournalFile.open(file).use { journal ->
                val expectedCount = (boundaries.count { it <= limit } - 1).coerceAtLeast(0)
                assertRecords(records.take(expectedCount), journal.replayedRecords)
            }
        }
    }

    // --- fsync ポリシー ---

    private class CountingSink(private val delegate: JournalSink) : JournalSink {
        var forceCount = 0

        override fun write(data: ByteArray) = delegate.write(data)

        override fun force() {
            forceCount++
            delegate.force()
        }

        override fun truncate(size: Long) = delegate.truncate(size)

        override fun close() = delegate.close()
    }

    @Test
    fun syncMode_forcesAfterEveryAppend() {
        var sink: CountingSink? = null
        JournalFile.open(
            journalFile(),
            syncMode = SyncMode.SYNC,
            sinkFactory = { CountingSink(FileSink(it)).also { s -> sink = s } },
        ).use { journal ->
            val afterOpen = sink!!.forceCount
            journal.append(byteArrayOf(1))
            journal.append(byteArrayOf(2))
            assertEquals(afterOpen + 2, sink!!.forceCount)
        }
    }

    @Test
    fun asyncMode_neverForces() {
        var sink: CountingSink? = null
        JournalFile.open(
            journalFile(),
            syncMode = SyncMode.ASYNC,
            sinkFactory = { CountingSink(FileSink(it)).also { s -> sink = s } },
        ).use { journal ->
            journal.append(byteArrayOf(1))
            journal.append(byteArrayOf(2))
            assertEquals(0, sink!!.forceCount)
        }
    }
}
