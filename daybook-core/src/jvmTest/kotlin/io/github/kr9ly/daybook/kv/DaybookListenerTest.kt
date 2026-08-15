package io.github.kr9ly.daybook.kv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [Daybook] のリスナー配送テスト。
 *
 * 配送は専用スレッドで非同期のため、待ち合わせ（CountDownLatch）が要る jvmTest に置く。
 * 配送順序・操作ベース通知の本体はエンジン側（KvStoreTest 等）で検証済みで、
 * ここでは顔の登録・解除がエンジンに素通しされることを確認する。
 */
class DaybookListenerTest {

    private class Events(expectedCount: Int) {
        private val list = Collections.synchronizedList(mutableListOf<Pair<String, Any?>>())
        private val latch = CountDownLatch(expectedCount)

        val listener = DaybookChangeListener { key, newValue ->
            list.add(key to newValue)
            latch.countDown()
        }

        fun await(): List<Pair<String, Any?>> {
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            return list.toList()
        }
    }

    @Test
    fun listener_receivesBatchInCallOrder() {
        val store = KvStore.openInMemory()
        val daybook = store.asDaybook()
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
        val daybook = store.asDaybook()
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
