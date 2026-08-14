package io.github.kr9ly.daybook.io

/**
 * 位置指定読み取り専用のファイルハンドル。
 *
 * パスではなくオープン済みハンドル（fd）を持ち続けることで、compaction の rename
 * （inode は変わらない）の後も同じファイルを読み続けられる。JournalFile の差分リード用。
 */
internal expect class PositionalFileReader(path: FilePath) : AutoCloseable {

    /** 現在のファイルサイズ。 */
    fun length(): Long

    /** [offset] から [size] バイトを読み切って返す。 */
    fun readFully(offset: Long, size: Int): ByteArray

    override fun close()
}
