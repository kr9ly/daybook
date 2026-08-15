package io.github.kr9ly.daybook.journal

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Crc32 actual の相互一致テスト。
 *
 * CRC-32（ISO 3309）の標準チェック値に対して全プラットフォームの actual が同じ値を
 * 返すことを確認する。JVM で書いたジャーナルを Native で読む（逆も）互換性の土台。
 */
class Crc32Test {

    @Test
    fun standardCheckValue() {
        val crc = Crc32()
        val bytes = "123456789".encodeToByteArray()
        crc.update(bytes, 0, bytes.size)
        assertEquals(0xCBF43926L, crc.value)
    }

    @Test
    fun emptyInput_isZero() {
        assertEquals(0L, Crc32().value)
    }

    @Test
    fun incrementalUpdate_matchesOneShot() {
        val bytes = ByteArray(256) { it.toByte() }

        val oneShot = Crc32()
        oneShot.update(bytes, 0, bytes.size)

        val incremental = Crc32()
        incremental.update(bytes, 0, 100)
        incremental.update(bytes, 100, 156)

        assertEquals(oneShot.value, incremental.value)
    }

    @Test
    fun offsetAndLength_limitTheRange() {
        val padded = ByteArray(3) + "123456789".encodeToByteArray() + ByteArray(5)
        val crc = Crc32()
        crc.update(padded, 3, 9)
        assertEquals(0xCBF43926L, crc.value)
    }
}
