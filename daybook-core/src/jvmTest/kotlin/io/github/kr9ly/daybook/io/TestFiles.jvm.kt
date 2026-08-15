package io.github.kr9ly.daybook.io

import java.io.File

internal actual fun writeFileBytes(path: FilePath, bytes: ByteArray) {
    File(path.path).writeBytes(bytes)
}

internal actual fun setDirectoryWritable(path: FilePath, writable: Boolean) {
    File(path.path).setWritable(writable)
}
