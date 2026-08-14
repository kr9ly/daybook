package io.github.kr9ly.daybook.concurrent

import java.util.concurrent.locks.ReentrantLock

internal actual class Lock {

    private val delegate = ReentrantLock()

    actual fun lock() {
        delegate.lock()
    }

    actual fun unlock() {
        delegate.unlock()
    }
}
