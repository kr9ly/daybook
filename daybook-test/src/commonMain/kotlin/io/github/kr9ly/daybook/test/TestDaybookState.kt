package io.github.kr9ly.daybook.test

import io.github.kr9ly.daybook.concurrent.Lock
import io.github.kr9ly.daybook.concurrent.withLock
import io.github.kr9ly.daybook.io.IoException
import io.github.kr9ly.daybook.kv.Daybook
import io.github.kr9ly.daybook.kv.DaybookSchema
import io.github.kr9ly.daybook.kv.KvOperation
import io.github.kr9ly.daybook.kv.KvStore
import io.github.kr9ly.daybook.kv.asDaybook

/**
 * [TestDaybook] の actual 間で共有する実装。
 *
 * 名前ごとの Entry が「ストア + その上の顔たち」を束ねる。ストアは in-memory の
 * 本物のエンジンで、同期配送（deliver = 直接呼び出し）と書き込み観測（記録・失敗注入）を
 * 注入して生成する。Daybook の顔は common で組み立て、プラットフォーム固有の顔
 * （Android の SharedPreferences）は [secondaryFace] 経由で actual 側が同じストアにかぶせる。
 */
internal class TestDaybookState {

    private class Entry {
        var store: KvStore? = null
        var schema: DaybookSchema? = null
        var daybook: Daybook? = null
        var secondaryFace: Any? = null
        var multiProcess: Boolean? = null
        val commits = mutableListOf<RecordedCommit>()
        var failNextWrite = false
    }

    private val lock = Lock()
    private val entries = HashMap<String, Entry>()

    fun getDaybook(schema: DaybookSchema, multiProcess: Boolean): Daybook {
        lock.withLock {
            val entry = entryFor(schema.storeName, multiProcess)
            val adopted = entry.schema
            if (adopted == null) {
                entry.schema = schema
            } else {
                require(adopted === schema) {
                    "\"${schema.storeName}\" is already open with another schema object; " +
                        "the same store must always be opened with the same schema object"
                }
            }
            entry.daybook?.let { return it }
            val daybook = storeFor(entry).asDaybook(schema)
            entry.daybook = daybook
            return daybook
        }
    }

    /**
     * プラットフォーム固有の顔を返す。初回アクセス時に [create] で同じストアの上に生成する。
     * 顔の型はプラットフォームごとに 1 種類の前提（actual 側だけが呼ぶ）。
     */
    fun <T : Any> secondaryFace(name: String, multiProcess: Boolean, create: (KvStore) -> T): T {
        lock.withLock {
            val entry = entryFor(name, multiProcess)
            entry.secondaryFace?.let {
                @Suppress("UNCHECKED_CAST")
                return it as T
            }
            val face = create(storeFor(entry))
            entry.secondaryFace = face
            return face
        }
    }

    fun commits(name: String): List<RecordedCommit> {
        lock.withLock {
            return entryFor(name, multiProcess = null).commits.toList()
        }
    }

    fun failNextWrite(name: String) {
        lock.withLock {
            entryFor(name, multiProcess = null).failNextWrite = true
        }
    }

    /**
     * lock の下で呼ぶ。[multiProcess] が非 null（顔の取得）のときはフラグの整合性を検査する。
     * フラグは顔をまたいで 1 つ: Daybook と SharedPreferences で異なる値を渡すのも不整合。
     */
    private fun entryFor(name: String, multiProcess: Boolean?): Entry {
        require(name.isNotEmpty()) { "name must not be empty" }
        require(!name.contains('/')) { "name must not contain '/': $name" }
        val entry = entries.getOrPut(name) { Entry() }
        if (multiProcess != null) {
            val existing = entry.multiProcess
            if (existing == null) {
                entry.multiProcess = multiProcess
            } else {
                require(existing == multiProcess) {
                    "\"$name\" is already open with multiProcess=$existing; " +
                        "all callers must use the same flag for the same name"
                }
            }
        }
        return entry
    }

    /** lock の下で呼ぶ。ストアは名前ごとに 1 つで、全部の顔が共有する。 */
    private fun storeFor(entry: Entry): KvStore {
        entry.store?.let { return it }
        val store = KvStore.openInMemory(
            delivery = { it() },
            writeHook = { op -> record(entry, op) },
        )
        entry.store = store
        return store
    }

    /**
     * 書き込みバッチを記録する（ジャーナル追記が起きる位置 — 状態への適用と通知の前）。
     * 失敗注入が保留されていればここで消費し、ディスク障害と同じ形（IOException）で落とす。
     */
    private fun record(entry: Entry, op: KvOperation.Mutation) {
        lock.withLock {
            if (entry.failNextWrite) {
                entry.failNextWrite = false
                throw IoException("write failure injected by TestDaybook.failNextWrite")
            }
            val singles = when (op) {
                is KvOperation.Single -> listOf(op)
                is KvOperation.Batch -> op.operations
            }
            var clearRequested = false
            val changes = LinkedHashMap<String, Any?>()
            singles.forEach { single ->
                when (single) {
                    is KvOperation.Put -> changes[single.key] = single.value
                    is KvOperation.Remove -> changes[single.key] = null
                    KvOperation.Clear -> clearRequested = true
                }
            }
            entry.commits += RecordedCommit(clearRequested, changes)
        }
    }
}
