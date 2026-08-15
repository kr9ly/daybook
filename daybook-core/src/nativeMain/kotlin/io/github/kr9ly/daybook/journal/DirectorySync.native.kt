@file:OptIn(ExperimentalForeignApi::class)

package io.github.kr9ly.daybook.journal

import io.github.kr9ly.daybook.io.FilePath
import io.github.kr9ly.daybook.io.IoException
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.open

/** ディレクトリ fd への同期。JVM actual（java.nio.file）と同じ保証水準を [fullFsync] で発行する。 */
internal class PosixDirectorySync : DirectorySync {
    override fun sync(directory: FilePath) {
        val fd = open(directory.path, O_RDONLY)
        if (fd < 0) throw IoException("cannot open directory: ${directory.path} (errno=$errno)")
        try {
            if (fullFsync(fd) != 0) throw IoException("directory fsync failed: ${directory.path} (errno=$errno)")
        } finally {
            close(fd)
        }
    }
}

public actual fun platformDirectorySync(): DirectorySync = PosixDirectorySync()
