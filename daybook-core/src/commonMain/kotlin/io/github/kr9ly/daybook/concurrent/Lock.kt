package io.github.kr9ly.daybook.concurrent

import io.github.kr9ly.daybook.internal.DaybookInternalApi

/**
 * プロセス内の相互排他ロック。
 *
 * kotlinx-atomicfu / stdlib common atomics を採らない裁定（KMP-2.0.md）に基づく
 * 自前の最小 expect/actual。JVM actual は ReentrantLock。
 * daybook-test が common のコンテナ状態を守るために使うため opt-in 公開。
 */
@DaybookInternalApi
public expect class Lock() {
    public fun lock()
    public fun unlock()
}

/** ロックを取得して [body] を実行する。 */
@DaybookInternalApi
public inline fun <T> Lock.withLock(body: () -> T): T {
    lock()
    try {
        return body()
    } finally {
        unlock()
    }
}
