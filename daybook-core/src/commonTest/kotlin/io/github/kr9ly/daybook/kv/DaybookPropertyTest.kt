package io.github.kr9ly.daybook.kv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [DaybookProperty] の契約テスト。1.x PreferenceProperty と同じ契約を common で検証する。 */
class DaybookPropertyTest {

    private fun open(): Daybook = KvStore.openInMemory().asDaybook()

    // --- ファクトリと get/set ---

    @Test
    fun primitiveProperties_roundTrip() {
        val daybook = open()
        val boolean = daybook.boolean("boolean", default = false)
        val int = daybook.int("int", default = 0)
        val long = daybook.long("long", default = 0L)
        val float = daybook.float("float", default = 0f)
        val double = daybook.double("double", default = 0.0)

        // 不在は宣言時のデフォルト
        assertFalse(boolean.get())
        assertEquals(0, int.get())
        assertEquals(0L, long.get())
        assertEquals(0f, float.get())
        assertEquals(0.0, double.get())

        boolean.set(true)
        int.set(1)
        long.set(2L)
        float.set(3.5f)
        double.set(4.5)

        assertTrue(boolean.get())
        assertEquals(1, int.get())
        assertEquals(2L, long.get())
        assertEquals(3.5f, float.get())
        assertEquals(4.5, double.get())
    }

    @Test
    fun stringProperty_withDefault() {
        val daybook = open()
        val property = daybook.string("key", default = "fallback")
        assertEquals("fallback", property.get())
        property.set("value")
        assertEquals("value", property.get())
    }

    @Test
    fun nullableStringProperty_nullMeansAbsentAndRemoval() {
        val daybook = open()
        val property = daybook.string("key")
        assertNull(property.get())
        property.set("value")
        assertEquals("value", property.get())
        property.set(null)
        assertNull(property.get())
        assertFalse(daybook.contains("key"))
    }

    @Test
    fun stringSetProperty_withDefault_copiesDefaultAtDeclaration() {
        val daybook = open()
        val default = mutableSetOf("a")
        val property = daybook.stringSet("key", default)
        default.add("late") // 宣言後の変更はデフォルトに影響しない
        assertEquals(setOf("a"), property.get())
        property.set(setOf("b"))
        assertEquals(setOf("b"), property.get())
    }

    @Test
    fun nullableStringSetProperty_nullMeansAbsentAndRemoval() {
        val daybook = open()
        val property = daybook.stringSet("key")
        assertNull(property.get())
        property.set(setOf("a"))
        assertEquals(setOf("a"), property.get())
        property.set(null)
        assertNull(property.get())
        assertFalse(daybook.contains("key"))
    }

    // --- デリゲート ---

    @Test
    fun delegate_readsAndWritesThroughProperty() {
        val daybook = open()
        var darkMode by daybook.boolean("dark_mode", default = false)
        assertFalse(darkMode)
        darkMode = true
        assertTrue(darkMode)
        assertTrue(daybook.getBoolean("dark_mode", false))
    }

    // --- map / catch ---

    private enum class Theme { SYSTEM, DARK }

    @Test
    fun map_convertsAtBoundary() {
        val daybook = open()
        val property = daybook.string("theme", default = Theme.SYSTEM.name)
            .map(decode = Theme::valueOf, encode = Theme::name)
        assertEquals(Theme.SYSTEM, property.get())
        property.set(Theme.DARK)
        assertEquals(Theme.DARK, property.get())
        assertEquals("DARK", daybook.getString("theme", null))
    }

    @Test
    fun map_decodeFailurePropagates() {
        val daybook = open()
        daybook.edit { putString("theme", "no-longer-a-theme") }
        val property = daybook.string("theme", default = Theme.SYSTEM.name)
            .map(decode = Theme::valueOf, encode = Theme::name)
        assertFailsWith<IllegalArgumentException> {
            property.get()
        }
    }

    @Test
    fun catch_recoversReadFailures() {
        val daybook = open()
        daybook.edit { putString("theme", "no-longer-a-theme") }
        val property = daybook.string("theme", default = Theme.SYSTEM.name)
            .map(decode = Theme::valueOf, encode = Theme::name)
            .catch { Theme.SYSTEM }
        assertEquals(Theme.SYSTEM, property.get())
    }

    @Test
    fun catch_recoversTypeMismatch() {
        val daybook = open()
        daybook.edit { putString("key", "not an int") }
        val property = daybook.int("key", default = 0).catch { -1 }
        assertEquals(-1, property.get())
    }

    @Test
    fun catch_doesNotInterceptWrites() {
        val daybook = open()
        val property = daybook.string("theme", default = Theme.SYSTEM.name)
            .map(decode = Theme::valueOf, encode = { throw IllegalStateException("encode failure") })
            .catch { Theme.SYSTEM }
        assertFailsWith<IllegalStateException> {
            property.set(Theme.DARK)
        }
    }

    @Test
    fun keyAndStore_areExposed() {
        val daybook = open()
        val property = daybook.int("key", default = 0)
        assertEquals("key", property.key)
        assertEquals(daybook, property.daybook)
    }
}
