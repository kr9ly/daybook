package io.github.kr9ly.daybook.kv

import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * ジャーナルレコードの payload として読めない、または KV 操作として不正なバイト列。
 *
 * ジャーナル層の CRC が payload の完全性を保証するため、ここでの decode 失敗は
 * ビット破損ではなくエンコードの不整合（未知の将来フォーマット等）を意味する。
 * 読み飛ばしで「復旧」せず例外として呼び出し側に返す。
 */
internal class KvEncodingException(message: String) : IOException(message)

/**
 * [KvOperation] とジャーナルレコード payload（バイト列）の相互変換。
 *
 * payload 形式:
 * ```
 * PUT:               [op=1][key][type 1B][value]
 * REMOVE:            [op=2][key]
 * CLEAR:             [op=3]
 * SNAPSHOT_BOUNDARY: [op=4]
 * ```
 *
 * 文字列は `[length 4B][UTF-8 bytes]`、`Set<String>` は `[count 4B][文字列...]`。
 * `Int`/`Long` はそのままのビット幅、`Float` は raw bits 4B、`Boolean` は 1B(0/1)。
 * 整数はすべてビッグエンディアン（ジャーナル層と同じ）。
 */
internal object KvOperationCodec {

    private const val OP_PUT = 1
    private const val OP_REMOVE = 2
    private const val OP_CLEAR = 3
    private const val OP_SNAPSHOT_BOUNDARY = 4

    private const val TYPE_STRING = 1
    private const val TYPE_INT = 2
    private const val TYPE_LONG = 3
    private const val TYPE_FLOAT = 4
    private const val TYPE_BOOLEAN = 5
    private const val TYPE_STRING_SET = 6

    /**
     * 操作を payload に変換する。
     *
     * [KvOperation.Put] の値が対応 6 種以外のときは [IllegalArgumentException]
     * （壊れた入力ではなく呼び出し側のバグのため）。
     */
    fun encode(op: KvOperation): ByteArray {
        val out = ByteArrayOutputStream()
        when (op) {
            is KvOperation.Put -> {
                out.write(OP_PUT)
                writeString(out, op.key)
                writeValue(out, op.value)
            }
            is KvOperation.Remove -> {
                out.write(OP_REMOVE)
                writeString(out, op.key)
            }
            KvOperation.Clear -> out.write(OP_CLEAR)
            KvOperation.SnapshotBoundary -> out.write(OP_SNAPSHOT_BOUNDARY)
        }
        return out.toByteArray()
    }

    /** payload を操作に復元する。読めないバイト列は [KvEncodingException]。 */
    fun decode(payload: ByteArray): KvOperation {
        val reader = Reader(payload)
        val op = when (val tag = reader.readByte()) {
            OP_PUT -> {
                val key = reader.readString()
                KvOperation.Put(key, readValue(reader))
            }
            OP_REMOVE -> KvOperation.Remove(reader.readString())
            OP_CLEAR -> KvOperation.Clear
            OP_SNAPSHOT_BOUNDARY -> KvOperation.SnapshotBoundary
            else -> throw KvEncodingException("unknown op tag: $tag")
        }
        reader.requireConsumed()
        return op
    }

    private fun writeValue(out: ByteArrayOutputStream, value: Any) {
        when (value) {
            is String -> {
                out.write(TYPE_STRING)
                writeString(out, value)
            }
            is Int -> {
                out.write(TYPE_INT)
                writeInt(out, value)
            }
            is Long -> {
                out.write(TYPE_LONG)
                writeInt(out, (value ushr 32).toInt())
                writeInt(out, value.toInt())
            }
            is Float -> {
                out.write(TYPE_FLOAT)
                writeInt(out, value.toRawBits())
            }
            is Boolean -> {
                out.write(TYPE_BOOLEAN)
                out.write(if (value) 1 else 0)
            }
            is Set<*> -> {
                out.write(TYPE_STRING_SET)
                writeInt(out, value.size)
                value.forEach { element ->
                    require(element is String) {
                        "unsupported Set element: ${element?.let { it::class.java.name }} (only Set<String>)"
                    }
                    writeString(out, element)
                }
            }
            else -> throw IllegalArgumentException(
                "unsupported value type: ${value::class.java.name} " +
                    "(String/Int/Long/Float/Boolean/Set<String> only)",
            )
        }
    }

    private fun readValue(reader: Reader): Any = when (val tag = reader.readByte()) {
        TYPE_STRING -> reader.readString()
        TYPE_INT -> reader.readInt()
        TYPE_LONG -> (reader.readInt().toLong() shl 32) or (reader.readInt().toLong() and 0xFFFFFFFFL)
        TYPE_FLOAT -> Float.fromBits(reader.readInt())
        TYPE_BOOLEAN -> when (val b = reader.readByte()) {
            0 -> false
            1 -> true
            else -> throw KvEncodingException("invalid boolean value: $b")
        }
        TYPE_STRING_SET -> {
            val count = reader.readInt()
            if (count < 0) throw KvEncodingException("negative set count: $count")
            buildSet {
                repeat(count) { add(reader.readString()) }
            }
        }
        else -> throw KvEncodingException("unknown value type tag: $tag")
    }

    private fun writeString(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(out, bytes.size)
        out.write(bytes, 0, bytes.size)
    }

    private fun writeInt(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    /** payload 上の読み取りカーソル。末尾を越える読み取りは [KvEncodingException]。 */
    private class Reader(private val bytes: ByteArray) {
        private var offset = 0

        fun readByte(): Int {
            ensureRemaining(1)
            return bytes[offset++].toInt() and 0xFF
        }

        fun readInt(): Int {
            ensureRemaining(4)
            val value = ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
            offset += 4
            return value
        }

        fun readString(): String {
            val length = readInt()
            if (length < 0) throw KvEncodingException("negative string length: $length")
            ensureRemaining(length)
            val value = String(bytes, offset, length, Charsets.UTF_8)
            offset += length
            return value
        }

        /** 操作の decode 後に余りバイトがないことを検証する（形式不整合の検出）。 */
        fun requireConsumed() {
            if (offset != bytes.size) {
                throw KvEncodingException("trailing garbage: ${bytes.size - offset} bytes after operation")
            }
        }

        private fun ensureRemaining(count: Int) {
            if (offset + count > bytes.size) {
                throw KvEncodingException("truncated payload: need $count bytes at offset $offset, size ${bytes.size}")
            }
        }
    }
}
