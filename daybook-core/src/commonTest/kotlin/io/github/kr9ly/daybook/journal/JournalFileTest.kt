package io.github.kr9ly.daybook.journal

import io.github.kr9ly.daybook.io.FilePath
import io.github.kr9ly.daybook.io.appendFileBytes
import io.github.kr9ly.daybook.io.createTempDirectory
import io.github.kr9ly.daybook.io.readFileBytes
import io.github.kr9ly.daybook.io.writeFileBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JournalFileTest {

    private val tmp = createTempDirectory()

    private fun journalFile(): FilePath = tmp.resolve("journal.db")

    private fun fileLength(file: FilePath): Long = readFileBytes(file).size.toLong()

    private fun assertRecords(expected: List<ByteArray>, actual: List<ByteArray>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEachIndexed { i, (e, a) ->
            assertContentEquals(e, a, "record $i")
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
            byteArrayOf(), // 空 payload
            byteArrayOf(1),
            byteArrayOf(0, -1, 127, -128, 42), // バイナリ値
            ByteArray(64 * 1024) { (it % 251).toByte() }, // 大きめ
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
            assertFailsWith<IllegalArgumentException> {
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
        val bytes = readFileBytes(file)
        val sizeBefore = bytes.size
        bytes[bytes.size - 6]++ // 最終レコード payload 末尾
        writeFileBytes(file, bytes)

        JournalFile.open(file).use { journal ->
            assertRecords(listOf(byteArrayOf(1, 1, 1)), journal.replayedRecords)
            assertTrue(journal.recoveredFromCorruption)
        }
        assertTrue(fileLength(file) < sizeBefore)

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
        val bytes = readFileBytes(file)
        bytes[8 + 9 + 4]++
        writeFileBytes(file, bytes)

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
        appendFileBytes(file, byteArrayOf(0x7F, -1, -1, -1, 0, 0))

        JournalFile.open(file).use { journal ->
            assertRecords(listOf(byteArrayOf(1)), journal.replayedRecords)
            assertTrue(journal.recoveredFromCorruption)
        }
    }

    @Test
    fun negativeLengthField_isTreatedAsCorruption() {
        val file = journalFile()
        JournalFile.open(file).use { it.append(byteArrayOf(1)) }
        // 2 レコード目として「長さ = -1」の残骸を書く
        appendFileBytes(file, byteArrayOf(-1, -1, -1, -1, 0, 0))

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
        val full = readFileBytes(reference)

        // レコード境界 = ヘッダ 8B + 各レコード (4 + len + 4)B の累積
        val boundaries = mutableListOf(8)
        records.forEach { boundaries.add(boundaries.last() + 4 + it.size + 4) }
        assertEquals(full.size, boundaries.last())

        for (cut in 0..full.size) {
            val file = tmp.resolve("cut-$cut.db")
            writeFileBytes(file, full.copyOfRange(0, cut))
            val expectedCount = boundaries.count { it <= cut } - 1
            JournalFile.open(file).use { journal ->
                assertRecords(
                    records.take(expectedCount.coerceAtLeast(0)),
                    journal.replayedRecords,
                )
                // cut=0 は空ファイル = 新規扱い。それ以外の中途半端な位置は破損復旧
                assertEquals(
                    cut != 0 && cut !in boundaries,
                    journal.recoveredFromCorruption,
                    "cut=$cut",
                )
            }
        }
    }

    // --- 差分リード（他プロセスの追記分） ---

    /** ジャーナルのレコード 1 件ぶんのバイト列（[length][payload][crc32]）を組み立てる。 */
    private fun encodeRecord(payload: ByteArray): ByteArray {
        val body = ByteArray(4 + payload.size)
        body[0] = (payload.size ushr 24).toByte()
        body[1] = (payload.size ushr 16).toByte()
        body[2] = (payload.size ushr 8).toByte()
        body[3] = payload.size.toByte()
        payload.copyInto(body, 4)
        val crc = Crc32()
        crc.update(body, 0, body.size)
        val crcValue = crc.value.toInt()
        return body + byteArrayOf(
            (crcValue ushr 24).toByte(),
            (crcValue ushr 16).toByte(),
            (crcValue ushr 8).toByte(),
            crcValue.toByte(),
        )
    }

    @Test
    fun readNewRecords_returnsOtherHandleAppendsIncrementally() {
        val file = journalFile()
        JournalFile.open(file).use { reader ->
            // 別ハンドル = 他プロセスの追記の模擬
            JournalFile.open(file).use { writer ->
                writer.append(byteArrayOf(1))
                writer.append(byteArrayOf(2, 2))
            }
            assertRecords(listOf(byteArrayOf(1), byteArrayOf(2, 2)), reader.readNewRecords())
            assertTrue(reader.readNewRecords().isEmpty()) // 消費済み
            JournalFile.open(file).use { it.append(byteArrayOf(3)) }
            assertRecords(listOf(byteArrayOf(3)), reader.readNewRecords())
        }
    }

    @Test
    fun readNewRecords_doesNotReturnOwnAppends() {
        JournalFile.open(journalFile()).use { journal ->
            journal.append(byteArrayOf(1))
            assertTrue(journal.readNewRecords().isEmpty())
        }
    }

    @Test
    fun readNewRecords_advancesLengthToConsumedBoundary() {
        val file = journalFile()
        JournalFile.open(file).use { reader ->
            JournalFile.open(file).use { it.append(byteArrayOf(1, 1, 1)) }
            reader.readNewRecords()
            assertEquals(fileLength(file), reader.length)
        }
    }

    @Test
    fun readNewRecords_incompleteTail_isKeptUntilComplete() {
        val file = journalFile()
        JournalFile.open(file).use { reader ->
            val record = encodeRecord(byteArrayOf(7, 7, 7))
            // 書き込み途中の状態を 1 バイトずつ再現する
            for (cut in 1 until record.size) {
                writeFileBytes(file, readFileBytes(file).copyOfRange(0, 8)) // ヘッダだけに戻す
                appendFileBytes(file, record.copyOfRange(0, cut))
                assertTrue(reader.readNewRecords().isEmpty(), "cut=$cut")
            }
            // 完成したら読める。切り捨ては起きていない
            appendFileBytes(file, record.copyOfRange(record.size - 1, record.size))
            assertRecords(listOf(byteArrayOf(7, 7, 7)), reader.readNewRecords())
        }
    }

    @Test
    fun readNewRecords_stopsBeforeInvalidTailWithoutTruncating() {
        val file = journalFile()
        JournalFile.open(file).use { reader ->
            JournalFile.open(file).use { it.append(byteArrayOf(1)) }
            // 完全な形だが CRC が合わないテール（書き込み途中のゴミ）を続ける
            val garbage = encodeRecord(byteArrayOf(9)).also { it[it.size - 1]++ }
            appendFileBytes(file, garbage)
            val sizeBefore = fileLength(file)

            assertRecords(listOf(byteArrayOf(1)), reader.readNewRecords())
            assertTrue(reader.readNewRecords().isEmpty()) // 不正テールの手前で待つ
            assertEquals(sizeBefore, fileLength(file)) // 切り捨てはしない
        }
    }

    @Test
    fun foreignFile_throwsInsteadOfSilentlyOverwriting() {
        val file = journalFile()
        writeFileBytes(file, "this is not a journal".encodeToByteArray())
        assertFailsWith<JournalFormatException> { JournalFile.open(file) }
        // ファイルは無傷のまま
        assertEquals("this is not a journal", readFileBytes(file).decodeToString())
    }

    @Test
    fun unknownVersion_throws() {
        val file = journalFile()
        JournalFile.open(file).use {}
        val bytes = readFileBytes(file)
        bytes[7] = 99 // version フィールド
        writeFileBytes(file, bytes)
        assertFailsWith<JournalFormatException> { JournalFile.open(file) }
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
            val file = tmp.resolve("crash-$limit.db")
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
