package io.github.kr9ly.daybook.kv

import io.github.kr9ly.daybook.concurrent.Lock
import io.github.kr9ly.daybook.concurrent.withLock
import io.github.kr9ly.daybook.internal.DaybookInternalApi
import io.github.kr9ly.daybook.io.FilePath
import io.github.kr9ly.daybook.io.absoluteNormalizedPath
import io.github.kr9ly.daybook.io.createEmptyFile
import io.github.kr9ly.daybook.io.fileExists
import io.github.kr9ly.daybook.journal.DirectorySync
import io.github.kr9ly.daybook.journal.JournalWatcherFactory
import io.github.kr9ly.daybook.journal.SyncMode
import io.github.kr9ly.daybook.journal.platformDirectorySync
import io.github.kr9ly.daybook.journal.platformJournalWatcherFactory

/**
 * (directory, name) → インスタンスのプロセス内キャッシュ。
 *
 * 「同じストアは常に同一インスタンス」を保証する（[Daybook.open] の契約）。
 * これはリスナーの可視性（別の取得口から登録したリスナーにも届く）と、
 * 同一ファイルへの多重オープン（シングルプロセスモードでは破損リスク）の排除の前提になる。
 *
 * Android の SharedPreferences 互換 API（:daybook）も同じレジストリからストアを取得する。
 * 同じ (directory, name) には Daybook API と SharedPreferences 互換 API が同一の KvStore を
 * 共有し、別ファイルへの多重オープンは起きない。
 *
 * ストアはプロセス寿命で、close 経路は [resetForTesting] だけ。
 */
@DaybookInternalApi
public object DaybookRegistry {

    private data class Key(val directory: String, val name: String)

    private class Entry(
        val store: KvStore,
        val durability: Durability,
        val multiProcess: Boolean,
    ) {
        /** 最初のスキーマ付き open が採用するスキーマ。SharedPreferences 互換 API だけの間は null。 */
        var schema: DaybookSchema? = null

        /** スキーマ採用時に生成される Daybook アダプタ。 */
        var daybook: Daybook? = null
    }

    private val lock = Lock()
    private val entries = HashMap<Key, Entry>()

    /** [Daybook.open] の入口。watcher と directory fsync はプラットフォーム既定を使う。 */
    internal fun getOrOpen(directory: String, schema: DaybookSchema, options: DaybookOpenOptions): Daybook =
        openWithSchema(directory, schema, options, watcherFactory = null, directorySync = null)

    /**
     * プラットフォーム実装（watcher / directory fsync）を注入して Daybook アダプタを取得する。
     * Android の Context.openDaybook（:daybook）用。
     *
     * 注入はストアのインスタンス生成時にだけ効く。すでに別経路（[Daybook.open] 等）で
     * 生成済みなら、そのストアの結線のまま同一インスタンスが返る。
     */
    @DaybookInternalApi
    public fun openDaybook(
        directory: String,
        schema: DaybookSchema,
        configure: DaybookOpenOptions.() -> Unit,
        watcherFactory: JournalWatcherFactory,
        directorySync: DirectorySync,
    ): Daybook = openWithSchema(directory, schema, DaybookOpenOptions().apply(configure), watcherFactory, directorySync)

    /**
     * スキーマ付き open の共通経路。エントリを取得（なければ生成）した上で、スキーマの
     * 採用または同一性検査を行い、Daybook アダプタを返す。
     *
     * スキーマの採用: SharedPreferences 互換 API（文字列 name の 1.x 経路）が先に同名のストアを
     * 生成していた場合、エントリはスキーマ未設定で存在する。最初のスキーマ付き open が
     * スキーマを採用させ、以後の open は同一オブジェクトであることを検査される。
     */
    private fun openWithSchema(
        directory: String,
        schema: DaybookSchema,
        options: DaybookOpenOptions,
        watcherFactory: JournalWatcherFactory?,
        directorySync: DirectorySync?,
    ): Daybook {
        validateMigrationTargets(schema, options.migrations)
        lock.withLock {
            val entry =
                getOrOpenEntry(directory, schema.storeName, options, watcherFactory, directorySync, onCreate = null)
            val adopted = entry.schema
            if (adopted == null) {
                entry.schema = schema
            } else {
                require(adopted === schema) {
                    "\"${schema.storeName}\" is already open with schema ${adopted::class.simpleName}; " +
                        "the same store must always be opened with the same schema object"
                }
            }
            entry.daybook?.let { return it }
            val daybook = entry.store.asDaybook(schema)
            entry.daybook = daybook
            return daybook
        }
    }

    /**
     * 型付きビルダー製のマイグレーションソース（[SchemaTargetedMigrationSource]）の宛先が、
     * 開こうとしているスキーマに属するかを検査する。任意実装の [MigrationSource] には課さない。
     */
    private fun validateMigrationTargets(schema: DaybookSchema, migrations: List<MigrationSource>) {
        migrations.filterIsInstance<SchemaTargetedMigrationSource>().forEach { source ->
            source.targets.forEach { target ->
                require(target.schema === schema) {
                    "migration source \"${source.id}\" targets key \"${target.name}\" of schema " +
                        "\"${target.schema.storeName}\", but the store is being opened with schema \"${schema.storeName}\""
                }
            }
        }
    }

    /**
     * プラットフォーム実装を注入して裏の [KvStore] を取得する。
     * SharedPreferences 互換 API（:daybook）が同じストアにアダプタをかぶせるための入口。
     *
     * durability は常に既定（[Durability.ASYNC]）。同じストアが [Durability.SYNC] で
     * 開かれている場合はオプション不一致で IllegalArgumentException になる。
     *
     * [onCreate] はこの呼び出しがインスタンスを生成したときにだけ、レジストリのロック下・
     * ストアがキャッシュに載る前に呼ばれる（透過 import の実行位置）。例外を投げると
     * ストアは閉じられ、キャッシュには載らずに例外が伝播する。
     */
    @DaybookInternalApi
    public fun getOrOpenStore(
        directory: String,
        name: String,
        multiProcess: Boolean,
        watcherFactory: JournalWatcherFactory,
        directorySync: DirectorySync,
        migrations: List<MigrationSource> = emptyList(),
        onCreate: (KvStore) -> Unit,
    ): KvStore = getOrOpenEntry(
        directory,
        name,
        DaybookOpenOptions().apply {
            this.multiProcess = multiProcess
            this.migrations = migrations
        },
        watcherFactory,
        directorySync,
        onCreate,
    ).store

    /**
     * name のストアに対して [body] を実行する（マイグレーション用）。
     * キャッシュ済みならそのストア、未オープンなら一時的に開いて閉じる。
     * 全体をレジストリのロック下で行い、オープンや他のマイグレーションと直列化する
     * （キャッシュ外での一時オープンが、並行するオープンと同一ファイルの多重オープンに
     * ならないための前提）。
     */
    @DaybookInternalApi
    public fun <T> withStore(
        directory: String,
        name: String,
        directorySync: DirectorySync,
        migrations: List<MigrationSource> = emptyList(),
        body: (KvStore) -> T,
    ): T {
        validateName(name)
        val key = Key(absoluteNormalizedPath(directory), name)
        lock.withLock {
            entries[key]?.let { return body(it.store) }
            val pending = readPendingMigrations(key, migrations)
            val store = KvStore.open(
                directory = FilePath(key.directory),
                name = name,
                directorySync = directorySync,
            )
            return try {
                applyMigrations(key, store, pending)
                body(store)
            } finally {
                store.close()
            }
        }
    }

    private fun getOrOpenEntry(
        directory: String,
        name: String,
        options: DaybookOpenOptions,
        watcherFactory: JournalWatcherFactory?,
        directorySync: DirectorySync?,
        onCreate: ((KvStore) -> Unit)?,
    ): Entry {
        validateName(name)
        val key = Key(absoluteNormalizedPath(directory), name)
        lock.withLock {
            entries[key]?.let { existing ->
                require(
                    existing.durability == options.durability &&
                        existing.multiProcess == options.multiProcess,
                ) {
                    "\"$name\" in ${key.directory} is already open with " +
                        "durability=${existing.durability}, multiProcess=${existing.multiProcess}; " +
                        "all callers must use the same options for the same store"
                }
                return existing
            }
            // ソースの読み取りはストアを開く前に行う（1.x ジャーナルのように、ソースが
            // ストアのファイル名前空間を占有している場合に退避できるよう）。適用は
            // リプレイ後・open が返る前（MigrationSource の KDoc の実行契約）
            val pendingMigrations = readPendingMigrations(key, options.migrations)
            val store = KvStore.open(
                directory = FilePath(key.directory),
                name = name,
                syncMode = when (options.durability) {
                    Durability.SYNC -> SyncMode.SYNC
                    Durability.ASYNC -> SyncMode.ASYNC
                },
                multiProcess = options.multiProcess,
                directorySync = directorySync ?: platformDirectorySync(),
                watcherFactory = if (options.multiProcess) {
                    watcherFactory ?: platformJournalWatcherFactory()
                } else {
                    null
                },
            )
            try {
                // マイグレーション → onCreate の順（Android の prefs 取り込みは 1.x 由来の
                // データの上に重なる）。失敗したストアはキャッシュに載せない
                applyMigrations(key, store, pendingMigrations)
                onCreate?.invoke(store)
            } catch (e: Throwable) {
                store.close()
                throw e
            }
            val entry = Entry(store, options.durability, options.multiProcess)
            entries[key] = entry
            return entry
        }
    }

    /**
     * マーカーのないソースの読み取りを、ストアを開く前に実行する。
     * 読み取り結果が null のソース（まだ読める状態にない）は今回スキップし、マーカーも
     * 作らない（次のストア生成時に再試行される）。同じ id の重複は最初の 1 つだけを使う。
     */
    private fun readPendingMigrations(
        key: Key,
        sources: List<MigrationSource>,
    ): List<Pair<MigrationSource, Map<String, Any>>> = sources
        .distinctBy { it.id }
        .filterNot { source ->
            require(source.id.isNotEmpty() && !source.id.contains('/')) {
                "migration source id must be non-empty and must not contain '/': \"${source.id}\""
            }
            fileExists(markerFile(key, source.id))
        }
        .mapNotNull { source ->
            source.read(MigrationEnvironment(key.directory, key.name))?.let { source to it }
        }

    /**
     * 読み取り済みの値をアトミックな 1 バッチとしてストアへ書き、ソースごとのマーカーを作る。
     * マーカー作成前のクラッシュは次回の再取り込みで回復する（[MigrationSource] の KDoc）。
     */
    private fun applyMigrations(
        key: Key,
        store: KvStore,
        pending: List<Pair<MigrationSource, Map<String, Any>>>,
    ) {
        pending.forEach { (source, values) ->
            if (values.isNotEmpty()) {
                store.writeBatch(values.map { (k, v) -> KvOperation.Put(k, v) })
            }
            createEmptyFile(markerFile(key, source.id))
        }
    }

    private fun markerFile(key: Key, id: String): FilePath =
        FilePath(key.directory).resolve("${key.name}.$id.migrated")

    private fun validateName(name: String) {
        require(name.isNotEmpty()) { "name must not be empty" }
        require(!name.contains('/')) { "name must not contain '/': $name" }
    }

    /** テスト専用: キャッシュを空にし、開いていたストアを閉じる（プロセス再起動の模倣）。 */
    @DaybookInternalApi
    public fun resetForTesting() {
        lock.withLock {
            entries.values.forEach { it.store.close() }
            entries.clear()
        }
    }
}
