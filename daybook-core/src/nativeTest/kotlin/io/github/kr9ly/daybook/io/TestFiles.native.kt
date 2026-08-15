@file:OptIn(ExperimentalForeignApi::class)

package io.github.kr9ly.daybook.io

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

internal actual fun writeFileBytes(path: FilePath, bytes: ByteArray) {
    val file = fopen(path.path, "wb") ?: throw IoException("fopen failed: ${path.path} (errno=$errno)")
    try {
        if (bytes.isNotEmpty()) {
            val written = bytes.usePinned { pinned ->
                fwrite(pinned.addressOf(0), 1u, bytes.size.toULong(), file)
            }
            if (written.toInt() != bytes.size) {
                throw IoException("fwrite failed: ${path.path} (errno=$errno)")
            }
        }
    } finally {
        fclose(file)
    }
}
