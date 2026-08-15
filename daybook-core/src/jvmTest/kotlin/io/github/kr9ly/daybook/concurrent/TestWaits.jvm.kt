package io.github.kr9ly.daybook.concurrent

internal actual fun waitUntil(timeoutMillis: Int, condition: () -> Boolean): Boolean {
    var waitedMillis = 0
    while (!condition()) {
        if (waitedMillis >= timeoutMillis) return false
        Thread.sleep(10)
        waitedMillis += 10
    }
    return true
}
