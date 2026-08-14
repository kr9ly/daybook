package io.github.kr9ly.daybook.journal

/**
 * CRC-32（ISO 3309、java.util.zip.CRC32 と同一多項式）の計算器。
 *
 * ジャーナルレコードの完全性検査用。JVM actual は java.util.zip.CRC32 を包む。
 */
internal expect class Crc32() {

    fun update(bytes: ByteArray, offset: Int, length: Int)

    /** これまでに update した範囲の CRC-32 値（下位 32bit が有効）。 */
    val value: Long
}
