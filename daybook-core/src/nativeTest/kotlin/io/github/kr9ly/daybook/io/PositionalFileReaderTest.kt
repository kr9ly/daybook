package io.github.kr9ly.daybook.io

import io.github.kr9ly.daybook.journal.FileSink
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PositionalFileReaderTest {

    @Test
    fun lengthAndRead_reflectFileContent() {
        val dir = createTempDirectory()
        val file = dir.resolve("journal")
        FileSink(file).use { sink -> sink.write(byteArrayOf(10, 20, 30, 40, 50)) }

        PositionalFileReader(file).use { reader ->
            assertEquals(5L, reader.length())
            assertContentEquals(byteArrayOf(10, 20, 30, 40, 50), reader.readFully(0, 5))
            assertContentEquals(byteArrayOf(30, 40), reader.readFully(2, 2))
            assertContentEquals(ByteArray(0), reader.readFully(0, 0))
        }
    }

    @Test
    fun readFully_beyondEndOfFile_throwsIoException() {
        val dir = createTempDirectory()
        val file = dir.resolve("journal")
        FileSink(file).use { sink -> sink.write(byteArrayOf(1, 2, 3)) }

        PositionalFileReader(file).use { reader ->
            assertFailsWith<IoException> { reader.readFully(0, 4) }
        }
    }

    @Test
    fun open_missingFile_throwsIoException() {
        val dir = createTempDirectory()
        assertFailsWith<IoException> { PositionalFileReader(dir.resolve("missing")) }
    }

    @Test
    fun openHandle_survivesRename() {
        // compaction の rename 後も同じ fd で読み続けられる（KDoc の契約）
        val dir = createTempDirectory()
        val file = dir.resolve("journal")
        FileSink(file).use { sink -> sink.write(byteArrayOf(1, 2, 3)) }

        PositionalFileReader(file).use { reader ->
            renameFile(file, dir.resolve("renamed"))
            assertContentEquals(byteArrayOf(1, 2, 3), reader.readFully(0, 3))
        }
    }

    @Test
    fun length_seesGrowthByOtherHandle() {
        val dir = createTempDirectory()
        val file = dir.resolve("journal")
        FileSink(file).use { sink -> sink.write(byteArrayOf(1)) }

        PositionalFileReader(file).use { reader ->
            assertEquals(1L, reader.length())
            FileSink(file).use { sink -> sink.write(byteArrayOf(2, 3)) }
            assertEquals(3L, reader.length())
            assertContentEquals(byteArrayOf(2, 3), reader.readFully(1, 2))
        }
    }
}
