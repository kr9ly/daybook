package io.github.kr9ly.daybook.journal

/**
 * fd への書き込みを永続ストレージまで同期する。成功なら 0、失敗なら -1（errno 設定）。
 *
 * JVM actual（FileChannel.force）と耐久性の保証水準を揃えるためのプラットフォーム分岐。
 * Linux の fsync(2) はデバイスへの書き出しまで含むが、darwin の fsync(2) はドライブ内キャッシュへの到達までしか保証しないため、apple 側は fcntl(F_FULLFSYNC) を使う。
 */
internal expect fun fullFsync(fd: Int): Int
