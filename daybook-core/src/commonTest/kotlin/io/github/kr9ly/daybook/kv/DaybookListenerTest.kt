package io.github.kr9ly.daybook.kv

import io.github.kr9ly.daybook.concurrent.Lock
import io.github.kr9ly.daybook.concurrent.waitUntil
import io.github.kr9ly.daybook.concurrent.withLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Daybook] のリスナー配送テスト。
 *
 * 配送は専用スレッドで非同期のため、待ち合わせ（waitUntil ポーリング）が要る。
 * 配送順序・操作ベース通知の本体はエンジン側（KvStoreTest 等）で検証済みで、
 * ここでは Daybook アダプタの登録・解除がエンジンに素通しされることを確認する。
 */
class DaybookListenerTest {

    private object PlainSchema : DaybookSchema("test")

    private class Events(private val expectedCount: Int) {
        private val lock = Lock()
        private val list = mutableListOf<Pair<String, Any?>>()

        val listener = DaybookChangeListener { key, newValue ->
            lock.withLock { list.add(key to newValue) }
        }

        fun await(): List<Pair<String, Any?>> {
            assertTrue(waitUntil { lock.withLock { list.size } >= expectedCount })
            return lock.withLock { list.toList() }
        }
    }

    @Test
    fun listener_receivesBatchInCallOrder() {
        val store = KvStore.openInMemory()
        val daybook = store.asDaybook(PlainSchema)
        val events = Events(expectedCount = 3)
        daybook.addChangeListener(events.listener)
        daybook.edit {
            putString("a", "1")
            putDouble("b", 2.5)
            remove("a")
        }
        assertEquals(
            listOf<Pair<String, Any?>>("a" to "1", "b" to 2.5, "a" to null),
            events.await(),
        )
        store.close()
    }

    @Test
    fun removedListener_stopsReceiving() {
        val store = KvStore.openInMemory()
        val daybook = store.asDaybook(PlainSchema)
        val removed = Events(expectedCount = 1)
        val kept = Events(expectedCount = 2)
        daybook.addChangeListener(removed.listener)
        daybook.addChangeListener(kept.listener)

        daybook.edit { putInt("first", 1) }
        assertEquals(listOf<Pair<String, Any?>>("first" to 1), removed.await())

        daybook.removeChangeListener(removed.listener)
        daybook.edit { putInt("second", 2) }
        assertEquals(
            listOf<Pair<String, Any?>>("first" to 1, "second" to 2),
            kept.await(),
        )
        assertEquals(listOf<Pair<String, Any?>>("first" to 1), removed.await())
        store.close()
    }
}
