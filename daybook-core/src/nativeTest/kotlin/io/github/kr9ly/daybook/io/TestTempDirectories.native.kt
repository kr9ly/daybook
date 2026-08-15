@file:OptIn(ExperimentalForeignApi::class)

package io.github.kr9ly.daybook.io

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.errno
import platform.posix.mkdtemp

/** mkdtemp(3) による一時ディレクトリ作成。 */
internal actual fun createTempDirectory(): FilePath {
    val template = "/tmp/daybook-native-test-XXXXXX".encodeToByteArray() + byteArrayOf(0)
    val path = template.usePinned { pinned ->
        mkdtemp(pinned.addressOf(0))?.toKString()
    } ?: throw IoException("mkdtemp failed (errno=$errno)")
    return FilePath(path)
}
