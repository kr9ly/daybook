package io.github.kr9ly.daybook.journal

import io.github.kr9ly.daybook.io.FilePath
import io.github.kr9ly.daybook.io.toFilePath
import java.io.File

/**
 * java.io.File を受けるテスト用アダプタ。
 *
 * 1.x 由来の JVM テストは File でパスを組み立てるため、common API（FilePath 受け）との
 * 境界をここで吸収し、テスト本文の変更を最小化する。
 */

internal fun JournalFile.Companion.open(
    file: File,
    syncMode: SyncMode = SyncMode.ASYNC,
    sinkFactory: (FilePath) -> JournalSink = ::FileSink,
): JournalFile = open(file.toFilePath(), syncMode, sinkFactory)

internal fun JournalDirectory(dir: File, name: String): JournalDirectory =
    JournalDirectory(dir.toFilePath(), name)

internal fun FileInterProcessLock(file: File): FileInterProcessLock =
    FileInterProcessLock(file.toFilePath())

internal fun FileSink(file: File): FileSink = FileSink(file.toFilePath())
