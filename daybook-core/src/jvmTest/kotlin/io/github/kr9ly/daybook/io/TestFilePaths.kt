package io.github.kr9ly.daybook.io

import java.io.File

/** JVM テストが java.io.File のまま common API（FilePath 受け）を呼ぶための変換。 */
internal fun File.toFilePath(): FilePath = FilePath(path)
