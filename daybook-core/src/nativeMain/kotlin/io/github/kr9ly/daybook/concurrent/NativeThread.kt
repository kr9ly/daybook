@file:OptIn(ExperimentalForeignApi::class)

package io.github.kr9ly.daybook.concurrent

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import platform.posix.pthread_create
import platform.posix.pthread_detach
import platform.posix.pthread_tVar

/**
 * [body] を実行するデタッチ済み pthread を起動する。
 *
 * JVM のデーモンスレッド相当（join できない・プロセス終了で消える）。
 * 配送スレッド（NotificationDispatchThread）と watcher の監視スレッドが使う。
 * どちらも close の合図で自然に body を抜ける設計のため、join の必要がない。
 */
internal fun startDetachedThread(body: () -> Unit) {
    val ref = StableRef.create(body)
    memScoped {
        val thread = alloc<pthread_tVar>()
        val rc = pthread_create(
            thread.ptr,
            null,
            staticCFunction { arg: COpaquePointer? ->
                val bodyRef = arg!!.asStableRef<() -> Unit>()
                val threadBody = bodyRef.get()
                bodyRef.dispose()
                threadBody()
                null
            },
            ref.asCPointer(),
        )
        if (rc != 0) {
            ref.dispose()
            throw IllegalStateException("pthread_create failed: $rc")
        }
        pthread_detach(thread.value)
    }
}
