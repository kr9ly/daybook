package io.github.kr9ly.daybook.concurrent

internal actual fun pthreadMutexRecursiveType(): Int = platform.posix.PTHREAD_MUTEX_RECURSIVE
