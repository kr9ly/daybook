package io.github.kr9ly.daybook.kv

import io.github.kr9ly.daybook.journal.DirectorySync
import io.github.kr9ly.daybook.journal.FileSink
import io.github.kr9ly.daybook.journal.JournalDirectory
import io.github.kr9ly.daybook.journal.defaultDirectorySync
import io.github.kr9ly.daybook.journal.JournalFile
import io.github.kr9ly.daybook.journal.JournalSink
import io.github.kr9ly.daybook.journal.SyncMode
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * KV ストアの変更リスナー。
 *
 * [onChange] は Put で新値、Remove / Clear で null を受け取る。
 * 値まで渡すのは、リスナー内で再取得する際の読み出し競合を避けるため
 * （SharedPreferences の OnSharedPreferenceChangeListener はキーしか渡さない）。
 *
 * 配送は store ごとの専用スレッドで、書き込み順に直列に行われる。
 * 書き込みロックの外で配送されるため、リスナー内から store を再操作してもデッドロックしない。
 */
internal fun interface KvChangeListener {
    fun onChange(key: String, newValue: Any?)
}

/**
 * compaction の一時停止点。テストがここで例外を投げることで、
 * 「compaction のこの位置でプロセスがクラッシュした」状況を決定的に注入する。
 * フックが例外を投げた後の store は不定であり、開き直して復旧を検証する使い方をする。
 */
internal enum class CompactionPhase {
    /** 新世代スナップショットを一時ファイルへ書き終え fsync した直後（rename 前）。 */
    SNAPSHOT_WRITTEN,

    /** 一時ファイルを正式な世代ファイルへ rename した直後（旧世代の削除・切り替え前）。 */
    GENERATION_COMMITTED,
}

/**
 * インメモリキャッシュ付きの KV ストア。
 *
 * オープン時にジャーナル全体をリプレイして [ConcurrentHashMap] へ展開し、
 * 以後の読み出しはすべてメモリアクセス（ディスク IO なし・同期）。
 * 書き込みはジャーナルへの追記とキャッシュへの適用を書き込みロック下で行う。
 *
 * 変更通知は状態の差分ではなく操作ベース: 同じ値の Put や存在しないキーの Remove も
 * ジャーナルに追記され、そのまま通知される。「ジャーナルに適用された操作を通知する」
 * という単一の規則にすることで、将来のプロセス跨ぎ差分リプレイと通知経路を共有する。
 * Clear は消えた各キーへの (key, null) として配送される。
 *
 * ジャーナルが閾値を超えて育つと、現在のキャッシュを新世代ファイルへ書き出して
 * アトミック rename で切り替える（世代方式 compaction）。compaction は表現の圧縮であり
 * 状態を変えないため、変更通知は発生しない。
 */
internal class KvStore private constructor(
    private val directoryFile: File,
    private val directory: JournalDirectory,
    private var generation: Long,
    private var journal: JournalFile,
    private val syncMode: SyncMode,
    private val sinkFactory: (File) -> JournalSink,
    private val directorySync: DirectorySync,
    private val compactionThreshold: Long,
    private val compactionHook: (CompactionPhase) -> Unit,
    private val cache: ConcurrentHashMap<String, Any>,
    /** オープン時にジャーナルの壊れたテールを切り捨てて復旧したか。 */
    val recoveredFromCorruption: Boolean,
) : Closeable {

    /**
     * 直前の compaction 直後のジャーナルサイズ ≒ ライブデータのサイズ。
     * ライブデータ自体が閾値を超えているとき、追記のたびに compaction が走る
     * スラッシングを「前回の 2 倍に育つまで待つ」ことで防ぐ。
     * オープン時は 0 — ごみだらけのジャーナルを開いた場合、最初の閾値超えで即 compaction する。
     */
    private var compactionBaseline = 0L

    private val writeLock = Any()
    private val listeners = CopyOnWriteArrayList<KvChangeListener>()
    private val dispatcher: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "daybook-dispatch").apply { isDaemon = true }
    }

    /** キーの現在値。未設定なら null。 */
    fun get(key: String): Any? = cache[key]

    /** キーが設定されているか。 */
    fun contains(key: String): Boolean = cache.containsKey(key)

    /** 現在の全エントリのスナップショット。以後の書き込みの影響を受けない。 */
    fun getAll(): Map<String, Any> = HashMap(cache)

    /**
     * キーへ値を設定する。
     *
     * [value] は SharedPreferences 互換の 6 種
     * （String / Int / Long / Float / Boolean / Set<String>）に限る。
     * それ以外は [IllegalArgumentException]（呼び出し側のバグのため）。
     * Set は防御的にコピーして保持する。
     */
    fun put(key: String, value: Any) {
        val stored = if (value is Set<*>) value.toSet() else value
        write(KvOperation.Put(key, stored))
    }

    /** キーを削除する。存在しないキーでもジャーナルに記録し通知する。 */
    fun remove(key: String) {
        write(KvOperation.Remove(key))
    }

    /** 全キーを削除する。 */
    fun clear() {
        write(KvOperation.Clear)
    }

    /** 変更リスナーを登録する。強参照で保持し、[removeListener] まで解放しない。 */
    fun addListener(listener: KvChangeListener) {
        listeners.add(listener)
    }

    /** 変更リスナーを解除する。 */
    fun removeListener(listener: KvChangeListener) {
        listeners.remove(listener)
    }

    private fun write(op: KvOperation) {
        // encode はロック外でも安全だが、型検査（IllegalArgumentException）を
        // 追記前に済ませるため append より先に呼ぶ
        val payload = KvOperationCodec.encode(op)
        synchronized(writeLock) {
            journal.append(payload)
            applyAndNotify(op)
            maybeCompact()
        }
    }

    private fun maybeCompact() {
        if (journal.length < compactionThreshold) return
        if (journal.length < 2 * compactionBaseline) return
        compact()
    }

    /**
     * 現在のキャッシュを新世代ジャーナルとして書き出し、アトミック rename で切り替える。
     *
     * 書き込みロック下で呼ばれるため、compaction 中の追記はプロセス内では起こらない
     * （プロセス間の競合はマルチプロセス対応のスコープ）。状態は変化しないため通知もない。
     *
     * 電源断への安全性は「一時ファイルの fsync → rename 発行 → 旧世代の削除発行」の
     * 順序で担保する（[JournalDirectory] の KDoc を参照）。rename 後は同じファイル
     * ハンドルをそのまま使い続ける（rename でファイルの実体は変わらないため）。
     */
    private fun compact() {
        val newGeneration = generation + 1
        val temp = directory.tempFor(newGeneration)
        temp.delete() // 過去に失敗した compaction の残骸があれば捨てる
        val newJournal = JournalFile.open(temp, syncMode, sinkFactory)
        try {
            cache.forEach { (key, value) ->
                newJournal.append(KvOperationCodec.encode(KvOperation.Put(key, value)))
            }
            newJournal.force()
            compactionHook(CompactionPhase.SNAPSHOT_WRITTEN)
            directory.commit(newGeneration)
            if (syncMode == SyncMode.SYNC) {
                // rename の永続化。これがないと電源断で rename が巻き戻り、
                // 以後の SYNC 追記（tmp の inode に fsync 済み）が復旧時の残骸掃除で消えうる
                directorySync.sync(directoryFile)
            }
            compactionHook(CompactionPhase.GENERATION_COMMITTED)
        } catch (e: Throwable) {
            newJournal.close()
            throw e
        }
        journal.close()
        journal = newJournal
        generation = newGeneration
        directory.deleteOlderThan(newGeneration)
        compactionBaseline = newJournal.length
    }

    /**
     * ジャーナルに載った操作をキャッシュへ適用し、通知を enqueue する。
     * ロック内で enqueue することで配送順序を書き込み順序に一致させる
     * （配送自体は専用スレッド上、つまりロック外で行われる）。
     */
    private fun applyAndNotify(op: KvOperation) {
        when (op) {
            is KvOperation.Put -> {
                cache[op.key] = op.value
                dispatch(op.key, op.value)
            }
            is KvOperation.Remove -> {
                cache.remove(op.key)
                dispatch(op.key, null)
            }
            KvOperation.Clear -> {
                val keys = cache.keys.toList()
                cache.clear()
                keys.forEach { key -> dispatch(key, null) }
            }
        }
    }

    private fun dispatch(key: String, newValue: Any?) {
        if (listeners.isEmpty()) return
        // 変更時点で登録されていたリスナーに配送する（配送時点の一覧ではなく）
        val snapshot = listeners.toList()
        dispatcher.execute {
            snapshot.forEach { listener -> listener.onChange(key, newValue) }
        }
    }

    /** enqueue 済みの通知は配送してから配送スレッドを止め、ジャーナルを閉じる。 */
    override fun close() {
        dispatcher.shutdown()
        journal.close()
    }

    companion object {

        /**
         * ジャーナルサイズがこれを超えると compaction を検討する既定値。
         * 上限しているのは実質オープン時のリプレイ時間（1 MiB ならミリ秒オーダー）。
         */
        const val DEFAULT_COMPACTION_THRESHOLD = 1L * 1024 * 1024

        /**
         * [directory] 内の現在世代のジャーナルを開いてリプレイし、読み書き可能なストアを返す。
         *
         * ジャーナルは `<name>.<世代番号>.journal` として保存され、閾値超えの compaction で
         * 世代が進む。中断された compaction の残骸はこの時点で片付けられる。
         *
         * ジャーナルとして読めないファイルは
         * [io.github.kr9ly.daybook.journal.JournalFormatException]、
         * レコードが KV 操作として読めない場合は [KvEncodingException]。
         *
         * [sinkFactory] と [compactionHook] はテストのクラッシュ注入用フック。
         */
        fun open(
            directory: File,
            name: String = "daybook",
            syncMode: SyncMode = SyncMode.ASYNC,
            compactionThreshold: Long = DEFAULT_COMPACTION_THRESHOLD,
            sinkFactory: (File) -> JournalSink = ::FileSink,
            directorySync: DirectorySync = defaultDirectorySync(),
            compactionHook: (CompactionPhase) -> Unit = {},
        ): KvStore {
            val journalDirectory = JournalDirectory(directory, name)
            val generation = journalDirectory.resolveCurrentGeneration()
            val journal = JournalFile.open(journalDirectory.fileFor(generation), syncMode, sinkFactory)
            val cache = ConcurrentHashMap<String, Any>()
            try {
                journal.replayedRecords.forEach { payload ->
                    applyReplayed(cache, KvOperationCodec.decode(payload))
                }
                if (syncMode == SyncMode.SYNC) {
                    // ジャーナルファイルの「名前」の永続化。内容をいくら fsync しても、
                    // 作成（や採用 rename）がディレクトリに永続化されるまでは
                    // 電源断でファイルごと孤児になりうる
                    directorySync.sync(directory)
                }
            } catch (e: Throwable) {
                journal.close()
                throw e
            }
            // 旧世代の削除は最新世代を開けてから（開けなかったとき直前の世代を道連れにしない）
            journalDirectory.deleteOlderThan(generation)
            return KvStore(
                directoryFile = directory,
                directory = journalDirectory,
                generation = generation,
                journal = journal,
                syncMode = syncMode,
                sinkFactory = sinkFactory,
                directorySync = directorySync,
                compactionThreshold = compactionThreshold,
                compactionHook = compactionHook,
                cache = cache,
                recoveredFromCorruption = journal.recoveredFromCorruption,
            )
        }

        private fun applyReplayed(cache: ConcurrentHashMap<String, Any>, op: KvOperation) {
            when (op) {
                is KvOperation.Put -> cache[op.key] = op.value
                is KvOperation.Remove -> cache.remove(op.key)
                KvOperation.Clear -> cache.clear()
            }
        }
    }
}
