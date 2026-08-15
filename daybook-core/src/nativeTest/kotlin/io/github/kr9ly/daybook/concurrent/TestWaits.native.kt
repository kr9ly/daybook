@file:OptIn(ExperimentalForeignApi::class)

package io.github.kr9ly.daybook.concurrent

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.usleep

internal actual fun waitUntil(timeoutMillis: Int, condition: () -> Boolean): Boolean {
    val stepMicros = 10_000u
    var waitedMillis = 0
    while (!condition()) {
        if (waitedMillis >= timeoutMillis) return false
        usleep(stepMicros)
        waitedMillis += 10
    }
    return true
}
