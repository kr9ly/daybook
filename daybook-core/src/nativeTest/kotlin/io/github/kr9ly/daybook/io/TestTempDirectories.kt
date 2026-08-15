@file:OptIn(ExperimentalForeignApi::class)

package io.github.kr9ly.daybook.io

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.errno
import platform.posix.mkdtemp

/**
 * テスト用の一時ディレクトリを作る（mkdtemp(3)）。
 *
 * 後始末はしない: テストのファイル残骸は /tmp 掃除に任せる
 * （JVM テストの TemporaryFolder 相当の仕組みを持ち込むほどの量ではない）。
 */
internal fun createTempDirectory(): FilePath {
    val template = "/tmp/daybook-native-test-XXXXXX".encodeToByteArray() + byteArrayOf(0)
    val path = template.usePinned { pinned ->
        mkdtemp(pinned.addressOf(0))?.toKString()
    } ?: throw IoException("mkdtemp failed (errno=$errno)")
    return FilePath(path)
}
