package io.github.kr9ly.daybook.journal

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * ディレクトリエントリ（ファイルの作成・rename）の永続化。
 *
 * ファイル内容の fsync はファイル名の永続化を含まない。電源断をまたいで
 * 「作成したファイルが名前ごと消える」「rename が巻き戻る」を防ぐには、
 * 親ディレクトリ自体の fsync が必要になる。SYNC モードの耐久性契約はこれで完成する
 * （ASYNC モードは fsync の発行順序による安全性のみで、ディレクトリ fsync は使わない）。
 *
 * 実装が環境で分かれるのは機能差ではなく API の存在差:
 * java.nio.file は Android では API 26 以降、android.system.Os は Android 専用で
 * JVM ユニットテストから呼べない。どちらも同じ fsync(2) を発行する。
 */
internal fun interface DirectorySync {
    /** ディレクトリの内容（エントリ）を永続ストレージに同期する。 */
    fun sync(directory: File)
}

/** JVM（ユニットテスト・デスクトップ）用。java.nio.file 経由でディレクトリ fd を fsync する。 */
internal class NioDirectorySync : DirectorySync {
    override fun sync(directory: File) {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel ->
            channel.force(true)
        }
    }
}
