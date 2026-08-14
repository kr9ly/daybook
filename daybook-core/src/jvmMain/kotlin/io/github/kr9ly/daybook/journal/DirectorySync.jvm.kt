package io.github.kr9ly.daybook.journal

import io.github.kr9ly.daybook.io.FilePath
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** JVM（ユニットテスト・デスクトップ）用。java.nio.file 経由でディレクトリ fd を fsync する。 */
internal class NioDirectorySync : DirectorySync {
    override fun sync(directory: FilePath) {
        FileChannel.open(File(directory.path).toPath(), StandardOpenOption.READ).use { channel ->
            channel.force(true)
        }
    }
}

public actual fun platformDirectorySync(): DirectorySync = NioDirectorySync()
