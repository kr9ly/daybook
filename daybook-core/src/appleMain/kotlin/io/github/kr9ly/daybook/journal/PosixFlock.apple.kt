package io.github.kr9ly.daybook.journal

internal actual fun posixFlock(fd: Int, operation: Int): Int = platform.posix.flock(fd, operation)
