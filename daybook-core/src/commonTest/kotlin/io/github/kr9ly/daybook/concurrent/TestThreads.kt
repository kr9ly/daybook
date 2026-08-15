package io.github.kr9ly.daybook.concurrent

/**
 * [body] を実行するスレッドを起動する（join なし）。
 * 完了の待ち合わせはテスト側が完了フラグ + [waitUntil] で行う。
 */
internal expect fun startTestThread(body: () -> Unit)
