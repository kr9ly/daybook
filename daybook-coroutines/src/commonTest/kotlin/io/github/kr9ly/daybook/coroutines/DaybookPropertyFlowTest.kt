package io.github.kr9ly.daybook.coroutines

import io.github.kr9ly.daybook.kv.Daybook
import io.github.kr9ly.daybook.kv.DaybookSchema
import io.github.kr9ly.daybook.kv.KvStore
import io.github.kr9ly.daybook.kv.asDaybook
import io.github.kr9ly.daybook.kv.property
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * common の [asFlow] の契約テスト。
 *
 * 配送は store の専用スレッドで非同期のため、受信側チャネルの receive で到着を待つ形で
 * 決定的にする。emission の不在そのものは断定できないので、「次に届く値」の検証で
 * conflate / distinctUntilChanged を確認する。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DaybookPropertyFlowTest {

    private object Schema : DaybookSchema("test") {
        val key = string("key")
        val intKey = int("int_key")
        val theme = string("theme")
    }

    private fun open(): Daybook = KvStore.openInMemory().asDaybook(Schema)

    /** collector を起動し、callbackFlow のリスナー登録まで走らせてからチャネルを返す。 */
    private suspend fun <T> TestScope.collect(flow: Flow<T>) =
        flow.produceIn(backgroundScope).also { yield() }

    @Test
    fun emitsDefaultOnCollect_whenAbsent() = runTest {
        val daybook = open()
        val property = daybook.property(Schema.key, default = "fallback")
        val values = collect(property.asFlow())
        assertEquals("fallback", values.receive())
    }

    @Test
    fun emitsCurrentValueOnCollect_whenPresent() = runTest {
        val daybook = open()
        val property = daybook.property(Schema.key, default = "fallback")
        property.set("value")
        val values = collect(property.asFlow())
        assertEquals("value", values.receive())
    }

    @Test
    fun emitsOnEachWrite_andDefaultAgainOnRemoval() = runTest {
        val daybook = open()
        val property = daybook.property(Schema.intKey, default = 0)
        val values = collect(property.asFlow())
        assertEquals(0, values.receive())
        property.set(1)
        assertEquals(1, values.receive())
        daybook.edit { remove("int_key") }
        assertEquals(0, values.receive())
    }

    @Test
    fun clear_reemitsDefault() = runTest {
        val daybook = open()
        val property = daybook.property(Schema.key, default = "fallback")
        property.set("value")
        val values = collect(property.asFlow())
        assertEquals("value", values.receive())
        daybook.edit { clear() }
        assertEquals("fallback", values.receive())
    }

    @Test
    fun sameValueWrites_areDeduplicated() = runTest {
        val daybook = open()
        val property = daybook.property(Schema.intKey, default = 0)
        val values = collect(property.asFlow())
        assertEquals(0, values.receive())
        property.set(0) // 操作ベース通知は飛ぶが distinctUntilChanged で落ちる
        property.set(1)
        assertEquals(1, values.receive())
    }

    @Test
    fun unrelatedKeyWrites_doNotEmit() = runTest {
        val daybook = open()
        val property = daybook.property(Schema.intKey, default = 0)
        val values = collect(property.asFlow())
        assertEquals(0, values.receive())
        daybook.edit { putInt("other", 99) }
        property.set(1)
        assertEquals(1, values.receive())
    }

    private enum class Theme { SYSTEM, DARK }

    @Test
    fun mappedProperty_flowsDecodedValues() = runTest {
        val daybook = open()
        val property = daybook.property(Schema.theme, default = Theme.SYSTEM.name)
            .map(decode = Theme::valueOf, encode = Theme::name)
        val values = collect(property.asFlow())
        assertEquals(Theme.SYSTEM, values.receive())
        property.set(Theme.DARK)
        assertEquals(Theme.DARK, values.receive())
    }
}
