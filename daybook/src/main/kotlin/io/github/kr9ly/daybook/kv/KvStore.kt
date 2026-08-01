package io.github.kr9ly.daybook.kv

import io.github.kr9ly.daybook.journal.FileSink
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
 */
internal class KvStore private constructor(
    private val journal: JournalFile,
    private val cache: ConcurrentHashMap<String, Any>,
    /** オープン時にジャーナルの壊れたテールを切り捨てて復旧したか。 */
    val recoveredFromCorruption: Boolean,
) : Closeable {

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
        }
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
         * ジャーナルを開いてリプレイし、読み書き可能なストアを返す。
         *
         * ジャーナルとして読めないファイルは
         * [io.github.kr9ly.daybook.journal.JournalFormatException]、
         * レコードが KV 操作として読めない場合は [KvEncodingException]。
         *
         * [sinkFactory] はテストのクラッシュ注入用フック。
         */
        fun open(
            file: File,
            syncMode: SyncMode = SyncMode.ASYNC,
            sinkFactory: (File) -> JournalSink = ::FileSink,
        ): KvStore {
            val journal = JournalFile.open(file, syncMode, sinkFactory)
            val cache = ConcurrentHashMap<String, Any>()
            try {
                journal.replayedRecords.forEach { payload ->
                    applyReplayed(cache, KvOperationCodec.decode(payload))
                }
            } catch (e: Throwable) {
                journal.close()
                throw e
            }
            return KvStore(
                journal = journal,
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
