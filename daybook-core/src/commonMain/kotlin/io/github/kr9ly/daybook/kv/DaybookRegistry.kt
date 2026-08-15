package io.github.kr9ly.daybook.kv

import io.github.kr9ly.daybook.concurrent.Lock
import io.github.kr9ly.daybook.concurrent.withLock
import io.github.kr9ly.daybook.io.FilePath
import io.github.kr9ly.daybook.io.absoluteNormalizedPath
import io.github.kr9ly.daybook.journal.SyncMode
import io.github.kr9ly.daybook.journal.platformJournalWatcherFactory

/**
 * (directory, name) → インスタンスのプロセス内キャッシュ。
 *
 * 「同じストアは常に同一インスタンス」を保証する（[Daybook.open] の契約）。
 * これはリスナーの可視性（別の取得口から登録したリスナーにも届く）と、
 * 同一ファイルへの多重オープン（シングルプロセスモードでは破損リスク）の排除の前提になる。
 * 1.x の DaybookPreferencesCache（:daybook）と同じ意味論。
 *
 * ストアはプロセス寿命で、close 経路は [resetForTesting] だけ。
 */
internal object DaybookRegistry {

    private data class Key(val directory: String, val name: String)

    private class Entry(
        val daybook: Daybook,
        val store: KvStore,
        val durability: Durability,
        val multiProcess: Boolean,
    )

    private val lock = Lock()
    private val entries = HashMap<Key, Entry>()

    fun getOrOpen(directory: String, name: String, options: DaybookOpenOptions): Daybook {
        require(name.isNotEmpty()) { "name must not be empty" }
        require(!name.contains('/')) { "name must not contain '/': $name" }
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
                return existing.daybook
            }
            val store = KvStore.open(
                directory = FilePath(key.directory),
                name = name,
                syncMode = when (options.durability) {
                    Durability.SYNC -> SyncMode.SYNC
                    Durability.ASYNC -> SyncMode.ASYNC
                },
                multiProcess = options.multiProcess,
                watcherFactory = if (options.multiProcess) platformJournalWatcherFactory() else null,
            )
            val daybook = store.asDaybook()
            entries[key] = Entry(daybook, store, options.durability, options.multiProcess)
            return daybook
        }
    }

    /** テスト専用: キャッシュを空にし、開いていたストアを閉じる。 */
    fun resetForTesting() {
        lock.withLock {
            entries.values.forEach { it.store.close() }
            entries.clear()
        }
    }
}
