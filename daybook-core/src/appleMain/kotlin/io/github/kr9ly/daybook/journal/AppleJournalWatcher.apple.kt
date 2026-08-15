package io.github.kr9ly.daybook.journal

/**
 * 暫定スタブ。kqueue / dispatch source による実装に置き換えるまで、
 * multiProcess の Daybook.open を fail-fast にする（シングルプロセス経路はここを通らない）。
 *
 * iOS の multiProcess（App Group 共有）は 2.0 時点で実装あり・保証なしの扱い（裁定 2026-08-15）。
 */
internal actual fun platformJournalWatcherFactory(): JournalWatcherFactory =
    throw UnsupportedOperationException(
        "multiProcess journal watcher is not implemented yet on Apple platforms",
    )
