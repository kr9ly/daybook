package io.github.kr9ly.daybook.io

import java.io.File

internal actual fun readFileOrEmpty(path: FilePath): ByteArray {
    val file = File(path.path)
    return if (file.exists()) file.readBytes() else ByteArray(0)
}

internal actual fun mkdirs(path: FilePath) {
    File(path.path).mkdirs()
}

internal actual fun listDirectory(path: FilePath): List<String>? =
    File(path.path).list()?.toList()

internal actual fun deleteFile(path: FilePath) {
    File(path.path).delete()
}

internal actual fun renameFile(from: FilePath, to: FilePath): Boolean =
    File(from.path).renameTo(File(to.path))
