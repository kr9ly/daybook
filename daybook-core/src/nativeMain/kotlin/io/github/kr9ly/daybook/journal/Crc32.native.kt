package io.github.kr9ly.daybook.journal

/**
 * テーブル駆動の CRC-32（reflected、多項式 0xEDB88320）。
 *
 * java.util.zip.CRC32（ISO 3309）と同一のパラメータで、JVM で書いたジャーナルを
 * Native で読んでも（逆も）チェックサムが一致する。
 */
internal actual class Crc32 {

    private var crc: UInt = 0xFFFF_FFFFu

    actual fun update(bytes: ByteArray, offset: Int, length: Int) {
        var c = crc
        for (i in offset until offset + length) {
            c = TABLE[((c xor bytes[i].toUInt()) and 0xFFu).toInt()] xor (c shr 8)
        }
        crc = c
    }

    actual val value: Long
        get() = (crc xor 0xFFFF_FFFFu).toLong() and 0xFFFF_FFFFL

    private companion object {
        private val TABLE = UIntArray(256) { n ->
            var c = n.toUInt()
            repeat(8) {
                c = if (c and 1u != 0u) 0xEDB8_8320u xor (c shr 1) else c shr 1
            }
            c
        }
    }
}
