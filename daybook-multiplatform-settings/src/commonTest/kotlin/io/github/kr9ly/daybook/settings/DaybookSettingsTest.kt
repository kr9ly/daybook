package io.github.kr9ly.daybook.settings

import io.github.kr9ly.daybook.kv.ChangeNotificationDelivery
import io.github.kr9ly.daybook.kv.Daybook
import io.github.kr9ly.daybook.kv.DaybookSchema
import io.github.kr9ly.daybook.kv.KvStore
import io.github.kr9ly.daybook.kv.asDaybook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [DaybookSettings] の契約テスト。
 *
 * ストアは in-memory + 同期配送（リスナーが書き込み呼び出しのスタック上で実行される）で
 * 組み立て、リスナー系のアサーションを待ち合わせなしの決定的な形にする。
 */
class DaybookSettingsTest {

    private object PlainSchema : DaybookSchema("test")

    private fun open(): Daybook =
        KvStore.openInMemory(delivery = ChangeNotificationDelivery { it() }).asDaybook(PlainSchema)

    private fun openSettings(): DaybookSettings = DaybookSettings(open())

    // --- 型付き put / get の契約 ---

    @Test
    fun putAndGet_roundTripsAllTypes() {
        val settings = openSettings()
        settings.putInt("int", 1)
        settings.putLong("long", 2L)
        settings.putString("string", "value")
        settings.putFloat("float", 3.5f)
        settings.putDouble("double", 4.5)
        settings.putBoolean("boolean", true)

        assertEquals(1, settings.getInt("int", 0))
        assertEquals(2L, settings.getLong("long", 0L))
        assertEquals("value", settings.getString("string", ""))
        assertEquals(3.5f, settings.getFloat("float", 0f))
        assertEquals(4.5, settings.getDouble("double", 0.0))
        assertTrue(settings.getBoolean("boolean", false))
    }

    @Test
    fun getters_returnDefaultsForAbsentKeys() {
        val settings = openSettings()
        assertEquals(7, settings.getInt("missing", 7))
        assertEquals(7L, settings.getLong("missing", 7L))
        assertEquals("fallback", settings.getString("missing", "fallback"))
        assertEquals(0.5f, settings.getFloat("missing", 0.5f))
        assertEquals(0.25, settings.getDouble("missing", 0.25))
        assertTrue(settings.getBoolean("missing", true))
    }

    @Test
    fun orNullGetters_returnNullForAbsentAndValueForPresent() {
        val settings = openSettings()
        assertNull(settings.getIntOrNull("missing"))
        assertNull(settings.getLongOrNull("missing"))
        assertNull(settings.getStringOrNull("missing"))
        assertNull(settings.getFloatOrNull("missing"))
        assertNull(settings.getDoubleOrNull("missing"))
        assertNull(settings.getBooleanOrNull("missing"))

        settings.putInt("int", 1)
        settings.putLong("long", 2L)
        settings.putString("string", "value")
        settings.putFloat("float", 3.5f)
        settings.putDouble("double", 4.5)
        settings.putBoolean("boolean", false)
        assertEquals(1, settings.getIntOrNull("int"))
        assertEquals(2L, settings.getLongOrNull("long"))
        assertEquals("value", settings.getStringOrNull("string"))
        assertEquals(3.5f, settings.getFloatOrNull("float"))
        assertEquals(4.5, settings.getDoubleOrNull("double"))
        assertEquals(false, settings.getBooleanOrNull("boolean"))
    }

    @Test
    fun getterWithWrongType_throwsClassCastException() {
        val settings = openSettings()
        settings.putString("key", "not an int")
        assertFailsWith<ClassCastException> { settings.getInt("key", 0) }
        assertFailsWith<ClassCastException> { settings.getIntOrNull("key") }
    }

    // --- keys / size / hasKey / remove / clear ---

    @Test
    fun keysAndSize_reflectStoredEntries() {
        val settings = openSettings()
        assertEquals(emptySet(), settings.keys)
        assertEquals(0, settings.size)

        settings.putInt("a", 1)
        settings.putString("b", "2")
        assertEquals(setOf("a", "b"), settings.keys)
        assertEquals(2, settings.size)
    }

    @Test
    fun stringSetKey_isVisibleInKeysButFailsFastOnTypedGetter() {
        val daybook = open()
        daybook.edit { putStringSet("set", setOf("x")) }
        val settings = DaybookSettings(daybook)

        // 列挙には見える（型互換ポリシー: 不可視化はしない）
        assertEquals(setOf("set"), settings.keys)
        assertEquals(1, settings.size)
        assertTrue(settings.hasKey("set"))
        // 型付きアクセスは fail-fast
        assertFailsWith<ClassCastException> { settings.getString("set", "") }
    }

    @Test
    fun hasKeyAndRemove_behaveLikeSettings() {
        val settings = openSettings()
        assertFalse(settings.hasKey("key"))
        settings.putInt("key", 1)
        assertTrue(settings.hasKey("key"))
        settings.remove("key")
        assertFalse(settings.hasKey("key"))
        // 不在キーの remove は無害
        settings.remove("key")
    }

    @Test
    fun clear_removesAllEntries() {
        val settings = openSettings()
        settings.putInt("a", 1)
        settings.putString("b", "2")
        settings.clear()
        assertEquals(emptySet(), settings.keys)
        assertNull(settings.getIntOrNull("a"))
    }

    // --- API 間のストア共有 ---

    @Test
    fun daybookApiAndSettingsAdapter_shareTheSameStore() {
        val daybook = open()
        val settings = DaybookSettings(daybook)

        daybook.edit { putInt("fromDaybook", 1) }
        assertEquals(1, settings.getInt("fromDaybook", 0))

        settings.putInt("fromSettings", 2)
        assertEquals(2, daybook.getInt("fromSettings", 0))
    }

    // --- リスナーの契約 ---

    @Test
    fun listener_firesWithNewValueOnChange() {
        val settings = openSettings()
        val received = mutableListOf<Int>()
        settings.addIntListener("key", -1) { received += it }

        settings.putInt("key", 1)
        settings.putInt("key", 2)
        assertEquals(listOf(1, 2), received)
    }

    @Test
    fun listener_dedupesSameValuePut() {
        val settings = openSettings()
        val received = mutableListOf<Int>()
        settings.addIntListener("key", -1) { received += it }

        settings.putInt("key", 1)
        settings.putInt("key", 1) // 同値 put は値変化ベースの契約では発火しない
        settings.putInt("key", 2)
        assertEquals(listOf(1, 2), received)
    }

    @Test
    fun listener_receivesDefaultValueOnRemoveAndClear() {
        val settings = openSettings()
        settings.putInt("key", 1)
        val received = mutableListOf<Int>()
        settings.addIntListener("key", -1) { received += it }

        settings.remove("key")
        settings.putInt("key", 2)
        settings.clear()
        assertEquals(listOf(-1, 2, -1), received)
    }

    @Test
    fun listener_doesNotFireForOtherKeys() {
        val settings = openSettings()
        val received = mutableListOf<Int>()
        settings.addIntListener("key", -1) { received += it }

        settings.putInt("other", 1)
        assertEquals(emptyList(), received)
    }

    @Test
    fun listener_doesNotFireOnRemoveOfAbsentKey() {
        val settings = openSettings()
        val received = mutableListOf<Int>()
        settings.addIntListener("key", -1) { received += it }

        settings.remove("key") // 不在 → 不在は値変化なし
        assertEquals(emptyList(), received)
    }

    @Test
    fun orNullListener_receivesNullOnRemove() {
        val settings = openSettings()
        settings.putString("key", "value")
        val received = mutableListOf<String?>()
        settings.addStringOrNullListener("key") { received += it }

        settings.putString("key", "next")
        settings.remove("key")
        assertEquals(listOf("next", null), received)
    }

    @Test
    fun listener_stopsAfterDeactivate() {
        val settings = openSettings()
        val received = mutableListOf<Int>()
        val listener = settings.addIntListener("key", -1) { received += it }

        settings.putInt("key", 1)
        listener.deactivate()
        settings.putInt("key", 2)
        assertEquals(listOf(1), received)
    }

    @Test
    fun listenerRegistrationOnWrongTypedKey_throwsClassCastException() {
        val settings = openSettings()
        settings.putString("key", "not an int")
        // 登録時の現在値捕捉は呼び出し側のスタック上のため fail-fast にできる
        assertFailsWith<ClassCastException> {
            settings.addIntListener("key", -1) {}
        }
    }

    @Test
    fun listener_skipsWrongTypedValuesAndResumesOnValidValue() {
        val daybook = open()
        val settings = DaybookSettings(daybook)
        val received = mutableListOf<Int>()
        settings.addIntListener("key", -1) { received += it }

        daybook.edit { putString("key", "wrong type") } // 型不一致は配送されない
        settings.putInt("key", 1)
        assertEquals(listOf(1), received)
    }

    @Test
    fun listeners_coverAllTypedVariants() {
        val settings = openSettings()
        val received = mutableListOf<Any?>()
        settings.addLongListener("long", -1L) { received += it }
        settings.addStringListener("string", "default") { received += it }
        settings.addFloatListener("float", -1f) { received += it }
        settings.addDoubleListener("double", -1.0) { received += it }
        settings.addBooleanListener("boolean", false) { received += it }

        settings.putLong("long", 1L)
        settings.putString("string", "value")
        settings.putFloat("float", 2.5f)
        settings.putDouble("double", 3.5)
        settings.putBoolean("boolean", true)
        assertEquals(listOf<Any?>(1L, "value", 2.5f, 3.5, true), received)
    }

    @Test
    fun orNullListeners_coverAllTypedVariants() {
        val settings = openSettings()
        val received = mutableListOf<Any?>()
        settings.addIntOrNullListener("int") { received += it }
        settings.addLongOrNullListener("long") { received += it }
        settings.addFloatOrNullListener("float") { received += it }
        settings.addDoubleOrNullListener("double") { received += it }
        settings.addBooleanOrNullListener("boolean") { received += it }

        settings.putInt("int", 1)
        settings.putLong("long", 2L)
        settings.putFloat("float", 2.5f)
        settings.putDouble("double", 3.5)
        settings.putBoolean("boolean", true)
        assertEquals(listOf<Any?>(1, 2L, 2.5f, 3.5, true), received)
    }
}
