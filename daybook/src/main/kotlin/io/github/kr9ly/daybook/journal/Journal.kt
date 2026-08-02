package io.github.kr9ly.daybook.journal

import java.io.Closeable

/**
 * KvStore から見たジャーナルの継ぎ目。
 *
 * 実体は [JournalFile]（追記専用のバイトレコードログ）。
 * [InMemoryJournal] が差し替わると永続化だけが不在になり、
 * 上位のキャッシュ・通知・型検査は同一コードパスのまま動く（daybook-test の基盤）。
 */
internal interface Journal : Closeable {

    /** 現在のジャーナルサイズ。compaction の閾値判定用。 */
    val length: Long

    /** オープン時のリプレイで得た正常レコード列。 */
    val replayedRecords: List<ByteArray>

    /** レコードを末尾に追記する。 */
    fun append(payload: ByteArray)

    /** これまでの追記を永続ストレージに同期する（fsync）。 */
    fun force()

    /** [length] 以降に増えた完全なレコードを読み、[length] をその境界まで進めて返す。 */
    fun readNewRecords(): List<ByteArray>
}

/**
 * ファイルを一切持たないジャーナル代替（[io.github.kr9ly.daybook.kv.KvStore.openInMemory] 用）。
 *
 * 追記は捨てられ、length は常に 0 なので compaction は構造的に起動しない。
 * 状態を持たないため全ストアで共有できる。
 */
internal object InMemoryJournal : Journal {
    override val length: Long get() = 0
    override val replayedRecords: List<ByteArray> get() = emptyList()
    override fun append(payload: ByteArray) {}
    override fun force() {}
    override fun readNewRecords(): List<ByteArray> = emptyList()
    override fun close() {}
}
