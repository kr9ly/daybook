package io.github.kr9ly.daybook.kv

import io.github.kr9ly.daybook.io.IoException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [KvStore.openInMemory] のテスト。
 * ファイルを一切持たないストアが [KvStore.open] と同じ観測可能挙動
 * （キャッシュ・型検査・シンク位置の失敗経路）を示すことを検証する。
 */
class KvStoreInMemoryTest {

    @Test
    fun putGetRemoveClear_workWithoutAnyFile() {
        val store = KvStore.openInMemory()
        store.put("string", "value")
        store.put("int", 42)
        assertEquals("value", store.get("string"))
        assertEquals(42, store.get("int"))
        assertTrue(store.contains("string"))

        store.remove("string")
        assertNull(store.get("string"))

        store.clear()
        assertEquals(emptyMap<String, Any>(), store.getAll())
        assertFalse(store.recoveredFromCorruption)
    }

    @Test
    fun sink_receivesEachMutationBeforeApply() {
        val received = mutableListOf<KvOperation.Mutation>()
        val store = KvStore.openInMemory { op -> received += op }

        store.put("key", 1)
        store.writeBatch(listOf(KvOperation.Put("a", 1), KvOperation.Remove("b")))
        store.clear()

        assertEquals(
            listOf<KvOperation.Mutation>(
                KvOperation.Put("key", 1),
                KvOperation.Batch(listOf(KvOperation.Put("a", 1), KvOperation.Remove("b"))),
                KvOperation.Clear,
            ),
            received,
        )
    }

    @Test
    fun sinkThrowingIoException_propagates_andStateStaysUntouched() {
        var fail = false
        val store = KvStore.openInMemory { if (fail) throw IoException("injected") }
        store.put("key", 1)

        fail = true
        assertFailsWith<IoException> { store.put("key", 2) }

        fail = false
        assertEquals(1, store.get("key")) // シンクは適用前に呼ばれるため巻き戻し不要
    }

    @Test
    fun unsupportedValueType_isRejectedLikeFileBackedStore() {
        val store = KvStore.openInMemory()
        assertFailsWith<IllegalArgumentException> { store.put("key", 'x') }
    }

    @Test
    fun readFresh_returnsCacheValue() {
        val store = KvStore.openInMemory()
        store.put("key", 1)
        assertEquals(1, store.readFresh("key"))
    }

    @Test
    fun close_isIdempotent() {
        val store = KvStore.openInMemory()
        store.put("key", 1)
        store.close()
        store.close()
    }
}
