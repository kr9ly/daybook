package io.github.kr9ly.daybook.kv

import io.github.kr9ly.daybook.io.IoException

/**
 * ジャーナルレコードの payload として読めない、または KV 操作として不正なバイト列。
 *
 * ジャーナル層の CRC が payload の完全性を保証するため、ここでの decode 失敗は
 * ビット破損ではなくエンコードの不整合（未知の将来フォーマット等）を意味する。
 * 読み飛ばしで「復旧」せず例外として呼び出し側に返す。
 */
internal class KvEncodingException(message: String) : IoException(message)

/**
 * [KvOperation] とジャーナルレコード payload（バイト列）の相互変換。
 *
 * payload 形式:
 * ```
 * PUT:               [op=1][key][type 1B][value]
 * REMOVE:            [op=2][key]
 * CLEAR:             [op=3]
 * SNAPSHOT_BOUNDARY: [op=4]
 * BATCH:             [op=5][count 4B][単一操作...]
 * ```
 *
 * BATCH の要素は PUT / REMOVE / CLEAR の payload をそのまま並べたもの
 * （各要素は自己区切りなので長さプレフィックスは持たない）。
 * BATCH や SNAPSHOT_BOUNDARY を要素に含むバイト列は不正。
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
    private const val OP_BATCH = 5

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
        val out = ByteArrayBuilder()
        when (op) {
            is KvOperation.Single -> writeSingle(out, op)
            is KvOperation.Batch -> {
                out.write(OP_BATCH)
                writeInt(out, op.operations.size)
                op.operations.forEach { writeSingle(out, it) }
            }
            KvOperation.SnapshotBoundary -> out.write(OP_SNAPSHOT_BOUNDARY)
        }
        return out.toByteArray()
    }

    /** payload を操作に復元する。読めないバイト列は [KvEncodingException]。 */
    fun decode(payload: ByteArray): KvOperation {
        val reader = Reader(payload)
        val op = when (val tag = reader.readByte()) {
            OP_BATCH -> {
                val count = reader.readInt()
                if (count < 0) throw KvEncodingException("negative batch count: $count")
                KvOperation.Batch(List(count) { readSingle(reader) })
            }
            OP_SNAPSHOT_BOUNDARY -> KvOperation.SnapshotBoundary
            else -> readSingleBody(reader, tag)
        }
        reader.requireConsumed()
        return op
    }

    private fun writeSingle(out: ByteArrayBuilder, op: KvOperation.Single) {
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
        }
    }

    private fun readSingle(reader: Reader): KvOperation.Single =
        readSingleBody(reader, reader.readByte())

    /** タグ既読の状態から単一操作の残りを読む。単一操作以外のタグは [KvEncodingException]。 */
    private fun readSingleBody(reader: Reader, tag: Int): KvOperation.Single = when (tag) {
        OP_PUT -> {
            val key = reader.readString()
            KvOperation.Put(key, readValue(reader))
        }
        OP_REMOVE -> KvOperation.Remove(reader.readString())
        OP_CLEAR -> KvOperation.Clear
        else -> throw KvEncodingException("unknown op tag: $tag")
    }

    private fun writeValue(out: ByteArrayBuilder, value: Any) {
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
                        "unsupported Set element: ${element?.let { it::class.qualifiedName }} (only Set<String>)"
                    }
                    writeString(out, element)
                }
            }
            else -> throw IllegalArgumentException(
                "unsupported value type: ${value::class.qualifiedName} " +
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

    private fun writeString(out: ByteArrayBuilder, value: String) {
        val bytes = value.encodeToByteArray()
        writeInt(out, bytes.size)
        out.write(bytes)
    }

    private fun writeInt(out: ByteArrayBuilder, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    /** 追記専用の可変長バイトバッファ（java.io.ByteArrayOutputStream の共通コード代替）。 */
    private class ByteArrayBuilder {
        private var buffer = ByteArray(64)
        private var size = 0

        fun write(byte: Int) {
            ensureCapacity(1)
            buffer[size++] = byte.toByte()
        }

        fun write(bytes: ByteArray) {
            ensureCapacity(bytes.size)
            bytes.copyInto(buffer, size)
            size += bytes.size
        }

        fun toByteArray(): ByteArray = buffer.copyOf(size)

        private fun ensureCapacity(additional: Int) {
            if (size + additional <= buffer.size) return
            var newCapacity = buffer.size * 2
            while (newCapacity < size + additional) {
                newCapacity *= 2
            }
            buffer = buffer.copyOf(newCapacity)
        }
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
            val value = bytes.decodeToString(offset, offset + length)
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
