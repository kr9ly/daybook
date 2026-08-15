package io.github.kr9ly.daybook.kv

import io.github.kr9ly.daybook.concurrent.ConcurrentMutableMap
import io.github.kr9ly.daybook.concurrent.Lock
import io.github.kr9ly.daybook.concurrent.withLock
import io.github.kr9ly.daybook.internal.DaybookInternalApi
import io.github.kr9ly.daybook.io.FilePath
import io.github.kr9ly.daybook.io.deleteFile
import io.github.kr9ly.daybook.io.mkdirs
import io.github.kr9ly.daybook.journal.DirectorySync
import io.github.kr9ly.daybook.journal.FileInterProcessLock
import io.github.kr9ly.daybook.journal.FileSink
import io.github.kr9ly.daybook.journal.InMemoryJournal
import io.github.kr9ly.daybook.journal.InterProcessLock
import io.github.kr9ly.daybook.journal.Journal
import io.github.kr9ly.daybook.journal.JournalDirectory
import io.github.kr9ly.daybook.journal.JournalFile
import io.github.kr9ly.daybook.journal.JournalSink
import io.github.kr9ly.daybook.journal.JournalWatcherFactory
import io.github.kr9ly.daybook.journal.SyncMode
import io.github.kr9ly.daybook.journal.platformDirectorySync
import kotlin.concurrent.Volatile

/**
 * compaction の一時停止点。テストがここで例外を投げることで、
 * 「compaction のこの位置でプロセスがクラッシュした」状況を決定的に注入する。
 * フックが例外を投げた後の store は不定であり、開き直して復旧を検証する使い方をする。
 */
@DaybookInternalApi
public enum class CompactionPhase {
    /** 新世代スナップショットを一時ファイルへ書き終え fsync した直後（rename 前）。 */
    SNAPSHOT_WRITTEN,

    /** 一時ファイルを正式な世代ファイルへ rename した直後（旧世代の削除・切り替え前）。 */
    GENERATION_COMMITTED,
}

/**
 * インメモリキャッシュ付きの KV ストア。
 *
 * オープン時にジャーナル全体をリプレイして [ConcurrentMutableMap] へ展開し、
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
 *
 * マルチプロセスモード（open の multiProcess）では、追記ジャーナルがそのまま
 * プロセス間の変更配信チャネルになる。他プロセスの追記は watcher の検知で差分リプレイされ、
 * 自プロセスの書き込みと同じ [applyAndNotify] に合流する。書き込み排他は
 * 「プロセス内 writeLock → プロセス間ロック」の二段構えで、追記の前に必ず
 * 他プロセス分をキャッチアップする。検知の非同期性で残る「書いた直後の別プロセス読み」の
 * ウィンドウには [readFresh] を逃げ道として用意する。
 */
@DaybookInternalApi
public class KvStore private constructor(
    private val directoryFile: FilePath,
    private val directory: JournalDirectory,
    private var generation: Long,
    private var journal: Journal,
    private val syncMode: SyncMode,
    private val sinkFactory: (FilePath) -> JournalSink,
    private val directorySync: DirectorySync,
    private val compactionThreshold: Long,
    private val compactionHook: (CompactionPhase) -> Unit,
    /** マルチプロセスモードのときだけ非 null。 */
    private val interProcessLock: InterProcessLock?,
    private val cache: ConcurrentMutableMap<String, Any>,
    /** オープン時にジャーナルの壊れたテールを切り捨てて復旧したか。 */
    internal val recoveredFromCorruption: Boolean,
    /**
     * ジャーナル追記の直前に操作ごとに呼ばれるフック（通常のストアでは no-op）。
     * IOException を投げると追記失敗と同じ経路に乗る
     * （[openInMemory] が書き込み観測・失敗注入の継ぎ目として使う）。
     */
    private val writeHook: (KvOperation.Mutation) -> Unit = {},
    /**
     * リスナー通知の配送手段。null なら store 専用の配送スレッドを起動する（通常のストア）。
     * 注入された配送は書き込みロックの内側から呼ばれる点に注意 — 同期配送を注入した場合、
     * リスナーは書き込み呼び出しのスタック上で実行される（daybook-test の決定的配送用）。
     */
    injectedDelivery: ChangeNotificationDelivery? = null,
) : AutoCloseable {

    /**
     * 直前の compaction 直後のジャーナルサイズ ≒ ライブデータのサイズ。
     * ライブデータ自体が閾値を超えているとき、追記のたびに compaction が走る
     * スラッシングを「前回の 2 倍に育つまで待つ」ことで防ぐ。
     * オープン時は 0 — ごみだらけのジャーナルを開いた場合、最初の閾値超えで即 compaction する。
     */
    private var compactionBaseline = 0L

    private val writeLock = Lock()
    private var watcher: AutoCloseable? = null

    /** close 後の watcher イベントを無視するためのフラグ。writeLock 下で読み書きする。 */
    private var closed = false

    /**
     * 登録中のリスナー。イミュータブルなスナップショットの差し替えで更新する
     * （CopyOnWriteArrayList の共通コード代替）。更新は [listenersLock] 下、
     * 読み出しは volatile 読みだけでスナップショットが取れる。
     */
    @Volatile
    private var listeners: List<DaybookChangeListener> = emptyList()
    private val listenersLock = Lock()

    /** 自前の配送スレッド。配送が注入されたときは起動しない。 */
    private val ownDispatchThread: NotificationDispatchThread? =
        if (injectedDelivery == null) NotificationDispatchThread() else null

    private val delivery: ChangeNotificationDelivery = injectedDelivery ?: ownDispatchThread!!

    /** キーの現在値。未設定なら null。 */
    public fun get(key: String): Any? = cache[key]

    /** キーが設定されているか。 */
    public fun contains(key: String): Boolean = cache.containsKey(key)

    /** 現在の全エントリのスナップショット。以後の書き込みの影響を受けない。 */
    public fun getAll(): Map<String, Any> = cache.snapshot()

    /**
     * キーへ値を設定する。
     *
     * [value] は対応 7 種（String / Set<String> / Int / Long / Float / Double / Boolean）に限る。
     * それ以外は [IllegalArgumentException]（呼び出し側のバグのため）。
     * Set は防御的にコピーして保持する。
     */
    public fun put(key: String, value: Any) {
        val stored = if (value is Set<*>) value.toSet() else value
        write(KvOperation.Put(key, stored))
    }

    /** キーを削除する。存在しないキーでもジャーナルに記録し通知する。 */
    public fun remove(key: String) {
        write(KvOperation.Remove(key))
    }

    /** 全キーを削除する。 */
    public fun clear() {
        write(KvOperation.Clear)
    }

    /**
     * 複数操作を 1 ジャーナルレコードとしてアトミックに適用する。
     *
     * クラッシュ時は全操作が残るか全操作が消えるかの二択で、他プロセスからも
     * 途中状態は見えない（[KvOperation.Batch] を参照）。適用・通知は並び順どおり。
     * 空リストは何もしない。1 操作だけのときは素の操作としてジャーナルに書く
     * （アトミック性は単一操作で自明のため、バッチ表現にする理由がない）。
     *
     * 値の制約は [put] と同じ。Set は防御的にコピーして保持する。
     */
    public fun writeBatch(operations: List<KvOperation.Single>) {
        val sanitized = operations.map { op ->
            if (op is KvOperation.Put && op.value is Set<*>) op.copy(value = op.value.toSet()) else op
        }
        when (sanitized.size) {
            0 -> return
            1 -> write(sanitized[0])
            else -> write(KvOperation.Batch(sanitized))
        }
    }

    /** 変更リスナーを登録する。強参照で保持し、[removeListener] まで解放しない。 */
    public fun addListener(listener: DaybookChangeListener) {
        listenersLock.withLock {
            listeners = listeners + listener
        }
    }

    /** 変更リスナーを解除する。 */
    public fun removeListener(listener: DaybookChangeListener) {
        listenersLock.withLock {
            listeners = listeners - listener
        }
    }

    /**
     * 強一貫読み出し。
     *
     * マルチプロセスモードでは、変更検知の非同期性により「別プロセスが書いた直後の
     * 読み出しが古い値を返す」ウィンドウが原理的に残る。この API はジャーナルを確認して
     * 遅れを取り込んでから読むことで、呼び出し時点までに完了した書き込みを保証する。
     * シングルプロセスモードでは [get] と同じ。
     */
    public fun readFresh(key: String): Any? {
        if (interProcessLock != null) {
            writeLock.withLock {
                interProcessLock.withLock { catchUp() }
            }
        }
        return cache[key]
    }

    private fun write(op: KvOperation.Mutation) {
        // encode はロック外でも安全だが、型検査（IllegalArgumentException）を
        // 追記前に済ませるため append より先に呼ぶ
        val payload = KvOperationCodec.encode(op)
        writeLock.withLock {
            withProcessLock {
                if (interProcessLock != null) {
                    // 追記先を最新に合わせる（他プロセスの compaction・追記の取り込み）
                    catchUp()
                }
                writeHook(op)
                journal.append(payload)
                applyAndNotify(op)
                maybeCompact()
            }
        }
    }

    /** マルチプロセスモードならプロセス間ロックを取って [body] を実行する。 */
    private fun <T> withProcessLock(body: () -> T): T {
        val lock = interProcessLock ?: return body()
        return lock.withLock(body)
    }

    /**
     * 他プロセスの書き込みを取り込む。writeLock とプロセス間ロックの下で呼ぶ。
     *
     * 世代が進んでいなければ現ジャーナルの差分リプレイ（操作ベース通知）、
     * 進んでいれば新世代への開き直し（[switchGeneration]）。
     */
    private fun catchUp() {
        val latest = directory.resolveCurrentGeneration()
        if (latest == generation) {
            journal.readNewRecords()
                .map(KvOperationCodec::decode)
                .filterIsInstance<KvOperation.Mutation>()
                .forEach(::applyAndNotify)
        } else {
            switchGeneration(latest)
        }
    }

    /**
     * 他プロセスの compaction で進んだ世代へ開き直す。
     *
     * 新世代の中身は「スナップショット（境界マーカーまで）+ その後の追記」。
     * スナップショットは状態の再表現であり、操作ベースで通知すると全キーへ偽 Put が
     * 飛ぶため、無音で状態を再構築して自キャッシュとの差分だけを (key, newValue) で
     * 通知する。マーカー以降は通常の操作ベース通知に戻る。
     */
    private fun switchGeneration(newGeneration: Long) {
        val newJournal = JournalFile.open(directory.fileFor(newGeneration), syncMode, sinkFactory)
        journal.close()
        journal = newJournal
        generation = newGeneration
        compactionBaseline = newJournal.length // ≒ スナップショットサイズ（直後の追記込みの近似）

        val ops = newJournal.replayedRecords.map(KvOperationCodec::decode)
        val boundary = ops.indexOfFirst { it is KvOperation.SnapshotBoundary }
        // マーカーがないファイル（compaction を経ていない世代）は全体をスナップショット扱い
        val snapshotOps = if (boundary >= 0) ops.take(boundary) else ops
        val tailOps = if (boundary >= 0) ops.drop(boundary + 1) else emptyList()

        val rebuilt = HashMap<String, Any>()
        snapshotOps.forEach { applyReplayed(rebuilt, it) }
        cache.snapshotKeys().filterNot(rebuilt::containsKey).forEach { key ->
            cache.remove(key)
            dispatch(key, null)
        }
        rebuilt.forEach { (key, value) ->
            if (cache[key] != value) {
                cache[key] = value
                dispatch(key, value)
            }
        }
        tailOps.filterIsInstance<KvOperation.Mutation>().forEach(::applyAndNotify)
    }

    /** 検知層を起動する。open の最後（store の構築完了後）に呼ばれる。 */
    private fun startWatching(watcherFactory: JournalWatcherFactory, lock: InterProcessLock) {
        watcher = watcherFactory.watch(directoryFile) {
            // 検知機構のスレッドから呼ばれる。close 後のイベントは無視する
            writeLock.withLock {
                if (!closed) {
                    lock.withLock { catchUp() }
                }
            }
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
        deleteFile(temp) // 過去に失敗した compaction の残骸があれば捨てる
        val newJournal = JournalFile.open(temp, syncMode, sinkFactory)
        try {
            cache.forEach { key, value ->
                newJournal.append(KvOperationCodec.encode(KvOperation.Put(key, value)))
            }
            // 境界マーカー: 遅れて開き直したプロセスが「ここまではスナップショット」と
            // 判別するために書く（KvOperation.SnapshotBoundary の KDoc を参照）
            newJournal.append(KvOperationCodec.encode(KvOperation.SnapshotBoundary))
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
    private fun applyAndNotify(op: KvOperation.Mutation) {
        when (op) {
            is KvOperation.Single -> applySingle(op)
            is KvOperation.Batch -> op.operations.forEach(::applySingle)
        }
    }

    private fun applySingle(op: KvOperation.Single) {
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
                val keys = cache.snapshotKeys()
                cache.clear()
                keys.forEach { key -> dispatch(key, null) }
            }
        }
    }

    private fun dispatch(key: String, newValue: Any?) {
        // 変更時点で登録されていたリスナーに配送する（配送時点の一覧ではなく）
        val snapshot = listeners
        if (snapshot.isEmpty()) return
        delivery.deliver {
            snapshot.forEach { listener -> listener.onChange(key, newValue) }
        }
    }

    /** enqueue 済みの通知は配送してから配送スレッドを止め、ジャーナルを閉じる。 */
    override fun close() {
        writeLock.withLock {
            if (closed) return
            closed = true
            watcher?.close()
            interProcessLock?.close()
            journal.close()
        }
        ownDispatchThread?.close()
    }

    public companion object {

        /**
         * ジャーナルサイズがこれを超えると compaction を検討する既定値。
         * 上限しているのは実質オープン時のリプレイ時間（1 MiB ならミリ秒オーダー）。
         */
        public const val DEFAULT_COMPACTION_THRESHOLD: Long = 1L * 1024 * 1024

        /** [open] のデフォルトフック（no-op）。[openInMemory] と共有する。 */
        private val NO_OP_COMPACTION_HOOK: (CompactionPhase) -> Unit = {}

        /**
         * ファイルを一切持たないストアを返す（daybook-test 用）。
         *
         * キャッシュ・Editor バッチ・通知・値の型検査は [open] のストアと同一コードパスで、
         * 永続化だけが不在: [InMemoryJournal] が追記を捨て、[writeHook] が追記の直前に呼ばれる。
         * [writeHook] が IOException を投げると追記失敗と同じ経路に乗る（書き込み観測・失敗注入の継ぎ目）。
         * compaction は length が育たないため、watcher・プロセス間ロックはマルチプロセス限定のため、
         * 構造的に起動しない（directoryFile / directory は到達しないダミー）。
         *
         * [delivery] はリスナー通知の配送手段の差し替え（null なら専用スレッド）。
         * 同期配送を注入するとリスナーは書き込み呼び出しのスタック上で実行され、
         * 配送スレッドは起動しない（daybook-test の決定的配送の継ぎ目）。
         */
        public fun openInMemory(
            delivery: ChangeNotificationDelivery? = null,
            writeHook: (KvOperation.Mutation) -> Unit = {},
        ): KvStore = KvStore(
            directoryFile = FilePath(""),
            directory = JournalDirectory(FilePath(""), "in-memory"),
            generation = 0,
            journal = InMemoryJournal,
            syncMode = SyncMode.ASYNC,
            sinkFactory = ::FileSink,
            directorySync = platformDirectorySync(),
            compactionThreshold = DEFAULT_COMPACTION_THRESHOLD,
            compactionHook = NO_OP_COMPACTION_HOOK,
            interProcessLock = null,
            cache = ConcurrentMutableMap(),
            recoveredFromCorruption = false,
            writeHook = writeHook,
            injectedDelivery = delivery,
        )

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
         * [multiProcess] を有効にすると、プロセス間ロックによる書き込み排他と
         * watcher による他プロセス変更の検知・差分リプレイが働く（クラス KDoc を参照）。
         * オープン時の復旧（テール切り捨て・世代解決）もプロセス間ロック下で行われる。
         * マルチプロセスモードでは [watcherFactory] が必須（プラットフォームの検知実装を
         * 呼び出し側が渡す。Android は :daybook の FileObserver 実装）。
         *
         * [sinkFactory] と [compactionHook] はテストのクラッシュ注入用フック。
         * [lockFactory] と [watcherFactory] は「1 JVM 内で複数プロセスを模す」
         * JVM テスト用の注入点（実 FileLock / FileObserver は Instrumentation テストで検証）。
         */
        public fun open(
            directory: FilePath,
            name: String = "daybook",
            syncMode: SyncMode = SyncMode.ASYNC,
            multiProcess: Boolean = false,
            compactionThreshold: Long = DEFAULT_COMPACTION_THRESHOLD,
            sinkFactory: (FilePath) -> JournalSink = ::FileSink,
            directorySync: DirectorySync = platformDirectorySync(),
            compactionHook: (CompactionPhase) -> Unit = NO_OP_COMPACTION_HOOK,
            lockFactory: (FilePath) -> InterProcessLock = ::FileInterProcessLock,
            watcherFactory: JournalWatcherFactory? = null,
        ): KvStore {
            val journalDirectory = JournalDirectory(directory, name)
            if (!multiProcess) {
                return openLocked(
                    directory,
                    journalDirectory,
                    syncMode,
                    compactionThreshold,
                    sinkFactory,
                    directorySync,
                    compactionHook,
                    interProcessLock = null,
                )
            }
            requireNotNull(watcherFactory) { "multiProcess requires a watcherFactory" }
            mkdirs(directory) // ロックファイルの置き場所を先に確保する
            val lock = lockFactory(journalDirectory.lockFile())
            val store = try {
                lock.withLock {
                    openLocked(
                        directory,
                        journalDirectory,
                        syncMode,
                        compactionThreshold,
                        sinkFactory,
                        directorySync,
                        compactionHook,
                        interProcessLock = lock,
                    )
                }
            } catch (e: Throwable) {
                lock.close()
                throw e
            }
            store.startWatching(watcherFactory, lock)
            return store
        }

        private fun openLocked(
            directory: FilePath,
            journalDirectory: JournalDirectory,
            syncMode: SyncMode,
            compactionThreshold: Long,
            sinkFactory: (FilePath) -> JournalSink,
            directorySync: DirectorySync,
            compactionHook: (CompactionPhase) -> Unit,
            interProcessLock: InterProcessLock?,
        ): KvStore {
            val generation = journalDirectory.resolveCurrentGeneration()
            val journal = JournalFile.open(journalDirectory.fileFor(generation), syncMode, sinkFactory)
            val cache = ConcurrentMutableMap<String, Any>()
            try {
                val replayed = HashMap<String, Any>()
                journal.replayedRecords.forEach { payload ->
                    applyReplayed(replayed, KvOperationCodec.decode(payload))
                }
                cache.putAll(replayed)
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
                interProcessLock = interProcessLock,
                cache = cache,
                recoveredFromCorruption = journal.recoveredFromCorruption,
            )
        }

        private fun applyReplayed(cache: MutableMap<String, Any>, op: KvOperation) {
            when (op) {
                is KvOperation.Put -> cache[op.key] = op.value
                is KvOperation.Remove -> cache.remove(op.key)
                KvOperation.Clear -> cache.clear()
                is KvOperation.Batch -> op.operations.forEach { applyReplayed(cache, it) }
                KvOperation.SnapshotBoundary -> {}
            }
        }
    }
}
