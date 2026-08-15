@file:OptIn(ExperimentalForeignApi::class)

package io.github.kr9ly.daybook.io

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.fstat
import platform.posix.open
import platform.posix.pread
import platform.posix.stat

/**
 * open(2) + pread(2) による実装。
 *
 * fd を持ち続けるため、compaction の rename（inode は変わらない）後も同じファイルを
 * 読み続けられる（JVM actual の RandomAccessFile と同じ性質）。
 */
internal actual class PositionalFileReader actual constructor(path: FilePath) : AutoCloseable {

    private val fd: Int = open(path.path, O_RDONLY).also {
        if (it < 0) throw IoException("cannot open for read: ${path.path} (errno=$errno)")
    }

    actual fun length(): Long = memScoped {
        val st = alloc<stat>()
        if (fstat(fd, st.ptr) != 0) throw IoException("fstat failed (errno=$errno)")
        st.st_size
    }

    actual fun readFully(offset: Long, size: Int): ByteArray {
        val bytes = ByteArray(size)
        if (size == 0) return bytes
        var done = 0
        bytes.usePinned { pinned ->
            while (done < size) {
                val n = pread(fd, pinned.addressOf(done), (size - done).convert(), offset + done).toInt()
                if (n < 0) throw IoException("pread failed (errno=$errno)")
                // JVM actual の readFully（EOFException = IOException の一種）と同じく読み切れなければ例外
                if (n == 0) throw IoException("unexpected end of file: need $size bytes at offset $offset")
                done += n
            }
        }
        return bytes
    }

    actual override fun close() {
        close(fd)
    }
}
