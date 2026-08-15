package io.github.kr9ly.daybook.kv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 公開の顔 [Daybook] の契約テスト。
 *
 * ストレージ実装（in-memory）はエンジンと同一コードパスのため、ここでは顔の契約 —
 * getter のデフォルト・型違い・防御コピー・edit のバッチ化 — に集中する。
 */
class DaybookTest {

    private object PlainSchema : DaybookSchema("test")

    private fun open(): Daybook = KvStore.openInMemory().asDaybook(PlainSchema)

    // --- getter の契約 ---

    @Test
    fun getters_returnDefaultsForAbsentKeys() {
        val daybook = open()
        assertNull(daybook.getString("missing", null))
        assertEquals("fallback", daybook.getString("missing", "fallback"))
        assertNull(daybook.getStringSet("missing", null))
        assertEquals(setOf("a"), daybook.getStringSet("missing", setOf("a")))
        assertEquals(7, daybook.getInt("missing", 7))
        assertEquals(7L, daybook.getLong("missing", 7L))
        assertEquals(0.5f, daybook.getFloat("missing", 0.5f))
        assertEquals(0.25, daybook.getDouble("missing", 0.25))
        assertTrue(daybook.getBoolean("missing", true))
    }

    @Test
    fun getters_returnStoredValues() {
        val daybook = open()
        daybook.edit {
            putString("string", "value")
            putStringSet("set", setOf("x", "y"))
            putInt("int", 1)
            putLong("long", 2L)
            putFloat("float", 3.5f)
            putDouble("double", 4.5)
            putBoolean("boolean", true)
        }
        assertEquals("value", daybook.getString("string", null))
        assertEquals(setOf("x", "y"), daybook.getStringSet("set", null))
        assertEquals(1, daybook.getInt("int", 0))
        assertEquals(2L, daybook.getLong("long", 0L))
        assertEquals(3.5f, daybook.getFloat("float", 0f))
        assertEquals(4.5, daybook.getDouble("double", 0.0))
        assertTrue(daybook.getBoolean("boolean", false))
    }

    @Test
    fun getterWithWrongType_throwsClassCastException() {
        val daybook = open()
        daybook.edit { putString("key", "not an int") }
        assertFailsWith<ClassCastException> {
            daybook.getInt("key", 0)
        }
    }

    @Test
    fun getStringSet_returnsDefensiveCopy() {
        val daybook = open()
        daybook.edit { putStringSet("set", setOf("a")) }
        // 読むたびに独立したコピーが返る（格納 Set の生参照を露出しない）
        assertNotSame(daybook.getStringSet("set", null), daybook.getStringSet("set", null))
        assertEquals(setOf("a"), daybook.getStringSet("set", null))
    }

    @Test
    fun putStringSet_copiesAtPutTime() {
        val daybook = open()
        val source = mutableSetOf("a")
        daybook.edit {
            putStringSet("set", source)
            source.add("late") // 積んだ後の変更はバッチに影響しない
        }
        assertEquals(setOf("a"), daybook.getStringSet("set", null))
    }

    @Test
    fun contains_reflectsPresence() {
        val daybook = open()
        assertFalse(daybook.contains("key"))
        daybook.edit { putInt("key", 1) }
        assertTrue(daybook.contains("key"))
    }

    // --- edit の契約 ---

    @Test
    fun edit_nullPutsAreRemovals() {
        val daybook = open()
        daybook.edit {
            putString("string", "value")
            putStringSet("set", setOf("a"))
        }
        daybook.edit {
            putString("string", null)
            putStringSet("set", null)
        }
        assertFalse(daybook.contains("string"))
        assertFalse(daybook.contains("set"))
    }

    @Test
    fun edit_appliesOperationsInCallOrder() {
        val daybook = open()
        daybook.edit { putString("key", "before") }
        // clear は AOSP の Editor と違い並べ替えられない: 後から積んだ clear は先の put も消す
        daybook.edit {
            putString("key", "inside")
            clear()
            putString("other", "survives")
        }
        assertFalse(daybook.contains("key"))
        assertEquals("survives", daybook.getString("other", null))
    }

    @Test
    fun edit_removeDeletesKey() {
        val daybook = open()
        daybook.edit { putInt("key", 1) }
        daybook.edit { remove("key") }
        assertFalse(daybook.contains("key"))
    }

    @Test
    fun edit_emptyBlockWritesNothing() {
        val daybook = open()
        daybook.edit {}
        assertFalse(daybook.contains("anything"))
    }
}
