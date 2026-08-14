package io.github.kr9ly.daybook.concurrent

/**
 * プロセス内の相互排他ロック。
 *
 * kotlinx-atomicfu / stdlib common atomics を採らない裁定（KMP-2.0.md）に基づく
 * 自前の最小 expect/actual。JVM actual は ReentrantLock。
 */
internal expect class Lock() {
    fun lock()
    fun unlock()
}

/** ロックを取得して [body] を実行する。 */
internal inline fun <T> Lock.withLock(body: () -> T): T {
    lock()
    try {
        return body()
    } finally {
        unlock()
    }
}
