package io.github.kr9ly.daybook.kv

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * daybook 1.x のジャーナルフォーマット（version 1）をバイト列で組み立てるテスト用ライタ。
 *
 * 2.x のエンジン（JournalFile / KvOperationCodec）は version 2 しか書けないため、
 * 1.x マイグレーションのテスト入力は 1.x リリース時点の形式をここで再現して作る。
 */
internal class V1JournalWriter {

    private val out = ByteArrayOutputStream()

    init {
        out.write(byteArrayOf(0x44, 0x42, 0x4B, 0x4A)) // "DBKJ"
        out.write(int(1)) // version 1
    }

    fun record(payload: ByteArray): V1JournalWriter {
        val framed = ByteBuffer.allocate(4 + payload.size)
        framed.putInt(payload.size)
        framed.put(payload)
        val crc = CRC32()
        crc.update(framed.array())
        out.write(framed.array())
        out.write(int(crc.value.toInt()))
        return this
    }

    /** レコード枠を通さずに生バイトを追記する（壊れたテールの再現用）。 */
    fun raw(bytes: ByteArray): V1JournalWriter {
        out.write(bytes)
        return this
    }

    fun writeTo(file: File): File {
        file.parentFile.mkdirs()
        file.writeBytes(out.toByteArray())
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

        private fun int(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()
    }

    internal class PayloadBuilder {
        private val out = ByteArrayOutputStream()

        fun write(byte: Int) = apply { out.write(byte) }

        fun raw(bytes: ByteArray) = apply { out.write(bytes) }

        fun int(value: Int) = apply { out.write(ByteBuffer.allocate(4).putInt(value).array()) }

        fun string(value: String) = apply {
            val bytes = value.encodeToByteArray()
            int(bytes.size)
            out.write(bytes)
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
                    out.write(ByteBuffer.allocate(8).putLong(value).array())
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
