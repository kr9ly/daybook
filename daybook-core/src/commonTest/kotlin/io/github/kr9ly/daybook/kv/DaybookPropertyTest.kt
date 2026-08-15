package io.github.kr9ly.daybook.kv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [DaybookProperty] の契約テスト。1.x PreferenceProperty と同じ契約を common で検証する。 */
class DaybookPropertyTest {

    private object Schema : DaybookSchema("test") {
        val boolean = boolean("boolean")
        val int = int("int")
        val long = long("long")
        val float = float("float")
        val double = double("double")
        val string = string("string")
        val stringSet = stringSet("string_set")
        val theme = string("theme")
        val darkMode = boolean("dark_mode")
    }

    private fun open(): Daybook = KvStore.openInMemory().asDaybook(Schema)

    // --- ファクトリと get/set ---

    @Test
    fun primitiveProperties_roundTrip() {
        val daybook = open()
        val boolean = daybook.property(Schema.boolean, default = false)
        val int = daybook.property(Schema.int, default = 0)
        val long = daybook.property(Schema.long, default = 0L)
        val float = daybook.property(Schema.float, default = 0f)
        val double = daybook.property(Schema.double, default = 0.0)

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
        val property = daybook.property(Schema.string, default = "fallback")
        assertEquals("fallback", property.get())
        property.set("value")
        assertEquals("value", property.get())
    }

    @Test
    fun nullableStringProperty_nullMeansAbsentAndRemoval() {
        val daybook = open()
        val property = daybook.property(Schema.string)
        assertNull(property.get())
        property.set("value")
        assertEquals("value", property.get())
        property.set(null)
        assertNull(property.get())
        assertFalse(daybook.contains("string"))
    }

    @Test
    fun stringSetProperty_withDefault_copiesDefaultAtDeclaration() {
        val daybook = open()
        val default = mutableSetOf("a")
        val property = daybook.property(Schema.stringSet, default)
        default.add("late") // 宣言後の変更はデフォルトに影響しない
        assertEquals(setOf("a"), property.get())
        property.set(setOf("b"))
        assertEquals(setOf("b"), property.get())
    }

    @Test
    fun nullableStringSetProperty_nullMeansAbsentAndRemoval() {
        val daybook = open()
        val property = daybook.property(Schema.stringSet)
        assertNull(property.get())
        property.set(setOf("a"))
        assertEquals(setOf("a"), property.get())
        property.set(null)
        assertNull(property.get())
        assertFalse(daybook.contains("string_set"))
    }

    // --- デリゲート ---

    @Test
    fun delegate_readsAndWritesThroughProperty() {
        val daybook = open()
        var darkMode by daybook.property(Schema.darkMode, default = false)
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
        val property = daybook.property(Schema.theme, default = Theme.SYSTEM.name)
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
        val property = daybook.property(Schema.theme, default = Theme.SYSTEM.name)
            .map(decode = Theme::valueOf, encode = Theme::name)
        assertFailsWith<IllegalArgumentException> {
            property.get()
        }
    }

    @Test
    fun catch_recoversReadFailures() {
        val daybook = open()
        daybook.edit { putString("theme", "no-longer-a-theme") }
        val property = daybook.property(Schema.theme, default = Theme.SYSTEM.name)
            .map(decode = Theme::valueOf, encode = Theme::name)
            .catch { Theme.SYSTEM }
        assertEquals(Theme.SYSTEM, property.get())
    }

    @Test
    fun catch_recoversTypeMismatch() {
        val daybook = open()
        daybook.edit { putString("int", "not an int") }
        val property = daybook.property(Schema.int, default = 0).catch { -1 }
        assertEquals(-1, property.get())
    }

    @Test
    fun catch_doesNotInterceptWrites() {
        val daybook = open()
        val property = daybook.property(Schema.theme, default = Theme.SYSTEM.name)
            .map(decode = Theme::valueOf, encode = { throw IllegalStateException("encode failure") })
            .catch { Theme.SYSTEM }
        assertFailsWith<IllegalStateException> {
            property.set(Theme.DARK)
        }
    }

    @Test
    fun keyAndStore_areExposed() {
        val daybook = open()
        val property = daybook.property(Schema.int, default = 0)
        assertEquals("int", property.key)
        assertEquals(daybook, property.daybook)
    }

    // --- ストア束縛のランタイム検査 ---

    private object OtherSchema : DaybookSchema("other") {
        val flag = boolean("flag")
        val name = string("name")
        val tags = stringSet("tags")
    }

    @Test
    fun property_rejectsKeyFromAnotherSchema() {
        val daybook = open()
        assertFailsWith<IllegalArgumentException> {
            daybook.property(OtherSchema.flag, default = false)
        }
    }

    @Test
    fun nullableProperties_rejectKeyFromAnotherSchema() {
        val daybook = open()
        assertFailsWith<IllegalArgumentException> {
            daybook.property(OtherSchema.name)
        }
        assertFailsWith<IllegalArgumentException> {
            daybook.property(OtherSchema.tags)
        }
    }
}
