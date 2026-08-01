package io.github.kr9ly.daybook.journal

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.CRC32

/** 追記ごとの fsync ポリシー。 */
internal enum class SyncMode {
    /** 追記ごとに fsync する。遅いが電源断まで耐える。 */
    SYNC,

    /** OS のページキャッシュに任せる。プロセスクラッシュには耐える。 */
    ASYNC,
}

/**
 * daybook のジャーナルではないファイルを開こうとした。
 *
 * 破損テール（切り捨てで復旧できる）とは違い、magic 不一致・未知バージョンは
 * 黙って上書きするとユーザーデータを壊しうるため、例外として呼び出し側に返す。
 */
internal class JournalFormatException(message: String) : IOException(message)

/**
 * 追記専用のバイトレコードログ。
 *
 * KV のセマンティクスを知らない。レコードは不透明な payload であり、
 * KV 操作へのエンコードは上位層が行う。
 *
 * ファイル形式:
 * ```
 * [magic 4B][version 4B]                          -- ヘッダ
 * [length 4B][payload][crc32 4B]                  -- レコード（繰り返し）
 * ```
 *
 * crc32 は length + payload に掛ける（長さフィールドの破損も検出する）。
 * 整数はすべてビッグエンディアン。
 *
 * オープン時に全レコードをリプレイし、CRC 不一致・長さ不整合を見つけたら
 * そのレコード以降（壊れたテール）を切り捨てて最後の正常状態に復旧する。
 */
internal class JournalFile private constructor(
    private val sink: JournalSink,
    private val syncMode: SyncMode,
    /** オープン時のリプレイで得た正常レコード列。 */
    val replayedRecords: List<ByteArray>,
    /** オープン時に壊れたテールを切り捨てて復旧したか。 */
    val recoveredFromCorruption: Boolean,
) : Closeable {

    /** レコードを末尾に追記する。SYNC モードでは追記ごとに fsync する。 */
    fun append(payload: ByteArray) {
        require(payload.size <= MAX_PAYLOAD_SIZE) {
            "payload too large: ${payload.size} bytes (max $MAX_PAYLOAD_SIZE)"
        }
        val buf = ByteBuffer.allocate(LENGTH_SIZE + payload.size + CRC_SIZE)
        buf.putInt(payload.size)
        buf.put(payload)
        val crc = CRC32()
        crc.update(buf.array(), 0, LENGTH_SIZE + payload.size)
        buf.putInt(crc.value.toInt())
        sink.write(buf.array())
        if (syncMode == SyncMode.SYNC) {
            sink.force()
        }
    }

    override fun close() {
        sink.close()
    }

    companion object {
        private val MAGIC = byteArrayOf(0x44, 0x42, 0x4B, 0x4A) // "DBKJ"
        private const val VERSION = 1
        private const val HEADER_SIZE = 8
        private const val LENGTH_SIZE = 4
        private const val CRC_SIZE = 4

        /**
         * length フィールドの健全性チェック用の上限。
         * 想定ユースケース（設定値・小さな状態）を大きく超える値は破損とみなす。
         */
        const val MAX_PAYLOAD_SIZE = 16 * 1024 * 1024

        /**
         * ジャーナルを開き、既存レコードをリプレイして追記可能な状態で返す。
         *
         * 壊れたテールはこの時点でファイルからも切り捨てられる。
         * daybook のジャーナルではないファイル（magic 不一致・未知バージョン）は
         * [JournalFormatException]。
         *
         * [sinkFactory] はテストのクラッシュ注入用フック。
         */
        fun open(
            file: File,
            syncMode: SyncMode = SyncMode.ASYNC,
            sinkFactory: (File) -> JournalSink = ::FileSink,
        ): JournalFile {
            val bytes = if (file.exists()) file.readBytes() else ByteArray(0)
            val replay = parse(bytes)
            val sink = sinkFactory(file)
            try {
                if (replay.validLength == 0L) {
                    // 新規 or ヘッダ書き込み途中のクラッシュ。ヘッダから書き直す
                    sink.truncate(0)
                    val header = ByteBuffer.allocate(HEADER_SIZE)
                    header.put(MAGIC)
                    header.putInt(VERSION)
                    sink.write(header.array())
                    if (syncMode == SyncMode.SYNC) {
                        sink.force()
                    }
                } else {
                    // 壊れたテールの切り捨て（正常なら末尾への seek と等価）
                    sink.truncate(replay.validLength)
                }
            } catch (e: Throwable) {
                sink.close()
                throw e
            }
            return JournalFile(
                sink = sink,
                syncMode = syncMode,
                replayedRecords = replay.records,
                recoveredFromCorruption = replay.truncated,
            )
        }

        private class ParseResult(
            val records: List<ByteArray>,
            val validLength: Long,
            val truncated: Boolean,
        )

        private fun parse(bytes: ByteArray): ParseResult {
            if (bytes.size >= MAGIC.size) {
                for (i in MAGIC.indices) {
                    if (bytes[i] != MAGIC[i]) {
                        throw JournalFormatException("not a daybook journal (bad magic)")
                    }
                }
            }
            if (bytes.size < HEADER_SIZE) {
                // 空 or ヘッダ書き込み途中のクラッシュ
                return ParseResult(emptyList(), 0L, truncated = bytes.isNotEmpty())
            }
            val version = readInt(bytes, MAGIC.size)
            if (version != VERSION) {
                throw JournalFormatException("unsupported journal version: $version")
            }

            val records = mutableListOf<ByteArray>()
            var offset = HEADER_SIZE
            while (true) {
                if (offset + LENGTH_SIZE > bytes.size) break
                val length = readInt(bytes, offset)
                if (length < 0 || length > MAX_PAYLOAD_SIZE) break
                val end = offset + LENGTH_SIZE + length + CRC_SIZE
                if (end > bytes.size) break
                val crc = CRC32()
                crc.update(bytes, offset, LENGTH_SIZE + length)
                if (crc.value.toInt() != readInt(bytes, offset + LENGTH_SIZE + length)) break
                records.add(bytes.copyOfRange(offset + LENGTH_SIZE, offset + LENGTH_SIZE + length))
                offset = end
            }
            return ParseResult(records, offset.toLong(), truncated = offset < bytes.size)
        }

        private fun readInt(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
    }
}
