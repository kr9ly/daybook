package io.github.kr9ly.daybook.journal

import io.github.kr9ly.daybook.io.FilePath
import java.io.RandomAccessFile

internal actual class FileInterProcessLock actual constructor(file: FilePath) : InterProcessLock {

    private val channel = RandomAccessFile(file.path, "rw").channel

    actual override fun <T> withLock(body: () -> T): T {
        val lock = channel.lock()
        try {
            return body()
        } finally {
            lock.release()
        }
    }

    actual override fun close() {
        channel.close()
    }
}
