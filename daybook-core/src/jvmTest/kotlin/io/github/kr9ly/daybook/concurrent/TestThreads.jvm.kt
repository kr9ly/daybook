package io.github.kr9ly.daybook.concurrent

internal actual fun startTestThread(body: () -> Unit) {
    Thread(body).start()
}
