package io.github.kr9ly.daybook.kv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class KvOperationCodecTest {

    private fun roundTrip(op: KvOperation): KvOperation =
        KvOperationCodec.decode(KvOperationCodec.encode(op))

    private fun assertRoundTrip(op: KvOperation) {
        assertEquals(op, roundTrip(op))
    }

    // --- 全型の往復 ---

    @Test
    fun putString_roundTrip() {
        assertRoundTrip(KvOperation.Put("key", "value"))
        assertRoundTrip(KvOperation.Put("key", ""))
        assertRoundTrip(KvOperation.Put("key", "日本語と絵文字🎌とサロゲートペア𠮷"))
    }

    @Test
    fun putInt_roundTrip() {
        assertRoundTrip(KvOperation.Put("key", 0))
        assertRoundTrip(KvOperation.Put("key", -1))
        assertRoundTrip(KvOperation.Put("key", Int.MIN_VALUE))
        assertRoundTrip(KvOperation.Put("key", Int.MAX_VALUE))
    }

    @Test
    fun putLong_roundTrip() {
        assertRoundTrip(KvOperation.Put("key", 0L))
        assertRoundTrip(KvOperation.Put("key", -1L))
        assertRoundTrip(KvOperation.Put("key", Long.MIN_VALUE))
        assertRoundTrip(KvOperation.Put("key", Long.MAX_VALUE))
        // 上位・下位 32bit の合成を検証する非対称な値
        assertRoundTrip(KvOperation.Put("key", 0x12345678_9ABCDEF0L))
        assertRoundTrip(KvOperation.Put("key", -0x12345678_9ABCDEF0L))
    }

    @Test
    fun putFloat_roundTrip() {
        assertRoundTrip(KvOperation.Put("key", 0.0f))
        assertRoundTrip(KvOperation.Put("key", -1.5f))
        assertRoundTrip(KvOperation.Put("key", Float.NEGATIVE_INFINITY))
        assertRoundTrip(KvOperation.Put("key", Float.MIN_VALUE))
        // NaN は equals で比較できないため raw bits で検証（-0.0f も bits まで保存されることを含む）
        val nan = roundTrip(KvOperation.Put("key", Float.NaN)) as KvOperation.Put
        assertEquals(Float.NaN.toRawBits(), (nan.value as Float).toRawBits())
        val negativeZero = roundTrip(KvOperation.Put("key", -0.0f)) as KvOperation.Put
        assertEquals((-0.0f).toRawBits(), (negativeZero.value as Float).toRawBits())
    }

    @Test
    fun putBoolean_roundTrip() {
        assertRoundTrip(KvOperation.Put("key", true))
        assertRoundTrip(KvOperation.Put("key", false))
    }

    @Test
    fun putStringSet_roundTrip() {
        assertRoundTrip(KvOperation.Put("key", emptySet<String>()))
        assertRoundTrip(KvOperation.Put("key", setOf("a")))
        assertRoundTrip(KvOperation.Put("key", setOf("a", "b", "", "日本語")))
    }

    @Test
    fun remove_roundTrip() {
        assertRoundTrip(KvOperation.Remove("key"))
        assertRoundTrip(KvOperation.Remove(""))
    }

    @Test
    fun clear_roundTrip() {
        assertRoundTrip(KvOperation.Clear)
    }

    @Test
    fun keyWithMultibyteCharacters_roundTrip() {
        assertRoundTrip(KvOperation.Put("キー🔑", 1))
        assertRoundTrip(KvOperation.Remove("キー🔑"))
    }

    // --- エンコード側の型検査 ---

    @Test
    fun encode_rejectsUnsupportedValueType() {
        assertThrows(IllegalArgumentException::class.java) {
            KvOperationCodec.encode(KvOperation.Put("key", 1.0)) // Double は非対応
        }
    }

    @Test
    fun encode_rejectsNonStringSetElement() {
        assertThrows(IllegalArgumentException::class.java) {
            KvOperationCodec.encode(KvOperation.Put("key", setOf(1, 2)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            KvOperationCodec.encode(KvOperation.Put("key", setOf("a", null)))
        }
    }

    // --- decode 側の形式検査 ---

    private fun assertDecodeFails(payload: ByteArray) {
        assertThrows(KvEncodingException::class.java) {
            KvOperationCodec.decode(payload)
        }
    }

    @Test
    fun decode_rejectsEmptyPayload() {
        assertDecodeFails(byteArrayOf())
    }

    @Test
    fun decode_rejectsUnknownOpTag() {
        assertDecodeFails(byteArrayOf(99))
    }

    @Test
    fun decode_rejectsUnknownValueTypeTag() {
        val valid = KvOperationCodec.encode(KvOperation.Put("k", 1))
        // [op=1][len=4B "k"1B] の直後が type タグ
        val corrupted = valid.copyOf()
        corrupted[1 + 4 + 1] = 99
        assertDecodeFails(corrupted)
    }

    @Test
    fun decode_rejectsTruncatedPayload() {
        val valid = KvOperationCodec.encode(KvOperation.Put("key", "value"))
        // 全プレフィックスで途中切断を試す（境界の取りこぼし防止）
        for (length in 0 until valid.size) {
            assertDecodeFails(valid.copyOf(length))
        }
    }

    @Test
    fun decode_rejectsTrailingGarbage() {
        val valid = KvOperationCodec.encode(KvOperation.Remove("key"))
        assertDecodeFails(valid + byteArrayOf(0))
    }

    @Test
    fun decode_rejectsNegativeStringLength() {
        // [op=2][length=-1]
        assertDecodeFails(byteArrayOf(2, -1, -1, -1, -1))
    }

    @Test
    fun decode_rejectsNegativeSetCount() {
        val valid = KvOperationCodec.encode(KvOperation.Put("k", setOf("a")))
        // [op=1][key len 4B]["k" 1B][type 1B] の直後が count 4B
        val corrupted = valid.copyOf()
        corrupted[1 + 4 + 1 + 1] = -1
        corrupted[1 + 4 + 1 + 2] = -1
        corrupted[1 + 4 + 1 + 3] = -1
        corrupted[1 + 4 + 1 + 4] = -1
        assertDecodeFails(corrupted)
    }

    @Test
    fun decode_rejectsInvalidBooleanByte() {
        val valid = KvOperationCodec.encode(KvOperation.Put("k", true))
        val corrupted = valid.copyOf()
        corrupted[corrupted.size - 1] = 2
        assertDecodeFails(corrupted)
    }
}
