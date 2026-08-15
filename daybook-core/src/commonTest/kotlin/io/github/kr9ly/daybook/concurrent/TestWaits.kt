package io.github.kr9ly.daybook.concurrent

/**
 * [condition] が真になるまでポーリングで待つ。[timeoutMillis] を超えたら false。
 * 別スレッド（配送・watcher）の到達を待ち合わせるテスト用。
 */
internal expect fun waitUntil(timeoutMillis: Int = 5_000, condition: () -> Boolean): Boolean
