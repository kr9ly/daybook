@file:OptIn(ExperimentalForeignApi::class)

package io.github.kr9ly.daybook.journal

import io.github.kr9ly.daybook.io.FILE_MODE_RW
import io.github.kr9ly.daybook.io.FilePath
import io.github.kr9ly.daybook.io.IoException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.O_APPEND
import platform.posix.O_CREAT
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.fsync
import platform.posix.ftruncate
import platform.posix.open
import platform.posix.write

public actual class FileSink actual constructor(path: FilePath) : JournalSink {

    private val fd: Int = open(path.path, O_WRONLY or O_CREAT or O_APPEND, FILE_MODE_RW).also {
        if (it < 0) throw IoException("cannot open for append: ${path.path} (errno=$errno)")
    }

    actual override fun write(data: ByteArray) {
        if (data.isEmpty()) return
        var done = 0
        data.usePinned { pinned ->
            while (done < data.size) {
                val n = write(fd, pinned.addressOf(done), (data.size - done).convert()).toInt()
                if (n < 0) throw IoException("write failed (errno=$errno)")
                done += n
            }
        }
    }

    actual override fun force() {
        if (fsync(fd) != 0) throw IoException("fsync failed (errno=$errno)")
    }

    actual override fun truncate(size: Long) {
        if (ftruncate(fd, size) != 0) throw IoException("ftruncate failed (errno=$errno)")
    }

    actual override fun close() {
        close(fd)
    }
}
