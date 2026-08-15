package io.github.kr9ly.daybook.journal

import platform.posix.fsync

internal actual fun fullFsync(fd: Int): Int = fsync(fd)
