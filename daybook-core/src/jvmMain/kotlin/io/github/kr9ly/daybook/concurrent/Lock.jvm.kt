package io.github.kr9ly.daybook.concurrent

import java.util.concurrent.locks.ReentrantLock

public actual class Lock {

    private val delegate = ReentrantLock()

    public actual fun lock() {
        delegate.lock()
    }

    public actual fun unlock() {
        delegate.unlock()
    }
}
