package io.github.kr9ly.daybook.kv

import io.github.kr9ly.daybook.io.FilePath
import io.github.kr9ly.daybook.io.mkdirs
import io.github.kr9ly.daybook.io.writeFileBytes
import io.github.kr9ly.daybook.journal.Crc32

/**
 * daybook 1.x のジャーナルフォーマット（version 1）をバイト列で組み立てるテスト用ライタ。
 *
 * 2.x のエンジン（JournalFile / KvOperationCodec）は version 2 しか書けないため、
 * 1.x マイグレーションのテスト入力は 1.x リリース時点の形式をここで再現して作る。
 */
internal class V1JournalWriter {

    private val out = mutableListOf<Byte>()

    init {
        raw(byteArrayOf(0x44, 0x42, 0x4B, 0x4A)) // "DBKJ"
        raw(int(1)) // version 1
    }

    fun record(payload: ByteArray): V1JournalWriter {
        val framed = int(payload.size) + payload
        val crc = Crc32()
        crc.update(framed, 0, framed.size)
        raw(framed)
        raw(int(crc.value.toInt()))
        return this
    }

    /** レコード枠を通さずに生バイトを追記する（壊れたテールの再現用）。 */
    fun raw(bytes: ByteArray): V1JournalWriter {
        bytes.forEach { out.add(it) }
        return this
    }

    fun writeTo(file: FilePath): FilePath {
        val parent = file.path.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) mkdirs(FilePath(parent))
        writeFileBytes(file, out.toByteArray())
        return file
    }

    companion object {

        fun put(key: String, value: Any): ByteArray = build {
            write(1) // OP_PUT
            string(key)
            value(value)
        }

        fun remove(key: String): ByteArray = build {
            write(2) // OP_REMOVE
            string(key)
        }

        fun clear(): ByteArray = byteArrayOf(3) // OP_CLEAR

        fun snapshotBoundary(): ByteArray = byteArrayOf(4) // OP_SNAPSHOT_BOUNDARY

        fun batch(vararg singles: ByteArray): ByteArray = build {
            write(5) // OP_BATCH
            int(singles.size)
            singles.forEach { raw(it) }
        }

        fun build(body: PayloadBuilder.() -> Unit): ByteArray =
            PayloadBuilder().apply(body).toByteArray()

        /** ビッグエンディアン 4 バイトの int 表現。 */
        private fun int(value: Int): ByteArray = byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )
    }

    internal class PayloadBuilder {
        private val out = mutableListOf<Byte>()

        fun write(byte: Int) = apply { out.add(byte.toByte()) }

        fun raw(bytes: ByteArray) = apply { bytes.forEach { out.add(it) } }

        fun int(value: Int) = apply { raw(Companion.int(value)) }

        fun string(value: String) = apply {
            val bytes = value.encodeToByteArray()
            int(bytes.size)
            raw(bytes)
        }

        fun value(value: Any) = apply {
            when (value) {
                is String -> {
                    write(1)
                    string(value)
                }

                is Int -> {
                    write(2)
                    int(value)
                }

                is Long -> {
                    write(3)
                    int((value ushr 32).toInt())
                    int(value.toInt())
                }

                is Float -> {
                    write(4)
                    int(value.toRawBits())
                }

                is Boolean -> {
                    write(5)
                    write(if (value) 1 else 0)
                }

                is Set<*> -> {
                    write(6)
                    int(value.size)
                    value.forEach { string(it as String) }
                }

                else -> throw IllegalArgumentException("v1 にない値型: ${value::class}")
            }
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
