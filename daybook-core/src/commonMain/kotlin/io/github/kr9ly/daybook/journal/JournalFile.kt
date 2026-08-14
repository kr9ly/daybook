package io.github.kr9ly.daybook.journal

import io.github.kr9ly.daybook.internal.DaybookInternalApi
import io.github.kr9ly.daybook.io.FilePath
import io.github.kr9ly.daybook.io.IoException
import io.github.kr9ly.daybook.io.PositionalFileReader
import io.github.kr9ly.daybook.io.readFileOrEmpty

/** 追記ごとの fsync ポリシー。 */
@DaybookInternalApi
public enum class SyncMode {
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
internal class JournalFormatException(message: String) : IoException(message)

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
    /**
     * 差分リード用の読み取りハンドル。パスではなくオープン済みハンドルを持ち続けることで、
     * compaction の rename（inode は変わらない）の後も同じファイルを読み続けられる。
     */
    private val reader: PositionalFileReader,
    private val sink: JournalSink,
    private val syncMode: SyncMode,
    /** オープン時のリプレイで得た正常レコード列。 */
    override val replayedRecords: List<ByteArray>,
    /** オープン時に壊れたテールを切り捨てて復旧したか。 */
    val recoveredFromCorruption: Boolean,
    initialLength: Long,
) : Journal {

    /** 現在のファイルサイズ（ヘッダ + 正常レコード列）。compaction の閾値判定用。 */
    private var currentLength: Long = initialLength

    override val length: Long get() = currentLength

    /** レコードを末尾に追記する。SYNC モードでは追記ごとに fsync する。 */
    override fun append(payload: ByteArray) {
        require(payload.size <= MAX_PAYLOAD_SIZE) {
            "payload too large: ${payload.size} bytes (max $MAX_PAYLOAD_SIZE)"
        }
        val record = ByteArray(LENGTH_SIZE + payload.size + CRC_SIZE)
        writeInt(record, 0, payload.size)
        payload.copyInto(record, LENGTH_SIZE)
        val crc = Crc32()
        crc.update(record, 0, LENGTH_SIZE + payload.size)
        writeInt(record, LENGTH_SIZE + payload.size, crc.value.toInt())
        sink.write(record)
        currentLength += record.size
        if (syncMode == SyncMode.SYNC) {
            sink.force()
        }
    }

    /** これまでの追記を永続ストレージに同期する（fsync）。 */
    override fun force() {
        sink.force()
    }

    /**
     * [length] 以降に増えた完全なレコードを読み、[length] をその境界まで進めて返す。
     * 他プロセスの追記分をリプレイする差分リード。自ハンドルの追記は [length] を
     * 進めるため、ここには現れない。
     *
     * 末尾の不完全なレコードは破損ではなく他プロセスの書き込み途中とみなし、
     * 切り捨てず読み残す（次回の呼び出しで完成していれば読める）。
     * テールの切り捨てはオープン時の復旧だけの権利。
     */
    override fun readNewRecords(): List<ByteArray> {
        val end = reader.length()
        if (end <= currentLength) return emptyList()
        val tail = reader.readFully(currentLength, (end - currentLength).toInt())
        val result = scanRecords(tail, 0)
        currentLength += result.validLength
        return result.records
    }

    override fun close() {
        reader.close()
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
            file: FilePath,
            syncMode: SyncMode = SyncMode.ASYNC,
            sinkFactory: (FilePath) -> JournalSink = ::FileSink,
        ): JournalFile {
            val bytes = readFileOrEmpty(file)
            val replay = parse(bytes)
            val sink = sinkFactory(file)
            try {
                if (replay.validLength == 0L) {
                    // 新規 or ヘッダ書き込み途中のクラッシュ。ヘッダから書き直す
                    sink.truncate(0)
                    val header = ByteArray(HEADER_SIZE)
                    MAGIC.copyInto(header)
                    writeInt(header, MAGIC.size, VERSION)
                    sink.write(header)
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
            val reader = try {
                PositionalFileReader(file)
            } catch (e: Throwable) {
                sink.close()
                throw e
            }
            return JournalFile(
                reader = reader,
                sink = sink,
                syncMode = syncMode,
                replayedRecords = replay.records,
                recoveredFromCorruption = replay.truncated,
                initialLength = if (replay.validLength == 0L) HEADER_SIZE.toLong() else replay.validLength,
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

            val result = scanRecords(bytes, HEADER_SIZE)
            return ParseResult(
                result.records,
                HEADER_SIZE + result.validLength,
                truncated = HEADER_SIZE + result.validLength < bytes.size,
            )
        }

        private class ScanResult(
            val records: List<ByteArray>,
            /** [records] が占めるバイト数（走査開始位置からの相対）。 */
            val validLength: Long,
        )

        /** [from] から完全なレコードを走査する。不完全・不正なレコードで停止する。 */
        private fun scanRecords(bytes: ByteArray, from: Int): ScanResult {
            val records = mutableListOf<ByteArray>()
            var offset = from
            while (true) {
                if (offset + LENGTH_SIZE > bytes.size) break
                val length = readInt(bytes, offset)
                if (length < 0 || length > MAX_PAYLOAD_SIZE) break
                val end = offset + LENGTH_SIZE + length + CRC_SIZE
                if (end > bytes.size) break
                val crc = Crc32()
                crc.update(bytes, offset, LENGTH_SIZE + length)
                if (crc.value.toInt() != readInt(bytes, offset + LENGTH_SIZE + length)) break
                records.add(bytes.copyOfRange(offset + LENGTH_SIZE, offset + LENGTH_SIZE + length))
                offset = end
            }
            return ScanResult(records, (offset - from).toLong())
        }

        private fun readInt(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)

        private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = (value ushr 24).toByte()
            bytes[offset + 1] = (value ushr 16).toByte()
            bytes[offset + 2] = (value ushr 8).toByte()
            bytes[offset + 3] = value.toByte()
        }
    }
}
