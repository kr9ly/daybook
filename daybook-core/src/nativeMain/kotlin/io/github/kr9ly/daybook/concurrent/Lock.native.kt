@file:OptIn(ExperimentalForeignApi::class)

package io.github.kr9ly.daybook.concurrent

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock
import platform.posix.pthread_mutexattr_destroy
import platform.posix.pthread_mutexattr_init
import platform.posix.pthread_mutexattr_settype
import platform.posix.pthread_mutexattr_t

/**
 * pthread の再帰 mutex による実装（JVM actual の ReentrantLock と同じ再入可能セマンティクス）。
 *
 * expect に close 概念がない（JVM は GC 回収）ため、mutex 本体は解放しない。
 * Lock はストア等プロセス寿命のオブジェクトが持つ前提で、この数十バイトの残留は許容する。
 */
public actual class Lock {

    private val mutex = nativeHeap.alloc<pthread_mutex_t>()

    init {
        memScoped {
            val attr = alloc<pthread_mutexattr_t>()
            pthread_mutexattr_init(attr.ptr)
            pthread_mutexattr_settype(attr.ptr, pthreadMutexRecursiveType())
            pthread_mutex_init(mutex.ptr, attr.ptr)
            pthread_mutexattr_destroy(attr.ptr)
        }
    }

    public actual fun lock() {
        pthread_mutex_lock(mutex.ptr)
    }

    public actual fun unlock() {
        pthread_mutex_unlock(mutex.ptr)
    }
}

/**
 * PTHREAD_MUTEX_RECURSIVE の値。定数の cinterop 上の型がプラットフォームで分かれる
 * （Linux は UInt、Darwin は Int）ため expect で吸収する。
 */
internal expect fun pthreadMutexRecursiveType(): Int
