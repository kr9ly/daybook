package io.github.kr9ly.daybook.concurrent

/**
 * [Lock] で全操作を守る HashMap による実装。
 *
 * JVM actual（ConcurrentHashMap）の読み出しロックフリー性は持たないが、
 * KvStore の読みホットパスは短いクリティカルセクションの get だけなので、
 * まずは正しさ優先の最小実装とする（計測してから最適化する）。
 * Lock は再帰 mutex のため、forEach の action からの再入（get 等）は安全。
 */
internal actual class ConcurrentMutableMap<K : Any, V : Any> {

    private val lock = Lock()
    private val delegate = HashMap<K, V>()

    actual operator fun get(key: K): V? = lock.withLock { delegate[key] }

    actual operator fun set(key: K, value: V) {
        lock.withLock { delegate[key] = value }
    }

    actual fun remove(key: K) {
        lock.withLock { delegate.remove(key) }
    }

    actual fun clear() {
        lock.withLock { delegate.clear() }
    }

    actual fun containsKey(key: K): Boolean = lock.withLock { delegate.containsKey(key) }

    actual fun putAll(from: Map<K, V>) {
        lock.withLock { delegate.putAll(from) }
    }

    actual fun snapshotKeys(): List<K> = lock.withLock { delegate.keys.toList() }

    actual fun snapshot(): Map<K, V> = lock.withLock { HashMap(delegate) }

    actual fun forEach(action: (K, V) -> Unit) {
        lock.withLock {
            delegate.forEach { (key, value) -> action(key, value) }
        }
    }
}
