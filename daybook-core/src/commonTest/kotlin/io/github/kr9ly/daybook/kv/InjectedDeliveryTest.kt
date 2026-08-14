package io.github.kr9ly.daybook.kv

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * openInMemory への配送注入（daybook-test の決定的配送の継ぎ目）のテスト。
 * 注入時は配送スレッドを起動せず、リスナーは書き込み呼び出しのスタック上で実行される。
 */
class InjectedDeliveryTest {

    @Test
    fun injectedSynchronousDelivery_runsListenersOnWriterStack() {
        val store = KvStore.openInMemory(delivery = { it() })
        val events = mutableListOf<Pair<String, Any?>>()
        store.addListener { key, newValue -> events += key to newValue }

        store.put("key", 1)
        // put が返った時点で配送済み（専用スレッドなら待ち合わせが要る）
        assertEquals(listOf<Pair<String, Any?>>("key" to 1), events)

        store.close() // 自前スレッドを持たないので shutdown 対象がなくても安全
        assertEquals(1, events.size)
    }
}
