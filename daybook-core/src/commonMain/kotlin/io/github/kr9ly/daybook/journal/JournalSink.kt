package io.github.kr9ly.daybook.journal

import io.github.kr9ly.daybook.internal.DaybookInternalApi
import io.github.kr9ly.daybook.io.FilePath

/**
 * ジャーナルの書き込み先。
 *
 * ファイル IO を直接触らずこのインターフェースを経由することで、
 * テストから「fsync 前にクラッシュ」「追記途中の任意バイト位置でクラッシュ」を
 * 決定的に注入できる。
 */
@DaybookInternalApi
public interface JournalSink : AutoCloseable {
    /** バイト列を現在位置に書き込む。 */
    public fun write(data: ByteArray)

    /** これまでの書き込みを永続ストレージに同期する（fsync）。 */
    public fun force()

    /** ファイルを指定サイズに切り詰め、書き込み位置をその末尾に移す。 */
    public fun truncate(size: Long)
}

/**
 * 実ファイルへの書き込み。
 *
 * 追記モード（O_APPEND 相当）で開くため、書き込みは常にその時点の実ファイル末尾に落ちる。
 * 他プロセスが追記した後でも自ハンドルの位置ずれによる上書きが構造的に起こらない
 * （マルチプロセスの追記は排他ロック下で直列化されるが、その上の安全網）。
 */
@DaybookInternalApi
public expect class FileSink(path: FilePath) : JournalSink {
    override fun write(data: ByteArray)
    override fun force()
    override fun truncate(size: Long)
    override fun close()
}
