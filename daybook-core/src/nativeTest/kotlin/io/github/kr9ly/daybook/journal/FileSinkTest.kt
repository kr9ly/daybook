package io.github.kr9ly.daybook.journal

import io.github.kr9ly.daybook.io.createTempDirectory
import io.github.kr9ly.daybook.io.readFileOrEmpty
import kotlin.test.Test
import kotlin.test.assertContentEquals

class FileSinkTest {

    @Test
    fun write_appendsToExistingContent() {
        val dir = createTempDirectory()
        val file = dir.resolve("journal")
        FileSink(file).use { sink -> sink.write(byteArrayOf(1, 2)) }
        FileSink(file).use { sink ->
            sink.write(byteArrayOf(3))
            sink.write(byteArrayOf(4, 5))
            sink.force()
        }
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), readFileOrEmpty(file))
    }

    @Test
    fun truncate_thenWrite_appendsAtNewEnd() {
        val dir = createTempDirectory()
        val file = dir.resolve("journal")
        FileSink(file).use { sink ->
            sink.write(byteArrayOf(1, 2, 3, 4, 5))
            sink.truncate(2)
            sink.write(byteArrayOf(9))
        }
        assertContentEquals(byteArrayOf(1, 2, 9), readFileOrEmpty(file))
    }

    @Test
    fun appendMode_isImmuneToConcurrentAppendsByOtherHandle() {
        // O_APPEND のため、別ハンドルの追記後も自ハンドルの書き込みは実末尾に落ちる
        val dir = createTempDirectory()
        val file = dir.resolve("journal")
        FileSink(file).use { first ->
            first.write(byteArrayOf(1))
            FileSink(file).use { second -> second.write(byteArrayOf(2)) }
            first.write(byteArrayOf(3))
        }
        assertContentEquals(byteArrayOf(1, 2, 3), readFileOrEmpty(file))
    }
}
