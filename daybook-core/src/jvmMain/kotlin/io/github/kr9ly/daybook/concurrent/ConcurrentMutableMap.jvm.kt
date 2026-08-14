package io.github.kr9ly.daybook.concurrent

import java.util.concurrent.ConcurrentHashMap

internal actual class ConcurrentMutableMap<K : Any, V : Any> {

    private val delegate = ConcurrentHashMap<K, V>()

    actual operator fun get(key: K): V? = delegate[key]

    actual operator fun set(key: K, value: V) {
        delegate[key] = value
    }

    actual fun remove(key: K) {
        delegate.remove(key)
    }

    actual fun clear() {
        delegate.clear()
    }

    actual fun containsKey(key: K): Boolean = delegate.containsKey(key)

    actual fun putAll(from: Map<K, V>) {
        delegate.putAll(from)
    }

    actual fun snapshotKeys(): List<K> = delegate.keys.toList()

    actual fun snapshot(): Map<K, V> = HashMap(delegate)

    actual fun forEach(action: (K, V) -> Unit) {
        delegate.forEach { (key, value) -> action(key, value) }
    }
}
