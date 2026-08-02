package io.github.kr9ly.daybook.journal

import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * ジャーナルの書き込み先。
 *
 * ファイル IO を直接触らずこのインターフェースを経由することで、
 * テストから「fsync 前にクラッシュ」「追記途中の任意バイト位置でクラッシュ」を
 * 決定的に注入できる。
 */
internal interface JournalSink : Closeable {
    /** バイト列を現在位置に書き込む。 */
    fun write(data: ByteArray)

    /** これまでの書き込みを永続ストレージに同期する（fsync）。 */
    fun force()

    /** ファイルを指定サイズに切り詰め、書き込み位置をその末尾に移す。 */
    fun truncate(size: Long)
}

/**
 * 実ファイルへの書き込み。
 *
 * O_APPEND で開くため、書き込みは常にその時点の実ファイル末尾に落ちる。
 * 他プロセスが追記した後でも自ハンドルの位置ずれによる上書きが構造的に起こらない
 * （マルチプロセスの追記は排他ロック下で直列化されるが、その上の安全網）。
 */
internal class FileSink(file: File) : JournalSink {
    private val channel = FileChannel.open(
        file.toPath(),
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
        StandardOpenOption.APPEND,
    )

    override fun write(data: ByteArray) {
        val buf = ByteBuffer.wrap(data)
        while (buf.hasRemaining()) {
            channel.write(buf)
        }
    }

    override fun force() {
        channel.force(true)
    }

    override fun truncate(size: Long) {
        channel.truncate(size)
    }

    override fun close() {
        channel.close()
    }
}
