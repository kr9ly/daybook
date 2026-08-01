package io.github.kr9ly.daybook.journal

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

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

/** 実ファイルへの書き込み。 */
internal class FileSink(file: File) : JournalSink {
    private val raf = RandomAccessFile(file, "rw")

    init {
        raf.seek(raf.length())
    }

    override fun write(data: ByteArray) {
        raf.write(data)
    }

    override fun force() {
        raf.fd.sync()
    }

    override fun truncate(size: Long) {
        raf.setLength(size)
        raf.seek(size)
    }

    override fun close() {
        raf.close()
    }
}
