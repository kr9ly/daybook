@file:OptIn(ExperimentalForeignApi::class)

package io.github.kr9ly.daybook.io

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.chmod
import platform.posix.errno

internal actual fun setDirectoryWritable(path: FilePath, writable: Boolean) {
    val mode = if (writable) 0b111_101_101u else 0b101_101_101u // 755 / 555
    if (chmod(path.path, mode) != 0) {
        throw IoException("chmod failed: ${path.path} (errno=$errno)")
    }
}
