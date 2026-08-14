package io.github.kr9ly.daybook.concurrent

/**
 * スレッド安全な可変マップの最小表面。KvStore のインメモリキャッシュ用。
 *
 * JVM actual は ConcurrentHashMap を包み、読み出しホットパスのロックフリー性を維持する。
 * MutableMap インターフェースは実装しない（必要な操作だけを表面に持ち、
 * イテレータの一貫性契約などプラットフォームごとに揺れる部分を表面から締め出す）。
 */
internal expect class ConcurrentMutableMap<K : Any, V : Any>() {

    operator fun get(key: K): V?

    operator fun set(key: K, value: V)

    fun remove(key: K)

    fun clear()

    fun containsKey(key: K): Boolean

    fun putAll(from: Map<K, V>)

    /** 現在のキー一覧のスナップショット。以後の書き込みの影響を受けない。 */
    fun snapshotKeys(): List<K>

    /** 現在の全エントリのスナップショット。以後の書き込みの影響を受けない。 */
    fun snapshot(): Map<K, V>

    /** 現在のエントリを列挙する。列挙中の変更を防ぐのは呼び出し側の責務。 */
    fun forEach(action: (K, V) -> Unit)
}
