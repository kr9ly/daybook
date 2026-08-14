package io.github.kr9ly.daybook.io

import java.io.RandomAccessFile

internal actual class PositionalFileReader actual constructor(path: FilePath) : AutoCloseable {

    private val file = RandomAccessFile(path.path, "r")

    actual fun length(): Long = file.length()

    actual fun readFully(offset: Long, size: Int): ByteArray {
        val bytes = ByteArray(size)
        file.seek(offset)
        file.readFully(bytes)
        return bytes
    }

    actual override fun close() {
        file.close()
    }
}
