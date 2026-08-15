package io.github.kr9ly.daybook.kv

import io.github.kr9ly.daybook.concurrent.Lock
import io.github.kr9ly.daybook.concurrent.withLock
import io.github.kr9ly.daybook.internal.DaybookInternalApi
import io.github.kr9ly.daybook.io.FilePath
import io.github.kr9ly.daybook.io.absoluteNormalizedPath
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
 * Android の SharedPreferences 顔（:daybook）も同じレジストリからストアを取得する。
 * 同じ (directory, name) には Daybook の顔と SharedPreferences の顔が同一の KvStore を
 * 共有し、別ファイルへの多重オープンは起きない。
 *
 * ストアはプロセス寿命で、close 経路は [resetForTesting] だけ。
 */
@DaybookInternalApi
public object DaybookRegistry {

    private data class Key(val directory: String, val name: String)

    private class Entry(
        val daybook: Daybook,
        val store: KvStore,
        val durability: Durability,
        val multiProcess: Boolean,
    )

    private val lock = Lock()
    private val entries = HashMap<Key, Entry>()

    /** [Daybook.open] の入口。watcher と directory fsync はプラットフォーム既定を使う。 */
    internal fun getOrOpen(directory: String, name: String, options: DaybookOpenOptions): Daybook =
        getOrOpenEntry(directory, name, options, watcherFactory = null, directorySync = null, onCreate = null).daybook

    /**
     * プラットフォーム実装（watcher / directory fsync）を注入して Daybook の顔を取得する。
     * Android の Context.openDaybook（:daybook）用。
     *
     * 注入はストアのインスタンス生成時にだけ効く。すでに別経路（[Daybook.open] 等）で
     * 生成済みなら、そのストアの結線のまま同一インスタンスが返る。
     */
    @DaybookInternalApi
    public fun openDaybook(
        directory: String,
        name: String,
        configure: DaybookOpenOptions.() -> Unit,
        watcherFactory: JournalWatcherFactory,
        directorySync: DirectorySync,
    ): Daybook = getOrOpenEntry(
        directory,
        name,
        DaybookOpenOptions().apply(configure),
        watcherFactory,
        directorySync,
        onCreate = null,
    ).daybook

    /**
     * プラットフォーム実装を注入して裏の [KvStore] を取得する。
     * SharedPreferences 顔（:daybook）が同じストアに顔をかぶせるための入口。
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
        onCreate: (KvStore) -> Unit,
    ): KvStore = getOrOpenEntry(
        directory,
        name,
        DaybookOpenOptions().apply { this.multiProcess = multiProcess },
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
        body: (KvStore) -> T,
    ): T {
        validateName(name)
        val key = Key(absoluteNormalizedPath(directory), name)
        lock.withLock {
            entries[key]?.let { return body(it.store) }
            val store = KvStore.open(
                directory = FilePath(key.directory),
                name = name,
                directorySync = directorySync,
            )
            return try {
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
            if (onCreate != null) {
                try {
                    onCreate(store)
                } catch (e: Throwable) {
                    store.close()
                    throw e
                }
            }
            val entry = Entry(store.asDaybook(), store, options.durability, options.multiProcess)
            entries[key] = entry
            return entry
        }
    }

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
