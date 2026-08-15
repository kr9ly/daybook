@file:OptIn(ExperimentalForeignApi::class)

package io.github.kr9ly.daybook.journal

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.F_FULLFSYNC
import platform.posix.fcntl
import platform.posix.fsync

internal actual fun fullFsync(fd: Int): Int {
    if (fcntl(fd, F_FULLFSYNC) != -1) return 0
    // F_FULLFSYNC 非対応のファイルシステム（ネットワーク FS 等）では fsync(2) に切り下げる。
    // OpenJDK の macOS 実装（FileDispatcherImpl.force0）と同じフォールバック。
    return fsync(fd)
}
