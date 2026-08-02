package io.github.kr9ly.daybook.journal

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException

// このファイルは JVM ユニットテストで実行できないため Kover の対象外
// （android.system.Os は Android ランタイム専用）。実機挙動は Instrumentation テストで検証する。

/** Android 用。android.system.Os（API 21+）経由でディレクトリ fd を fsync する。 */
internal class OsDirectorySync : DirectorySync {
    override fun sync(directory: File) {
        try {
            val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
        } catch (e: ErrnoException) {
            // ErrnoException は IOException 系でないため、契約（IO 失敗 = IOException）に正規化する
            throw IOException("failed to sync directory: $directory", e)
        }
    }
}

/** 実行環境に応じた [DirectorySync]。Android ランタイムでは Os、JVM では nio。 */
internal fun defaultDirectorySync(): DirectorySync =
    if (System.getProperty("java.vm.name") == "Dalvik") OsDirectorySync() else NioDirectorySync()
