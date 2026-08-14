package io.github.kr9ly.daybook.coroutines

import io.github.kr9ly.daybook.kv.Daybook
import io.github.kr9ly.daybook.kv.KvStore
import io.github.kr9ly.daybook.kv.asDaybook
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

/** common の [changesAsFlow] の契約テスト。 */
@OptIn(ExperimentalCoroutinesApi::class)
class DaybookChangesFlowTest {

    private fun open(): Daybook = KvStore.openInMemory().asDaybook()

    @Test
    fun emitsOperationKeysInWriteOrder_withoutInitialEmission() = runTest {
        val daybook = open()
        daybook.edit { putInt("before-collect", 1) } // collect 前の変更は流れない
        val keys = daybook.changesAsFlow().produceIn(backgroundScope).also { yield() }

        daybook.edit {
            putString("a", "1")
            putString("a", "1") // 同値 put も操作として流れる
            remove("missing") // 不在キーの remove も流れる
        }
        assertEquals("a", keys.receive())
        assertEquals("a", keys.receive())
        assertEquals("missing", keys.receive())
    }

    @Test
    fun clear_emitsEachRemovedKey() = runTest {
        val daybook = open()
        daybook.edit {
            putInt("a", 1)
            putInt("b", 2)
        }
        val keys = daybook.changesAsFlow().produceIn(backgroundScope).also { yield() }
        daybook.edit { clear() }
        assertEquals(setOf("a", "b"), setOf(keys.receive(), keys.receive()))
    }
}
