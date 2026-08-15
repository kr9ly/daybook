@file:OptIn(ExperimentalForeignApi::class)

package io.github.kr9ly.daybook.concurrent

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.usleep

/**
 * [condition] が真になるまでポーリングで待つ。[timeoutMillis] を超えたら false。
 * 別スレッド（配送・watcher）の到達を待ち合わせるテスト用。
 */
internal fun waitUntil(timeoutMillis: Int = 5_000, condition: () -> Boolean): Boolean {
    val stepMicros = 10_000u
    var waitedMillis = 0
    while (!condition()) {
        if (waitedMillis >= timeoutMillis) return false
        usleep(stepMicros)
        waitedMillis += 10
    }
    return true
}
