@file:OptIn(ExperimentalForeignApi::class)

package io.github.kr9ly.daybook.kv

import io.github.kr9ly.daybook.concurrent.startDetachedThread
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import platform.posix.pthread_cond_init
import platform.posix.pthread_cond_signal
import platform.posix.pthread_cond_t
import platform.posix.pthread_cond_wait
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock

/**
 * pthread の mutex + 条件変数によるキュー + 専用配送スレッド。
 *
 * Worker API は非推奨方向のため使わない裁定（KMP-2.0.md）。
 * close 後の deliver は JVM actual（shutdown 済み Executor）と同様に拒否する。
 * mutex / 条件変数は解放しない: 配送スレッドと deliver 呼び出しの競合を考えると
 * 安全に破棄できるタイミングがなく、store の close は実運用でプロセス寿命に一度きりのため
 * （テストの多数回 close でも残留は数十バイト × 回数で許容範囲）。
 */
internal actual class NotificationDispatchThread : ChangeNotificationDelivery {

    private val mutex = nativeHeap.alloc<pthread_mutex_t>()
    private val notEmpty = nativeHeap.alloc<pthread_cond_t>()
    private val queue = ArrayDeque<() -> Unit>()
    private var closed = false

    init {
        pthread_mutex_init(mutex.ptr, null)
        pthread_cond_init(notEmpty.ptr, null)
        startDetachedThread { runLoop() }
    }

    actual override fun deliver(action: () -> Unit) {
        pthread_mutex_lock(mutex.ptr)
        try {
            check(!closed) { "delivery already closed" }
            queue.addLast(action)
            pthread_cond_signal(notEmpty.ptr)
        } finally {
            pthread_mutex_unlock(mutex.ptr)
        }
    }

    actual fun close() {
        pthread_mutex_lock(mutex.ptr)
        closed = true
        pthread_cond_signal(notEmpty.ptr)
        pthread_mutex_unlock(mutex.ptr)
    }

    private fun runLoop() {
        while (true) {
            pthread_mutex_lock(mutex.ptr)
            while (queue.isEmpty() && !closed) {
                pthread_cond_wait(notEmpty.ptr, mutex.ptr)
            }
            val action = queue.removeFirstOrNull()
            pthread_mutex_unlock(mutex.ptr)
            // close 済みかつキューが空になったら終了（enqueue 済み分は配送し切る契約）
            if (action == null) return
            runCatching { action() }.onFailure { it.printStackTrace() }
        }
    }
}
