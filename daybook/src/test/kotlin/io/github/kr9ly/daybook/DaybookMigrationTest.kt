package io.github.kr9ly.daybook

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** framework SharedPreferences との相互マイグレーション（import / export / 透過取り込み）のテスト。 */
@RunWith(RobolectricTestRunner::class)
class DaybookMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        DaybookPreferencesCache.resetForTesting()
    }

    private fun seedFrameworkPrefs(name: String, vararg pairs: Pair<String, Any>) {
        val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
        pairs.forEach { (key, value) -> DaybookMigration.putInto(editor, key, value) }
        assertTrue(editor.commit())
    }

    // --- import（明示） ---

    @Test
    fun import_copiesAllSupportedTypes_once() {
        seedFrameworkPrefs(
            "settings",
            "string" to "value",
            "int" to 7,
            "long" to 7L,
            "float" to 2.5f,
            "boolean" to true,
            "set" to setOf("a", "b"),
        )

        assertTrue(context.importSharedPreferencesIntoDaybook("settings"))
        val prefs = context.getDaybookSharedPreferences("settings")
        assertEquals("value", prefs.getString("string", null))
        assertEquals(7, prefs.getInt("int", 0))
        assertEquals(7L, prefs.getLong("long", 0L))
        assertEquals(2.5f, prefs.getFloat("float", 0f))
        assertTrue(prefs.getBoolean("boolean", false))
        assertEquals(setOf("a", "b"), prefs.getStringSet("set", null))
        assertTrue(File(context.filesDir, "daybook/settings.imported").exists())

        // 2 回目は走らない: daybook 側の編集が再取り込みで巻き戻らない
        prefs.edit().putString("string", "edited").commit()
        assertFalse(context.importSharedPreferencesIntoDaybook("settings"))
        assertEquals("edited", prefs.getString("string", null))
    }

    @Test
    fun import_keepsSourceByDefault() {
        seedFrameworkPrefs("settings", "key" to "value")
        context.importSharedPreferencesIntoDaybook("settings")
        assertEquals(
            "value",
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("key", null),
        )
    }

    @Test
    fun import_withDeleteSource_clearsFrameworkPrefs() {
        seedFrameworkPrefs("settings", "key" to "value")
        context.importSharedPreferencesIntoDaybook("settings", deleteSource = true)
        assertEquals(
            emptyMap<String, Any?>(),
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).all,
        )
        // daybook 側には取り込み済み
        assertEquals("value", context.getDaybookSharedPreferences("settings").getString("key", null))
    }

    @Test
    fun import_intoOpenStore_overwritesSameKeysAndKeepsOthers() {
        // 先に daybook 側を開いて書く（キャッシュ済みストア経由の import 経路）
        val prefs = context.getDaybookSharedPreferences("settings")
        prefs.edit().putString("shared", "daybook").putString("daybookOnly", "kept").commit()

        seedFrameworkPrefs("settings", "shared" to "framework", "frameworkOnly" to "imported")
        assertTrue(context.importSharedPreferencesIntoDaybook("settings"))

        assertEquals(
            mapOf<String, Any?>(
                "shared" to "framework", // 同名キーは framework 値で上書き
                "daybookOnly" to "kept", // daybook 固有キーは残る
                "frameworkOnly" to "imported",
            ),
            prefs.all,
        )
    }

    @Test
    fun import_ofEmptyOrMissingFrameworkPrefs_stillMarksDone() {
        assertTrue(context.importSharedPreferencesIntoDaybook("nonexistent"))
        assertFalse(context.importSharedPreferencesIntoDaybook("nonexistent"))
        assertEquals(emptyMap<String, Any?>(), context.getDaybookSharedPreferences("nonexistent").all)
    }

    // --- import（透過） ---

    @Test
    fun transparentImport_runsOnFirstCreationOnly() {
        seedFrameworkPrefs("settings", "key" to "value")

        val prefs = context.getDaybookSharedPreferences("settings", importFromSharedPreferences = true)
        assertEquals("value", prefs.getString("key", null))

        // プロセス再起動を模す: キャッシュ破棄 → フラグつき再オープンでも再取り込みされない
        prefs.edit().putString("key", "edited").commit()
        DaybookPreferencesCache.resetForTesting()
        val reopened = context.getDaybookSharedPreferences("settings", importFromSharedPreferences = true)
        assertEquals("edited", reopened.getString("key", null))
    }

    @Test
    fun transparentImport_isIgnoredOnCacheHit() {
        // 取り込みはインスタンス生成時のみ: 生成後の編集をフラグつき再取得が上書きしない
        val prefs = context.getDaybookSharedPreferences("settings")
        seedFrameworkPrefs("settings", "key" to "framework")
        val second = context.getDaybookSharedPreferences("settings", importFromSharedPreferences = true)
        assertEquals(prefs, second)
        assertFalse(second.contains("key"))
    }

    // --- import（一括） ---

    @Test
    fun importAll_importsEveryFrameworkPrefsOnce() {
        seedFrameworkPrefs("alpha", "a" to 1)
        seedFrameworkPrefs("beta", "b" to 2)
        // 列挙が .xml 以外を無視すること
        File(File(context.filesDir.parentFile, "shared_prefs"), "junk.txt").writeText("junk")

        assertEquals(listOf("alpha", "beta"), context.importAllSharedPreferencesIntoDaybook())
        assertEquals(1, context.getDaybookSharedPreferences("alpha").getInt("a", 0))
        assertEquals(2, context.getDaybookSharedPreferences("beta").getInt("b", 0))

        // 全て取り込み済みなら空。後から現れた prefs だけ拾う
        assertEquals(emptyList<String>(), context.importAllSharedPreferencesIntoDaybook())
        seedFrameworkPrefs("gamma", "c" to 3)
        assertEquals(listOf("gamma"), context.importAllSharedPreferencesIntoDaybook())
    }

    @Test
    fun importAll_withoutSharedPrefsDirectory_returnsEmpty() {
        assertEquals(emptyList<String>(), context.importAllSharedPreferencesIntoDaybook())
    }

    // --- export ---

    @Test
    fun export_replicatesDaybookStateExactly() {
        // framework 側の古いキーは export で消える（複製であってマージではない）
        seedFrameworkPrefs("settings", "stale" to "old")
        context.getDaybookSharedPreferences("settings").edit()
            .putString("string", "value")
            .putInt("int", 7)
            .putLong("long", 7L)
            .putFloat("float", 2.5f)
            .putBoolean("boolean", true)
            .putStringSet("set", setOf("a"))
            .commit()

        context.exportDaybookToSharedPreferences("settings")

        assertEquals(
            mapOf<String, Any?>(
                "string" to "value",
                "int" to 7,
                "long" to 7L,
                "float" to 2.5f,
                "boolean" to true,
                "set" to setOf("a"),
            ),
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).all,
        )
    }

    @Test
    fun exportAll_findsClosedStoresAndIgnoresStrayFiles() {
        context.getDaybookSharedPreferences("alpha").edit().putInt("a", 1).commit()
        context.getDaybookSharedPreferences("beta").edit().putInt("b", 2).commit()
        DaybookPreferencesCache.resetForTesting() // クローズ済みストアもディレクトリ走査で見つかる

        // 世代ファイルの形式に合わないファイルは列挙されない
        val dir = DaybookPreferencesCache.daybookDir(context)
        File(dir, "junk.txt").writeText("junk")
        File(dir, "nogeneration.journal").writeText("x")
        File(dir, "notanumber.abc.journal").writeText("x")
        File(dir, "zero.0.journal").writeText("x")
        File(dir, ".1.journal").writeText("x")

        assertEquals(listOf("alpha", "beta"), context.exportAllDaybookToSharedPreferences())
        assertEquals(1, context.getSharedPreferences("alpha", Context.MODE_PRIVATE).getInt("a", 0))
        assertEquals(2, context.getSharedPreferences("beta", Context.MODE_PRIVATE).getInt("b", 0))
    }

    @Test
    fun exportAll_withoutDaybookDirectory_returnsEmpty() {
        assertEquals(emptyList<String>(), context.exportAllDaybookToSharedPreferences())
    }

    // --- 値型の検査 ---

    @Test
    fun putInto_rejectsUnsupportedValueType() {
        val editor = context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
        assertThrows(IllegalArgumentException::class.java) {
            DaybookMigration.putInto(editor, "key", 1.0) // Double は非対応
        }
    }
}
