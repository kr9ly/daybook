package io.github.kr9ly.daybook

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 型安全層（PreferenceProperty + ファクトリ拡張）のテスト。 */
@RunWith(RobolectricTestRunner::class)
class PreferencePropertyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs: SharedPreferences = context.getDaybookSharedPreferences("typed")

    @After
    fun tearDown() {
        DaybookPreferencesCache.resetForTesting()
    }

    // --- 全型の get / set ---

    @Test
    fun allTypes_returnDefaultWhenAbsent_andRoundTrip() {
        val boolean = prefs.boolean("boolean", default = true)
        val int = prefs.int("int", default = 7)
        val long = prefs.long("long", default = 7L)
        val float = prefs.float("float", default = 1.5f)
        val string = prefs.string("string", default = "def")
        val set = prefs.stringSet("set", default = setOf("def"))

        assertTrue(boolean.get())
        assertEquals(7, int.get())
        assertEquals(7L, long.get())
        assertEquals(1.5f, float.get())
        assertEquals("def", string.get())
        assertEquals(setOf("def"), set.get())

        boolean.set(false)
        int.set(1)
        long.set(2L)
        float.set(3.5f)
        string.set("value")
        set.set(setOf("a", "b"))

        assertFalse(boolean.get())
        assertEquals(1, int.get())
        assertEquals(2L, long.get())
        assertEquals(3.5f, float.get())
        assertEquals("value", string.get())
        assertEquals(setOf("a", "b"), set.get())
    }

    @Test
    fun nullableVariants_readNullWhenAbsent_andRemoveOnNullSet() {
        val nickname = prefs.string("nickname")
        val tags = prefs.stringSet("tags")

        assertNull(nickname.get())
        assertNull(tags.get())

        nickname.set("kr9ly")
        tags.set(setOf("a"))
        assertEquals("kr9ly", nickname.get())
        assertEquals(setOf("a"), tags.get())

        // null の set はキーの削除（putString(key, null) = remove の互換契約に乗る）
        nickname.set(null)
        tags.set(null)
        assertFalse(prefs.contains("nickname"))
        assertFalse(prefs.contains("tags"))
    }

    // --- デリゲートとしての使用 ---

    private class Settings(prefs: SharedPreferences) {
        var darkMode by prefs.boolean("dark_mode", default = false)

        val fontScalePref = prefs.float("font_scale", default = 1.0f)
        var fontScale by fontScalePref
    }

    @Test
    fun delegatedProperties_readAndWriteThroughPreferences() {
        val settings = Settings(prefs)
        assertFalse(settings.darkMode)
        settings.darkMode = true
        assertTrue(settings.darkMode)
        // デリゲートの書き込みは通常の SharedPreferences 経由でも見える
        assertTrue(prefs.getBoolean("dark_mode", false))

        assertEquals(1.0f, settings.fontScale)
        settings.fontScale = 2.0f
        assertEquals(2.0f, settings.fontScalePref.get())
    }

    @Test
    fun propertyExposesKeyAndPreferences() {
        // asFlow() などの外部連携が使う公開面
        val property = prefs.int("count", default = 0)
        assertEquals("count", property.key)
        assertSame(prefs, property.preferences)
    }

    // --- map（境界での値変換） ---

    private enum class Theme { SYSTEM, DARK }

    @Test
    fun map_convertsOnReadAndWrite() {
        val theme = prefs.string("theme", default = Theme.SYSTEM.name)
            .map(decode = Theme::valueOf, encode = Theme::name)

        assertEquals(Theme.SYSTEM, theme.get()) // 不在 → 格納側デフォルトを decode
        theme.set(Theme.DARK)
        assertEquals(Theme.DARK, theme.get())
        // 格納表現は変換前の型（素の互換 API から見える）
        assertEquals("DARK", prefs.getString("theme", null))
        // key / preferences は合成後も引き継がれる（asFlow の前提）
        assertEquals("theme", theme.key)
        assertSame(prefs, theme.preferences)
    }

    @Test
    fun map_worksAsDelegate() {
        var theme by prefs.string("theme", default = Theme.SYSTEM.name)
            .map(decode = Theme::valueOf, encode = Theme::name)
        assertEquals(Theme.SYSTEM, theme)
        theme = Theme.DARK
        assertEquals(Theme.DARK, theme)
    }

    @Test
    fun map_composesWithNullableBase() {
        // S = String? のまま型が流れる。null の扱いは decode/encode のシグネチャに現れる
        val port = prefs.string("port").map(
            decode = { it?.toInt() },
            encode = { it?.toString() },
        )
        assertNull(port.get())
        port.set(8080)
        assertEquals(8080, port.get())
        port.set(null) // null の代入はキー削除（base の契約が透過する）
        assertFalse(prefs.contains("port"))
    }

    @Test
    fun catch_recoversFromDecodeFailure() {
        prefs.edit().putString("theme", "NO_SUCH_THEME").commit()
        val theme = prefs.string("theme", default = Theme.SYSTEM.name)
            .map(decode = Theme::valueOf, encode = Theme::name)
            .catch { Theme.SYSTEM }

        assertEquals(Theme.SYSTEM, theme.get()) // 壊れた格納値はフォールバック
        theme.set(Theme.DARK)
        assertEquals(Theme.DARK, theme.get()) // 正常経路は素通し（catch は読み取りだけを包む）
    }

    @Test
    fun map_decodeFailure_propagatesAsIs() {
        // ポリシーレス: 変換の失敗は握りつぶさず呼び出し側に返す（fail-fast の系譜）
        prefs.edit().putString("theme", "NO_SUCH_THEME").commit()
        val theme = prefs.string("theme", default = Theme.SYSTEM.name)
            .map(decode = Theme::valueOf, encode = Theme::name)
        assertThrows(IllegalArgumentException::class.java) {
            theme.get()
        }
    }

    @Test
    fun worksAgainstFrameworkSharedPreferences() {
        // daybook 非依存: framework 実装の上でも同じに動く（移行前から導入できる）
        val framework = context.getSharedPreferences("framework", Context.MODE_PRIVATE)
        val count = framework.int("count", default = 0)
        assertEquals(0, count.get())
        count.set(42)
        assertEquals(42, framework.getInt("count", 0))
    }
}
