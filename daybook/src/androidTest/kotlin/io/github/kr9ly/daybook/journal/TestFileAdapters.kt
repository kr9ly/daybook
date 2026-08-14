package io.github.kr9ly.daybook.journal

import io.github.kr9ly.daybook.io.FilePath
import java.io.File

/**
 * java.io.File を受けるテスト用アダプタ。
 *
 * 1.x 由来の Instrumentation テストは File でパスを組み立てるため、
 * common API（FilePath 受け）との境界をここで吸収し、テスト本文の変更を最小化する。
 */

internal fun JournalWatcherFactory.watch(directory: File, onChange: () -> Unit): AutoCloseable =
    watch(FilePath(directory.path), onChange)

internal fun DirectorySync.sync(directory: File) {
    sync(FilePath(directory.path))
}
