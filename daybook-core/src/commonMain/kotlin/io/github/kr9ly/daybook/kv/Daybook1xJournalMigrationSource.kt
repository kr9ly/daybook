package io.github.kr9ly.daybook.kv

import io.github.kr9ly.daybook.io.FilePath
import io.github.kr9ly.daybook.io.IoException
import io.github.kr9ly.daybook.io.fileExists
import io.github.kr9ly.daybook.io.readFileOrEmpty
import io.github.kr9ly.daybook.io.renameFile
import io.github.kr9ly.daybook.journal.Crc32
import io.github.kr9ly.daybook.journal.JournalDirectory

/**
 * daybook 1.x のジャーナル（フォーマット version 1）を読む一回きりの取り込み。
 *
 * 1.x フォーマットの読み込みコードはこのファイルに隔離して凍結する（裁定 2026-08-14）:
 * エンジン本体（JournalFile / KvOperationCodec）は 2.x フォーマットだけを知り、
 * ここは 1.x リリース時点の形式 — レコード枠組みは現行と同一、payload の値型は
 * Double を除く 6 種 — を、現行コーデックの将来の変更と独立に読み続ける。
 *
 * 読み取りの手順（[MigrationSource.read] の契約どおり、ストアを開く前・ロック下）:
 *
 * 1. 退避済みファイル `<name>.journal.v1` があればそれを読む（前回の取り込みが
 *    マーカー作成前に中断したケース。取り込みは Put の羅列なので再実行は冪等）
 * 2. なければ 1.x と同じ世代解決（最大世代の採用・compaction 残骸の後始末）で
 *    現在世代のファイルを特定する
 * 3. version 1 ならリプレイして最終状態のマップを作り、旧世代を削除してから
 *    現在世代を退避名へ rename する（2.x エンジンの世代名前空間を明け渡す）
 * 4. ファイルがない・既に version 2 なら引き継ぐものはない（空マップ = マーカー作成）
 *
 * 壊れたテールは 1.x のオープンと同じく黙って切り捨てる。magic 不一致や version 1 として
 * 読めない payload は退避せず例外にする（黙ってマーカーを作るとデータ喪失が確定するため）。
 *
 * マルチプロセスの同時アップグレードでは、両プロセスが取り込みを実行しうる
 * （マーカー確認と取り込みの間に他プロセスの完了が挟まるレース）。取り込み内容は同一で
 * 冪等だが、片方の取り込み完了後・マーカー可視前のユーザー編集は巻き戻りうる。
 * これは 1.x の prefs 取り込みと同じ割り切りで、取り込みがストア生成時に走る構造上
 * 実害の窓は初回起動の一瞬に限られる。
 */
internal object Daybook1xJournalMigrationSource : MigrationSource {

    override val id: String = "daybook-1x"

    override fun read(environment: MigrationEnvironment): Map<String, Any> {
        val directory = FilePath(environment.directory)
        val setAside = directory.resolve("${environment.name}$SET_ASIDE_SUFFIX")
        if (fileExists(setAside)) {
            // 退避時に検査済みの内容のはずなので、ヘッダが崩れていたら外部要因の破損。
            // 黙って空扱いにするとマーカー作成 = データ喪失の確定になるため例外にする
            val bytes = readFileOrEmpty(setAside)
            if (bytes.size < HEADER_SIZE || readInt(bytes, MAGIC.size) != VERSION_1) {
                throw JournalV1FormatException("set-aside 1.x journal is corrupted: ${setAside.path}")
            }
            requireMagic(bytes, setAside)
            return replay(bytes)
        }
        // 世代の命名・解決規則は 1.x から変わっていないため、現行の JournalDirectory を
        // そのまま使う（1.x の compaction 残骸の採用・削除も 1.x のオープンと同じに揃う）
        val journalDirectory = JournalDirectory(directory, environment.name)
        val generation = journalDirectory.resolveCurrentGeneration()
        val file = journalDirectory.fileFor(generation)
        val bytes = readFileOrEmpty(file)
        if (bytes.size < HEADER_SIZE) {
            // ファイルなし、またはヘッダ書き込み途中のクラッシュ（1.x でもデータなし扱い）。
            // 残骸はエンジンのオープンがヘッダごと書き直して再利用する
            return emptyMap()
        }
        requireMagic(bytes, file)
        if (readInt(bytes, MAGIC.size) != VERSION_1) {
            // 既に 2.x のストア（または未知の将来バージョン）。1.x の引き継ぎ対象ではない。
            // 未知バージョンはこの後のエンジンのオープンが拒否する
            return emptyMap()
        }
        val state = replay(bytes)
        // 退避の前に旧世代を消す（残すと退避後の世代解決で古いデータが現在世代に化ける）
        journalDirectory.deleteOlderThan(generation)
        if (!renameFile(file, setAside)) {
            throw IoException("failed to set aside 1.x journal: ${file.path}")
        }
        return state
    }

    private fun requireMagic(bytes: ByteArray, file: FilePath) {
        for (i in MAGIC.indices) {
            if (bytes[i] != MAGIC[i]) {
                throw JournalV1FormatException("not a daybook journal (bad magic): ${file.path}")
            }
        }
    }

    /** version 1 ジャーナル全体をリプレイし、最終状態のマップを返す。ヘッダ検査は呼び出し側。 */
    private fun replay(bytes: ByteArray): Map<String, Any> {
        val state = LinkedHashMap<String, Any>()
        var offset = HEADER_SIZE
        while (true) {
            if (offset + LENGTH_SIZE > bytes.size) break
            val length = readInt(bytes, offset)
            if (length < 0 || length > MAX_PAYLOAD_SIZE) break
            val end = offset + LENGTH_SIZE + length + CRC_SIZE
            if (end > bytes.size) break
            val crc = Crc32()
            crc.update(bytes, offset, LENGTH_SIZE + length)
            if (crc.value.toInt() != readInt(bytes, offset + LENGTH_SIZE + length)) break
            apply(state, Reader(bytes, offset + LENGTH_SIZE, offset + LENGTH_SIZE + length))
            offset = end
        }
        return state
    }

    /** 1 レコードの payload を KV 操作として読み、状態へ適用する。 */
    private fun apply(state: LinkedHashMap<String, Any>, reader: Reader) {
        when (val tag = reader.readByte()) {
            OP_SNAPSHOT_BOUNDARY -> {}

            OP_BATCH -> {
                val count = reader.readInt()
                if (count < 0) throw JournalV1FormatException("negative batch count: $count")
                repeat(count) { applySingle(state, reader, reader.readByte()) }
            }

            else -> applySingle(state, reader, tag)
        }
        reader.requireConsumed()
    }

    private fun applySingle(state: LinkedHashMap<String, Any>, reader: Reader, tag: Int) {
        when (tag) {
            OP_PUT -> {
                val key = reader.readString()
                state[key] = reader.readValue()
            }

            OP_REMOVE -> state.remove(reader.readString())

            OP_CLEAR -> state.clear()

            else -> throw JournalV1FormatException("unknown op tag: $tag")
        }
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    /** payload 上の読み取りカーソル（1.x コーデックの読み手の凍結コピー、値型は 6 種）。 */
    private class Reader(private val bytes: ByteArray, private var offset: Int, private val end: Int) {

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
            if (length < 0) throw JournalV1FormatException("negative string length: $length")
            ensureRemaining(length)
            val value = bytes.decodeToString(offset, offset + length)
            offset += length
            return value
        }

        fun readValue(): Any = when (val tag = readByte()) {
            TYPE_STRING -> readString()

            TYPE_INT -> readInt()

            TYPE_LONG -> (readInt().toLong() shl 32) or (readInt().toLong() and 0xFFFFFFFFL)

            TYPE_FLOAT -> Float.fromBits(readInt())

            TYPE_BOOLEAN -> when (val b = readByte()) {
                0 -> false
                1 -> true
                else -> throw JournalV1FormatException("invalid boolean value: $b")
            }

            TYPE_STRING_SET -> {
                val count = readInt()
                if (count < 0) throw JournalV1FormatException("negative set count: $count")
                buildSet {
                    repeat(count) { add(readString()) }
                }
            }

            else -> throw JournalV1FormatException("unknown value type tag: $tag")
        }

        fun requireConsumed() {
            if (offset != end) {
                throw JournalV1FormatException("trailing garbage: ${end - offset} bytes after operation")
            }
        }

        private fun ensureRemaining(count: Int) {
            if (offset + count > end) {
                throw JournalV1FormatException("truncated payload: need $count bytes at offset $offset, end $end")
            }
        }
    }

    private val MAGIC = byteArrayOf(0x44, 0x42, 0x4B, 0x4A) // "DBKJ"
    private const val VERSION_1 = 1
    private const val HEADER_SIZE = 8
    private const val LENGTH_SIZE = 4
    private const val CRC_SIZE = 4
    private const val MAX_PAYLOAD_SIZE = 16 * 1024 * 1024

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

    /** 取り込み後の 1.x ジャーナルの退避名サフィックス（`.journal` で終わらず世代解決に載らない）。 */
    internal const val SET_ASIDE_SUFFIX = ".journal.v1"
}

/**
 * 1.x ジャーナル（version 1）として読めないバイト列。
 *
 * CRC を通ったレコードの payload が 1.x の KV 操作として読めない、または magic 不一致。
 * 黙ってマーカーを作るとユーザーデータの喪失が確定するため、例外として open から伝播させる。
 */
internal class JournalV1FormatException(message: String) : IoException(message)
