@file:OptIn(ExperimentalForeignApi::class)

package io.github.kr9ly.daybook.concurrent

import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction

/**
 * [body] を実行するデタッチ済み pthread を起動する。
 *
 * JVM のデーモンスレッド相当（join できない・プロセス終了で消える）。
 * 配送スレッド（NotificationDispatchThread）と watcher の監視スレッドが使う。
 * どちらも close の合図で自然に body を抜ける設計のため、join の必要がない。
 */
internal fun startDetachedThread(body: () -> Unit) {
    val ref = StableRef.create(body)
    val rc = createDetachedPthread(
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
}

/**
 * pthread_create + pthread_detach の呼び出し。pthread_t の cinterop 上の型が
 * プラットフォームで分かれる（Linux は整数、Darwin はポインタ）ため expect で吸収する。
 * 返り値は pthread_create のリターンコード（0 = 成功）。
 */
internal expect fun createDetachedPthread(
    routine: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>,
    arg: COpaquePointer?,
): Int
