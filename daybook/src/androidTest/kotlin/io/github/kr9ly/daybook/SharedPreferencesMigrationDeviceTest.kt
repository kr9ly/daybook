package io.github.kr9ly.daybook

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kr9ly.daybook.kv.DaybookSchema
import io.github.kr9ly.daybook.kv.MigrationException
import io.github.kr9ly.daybook.kv.MigrationMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/**
 * マイグレーションの実機検証（実 framework SharedPreferences → daybook）。
 *
 * JVM テスト（Robolectric）の SharedPreferences は再実装のため、実 framework の
 * XML 永続化・型保持・getAll の実挙動と組み合わせた取り込みはここでしか検証できない。
 * 対象は SharedPreferencesMigrationSource（スキーマ写像・冪等マーカー・モード）と
 * 1.x 互換の透過/明示 import。
 */
@RunWith(AndroidJUnit4::class)
class SharedPreferencesMigrationDeviceTest {

    private class SettingsSchema(name: String) : DaybookSchema(name) {
        val darkMode = boolean("dark_mode")
        val count = int("count")
        val total = long("total")
        val ratio = float("ratio")
        val label = string("label")
        val tags = stringSet("tags")
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val createdPrefsNames = mutableListOf<String>()

    @After
    fun tearDown() {
        createdPrefsNames.forEach { name ->
            context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
        DaybookPreferencesCache.resetForTesting()
    }

    private fun uniqueName(prefix: String) = "$prefix-${System.nanoTime()}"

    private fun sourceFile(prefix: String): SharedPreferencesFile {
        val name = uniqueName(prefix)
        createdPrefsNames += name
        return SharedPreferencesFile(name)
    }

    private fun realPrefs(file: SharedPreferencesFile) =
        context.getSharedPreferences(file.fileName, android.content.Context.MODE_PRIVATE)

    // --- スキーマ写像: 全 6 型のラウンドトリップ ---

    @Test
    fun schemaMigration_fullTypeRoundTrip_fromRealPreferences() {
        val legacy = sourceFile("legacy")
        realPrefs(legacy).edit()
            .putBoolean("src_dark", true)
            .putInt("src_count", 7)
            .putLong("src_total", 42L)
            .putFloat("src_ratio", 1.5f)
            .putString("src_label", "value")
            .putStringSet("src_tags", setOf("a", "b"))
            .commit()

        val schema = SettingsSchema(uniqueName("roundtrip"))
        val daybook = context.openDaybook(schema) {
            migrations = listOf(
                SharedPreferencesMigrationSource(context) {
                    migrate(legacy.boolean("src_dark"), into = schema.darkMode)
                    migrate(legacy.int("src_count"), into = schema.count)
                    migrate(legacy.long("src_total"), into = schema.total)
                    migrate(legacy.float("src_ratio"), into = schema.ratio)
                    migrate(legacy.string("src_label"), into = schema.label)
                    migrate(legacy.stringSet("src_tags"), into = schema.tags)
                },
            )
        }

        assertEquals(true, daybook.getBoolean("dark_mode", false))
        assertEquals(7, daybook.getInt("count", 0))
        assertEquals(42L, daybook.getLong("total", 0))
        assertEquals(1.5f, daybook.getFloat("ratio", 0f), 0f)
        assertEquals("value", daybook.getString("label", null))
        assertEquals(setOf("a", "b"), daybook.getStringSet("tags", null))
    }

    // --- 冪等マーカー: 実ファイルシステム上で再オープンしても再取り込みされない ---

    @Test
    fun schemaMigration_runsOnceAcrossSimulatedRestart() {
        val legacy = sourceFile("legacy")
        realPrefs(legacy).edit().putString("src_label", "original").commit()

        val schema = SettingsSchema(uniqueName("idempotent"))
        fun source() = SharedPreferencesMigrationSource(context) {
            migrate(legacy.string("src_label"), into = schema.label)
        }

        context.openDaybook(schema) { migrations = listOf(source()) }
            .edit { putString("label", "edited") }

        DaybookPreferencesCache.resetForTesting() // プロセス再起動を模す
        realPrefs(legacy).edit().putString("src_label", "changed later").commit()
        val reopened = context.openDaybook(schema) { migrations = listOf(source()) }

        assertEquals("edited", reopened.getString("label", null))
    }

    // --- モード: 実 prefs 上の型不一致 ---

    @Test
    fun schemaMigration_strictTypeMismatch_failsOpen() {
        val legacy = sourceFile("legacy")
        realPrefs(legacy).edit().putInt("src_label", 123).commit()

        val schema = SettingsSchema(uniqueName("strict"))
        assertThrows(MigrationException::class.java) {
            context.openDaybook(schema) {
                migrations = listOf(
                    SharedPreferencesMigrationSource(context, mode = MigrationMode.STRICT) {
                        migrate(legacy.string("src_label"), into = schema.label)
                    },
                )
            }
        }
    }

    @Test
    fun schemaMigration_lenientTypeMismatch_skipsAndCompletes() {
        val legacy = sourceFile("legacy")
        realPrefs(legacy).edit()
            .putInt("src_label", 123)
            .putBoolean("src_dark", true)
            .commit()

        val schema = SettingsSchema(uniqueName("lenient"))
        val skips = mutableListOf<SharedPreferencesMigrationSkip>()
        val daybook = context.openDaybook(schema) {
            migrations = listOf(
                SharedPreferencesMigrationSource(context, mode = MigrationMode.LENIENT) {
                    onSkipped = { skips += it }
                    migrate(legacy.string("src_label"), into = schema.label)
                    migrate(legacy.boolean("src_dark"), into = schema.darkMode)
                },
            )
        }

        assertFalse(daybook.contains("label"))
        assertEquals(true, daybook.getBoolean("dark_mode", false))
        assertEquals(1, skips.size)
        assertEquals("src_label", skips.single().key)
    }

    // --- 1.x 互換: 透過取り込み（importFromSharedPreferences フラグ） ---

    @Test
    fun transparentImport_fromRealPreferences_isIdempotentAcrossRestart() {
        val name = uniqueName("transparent")
        createdPrefsNames += name
        context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE).edit()
            .putString("greeting", "hello")
            .putInt("count", 3)
            .commit()

        val prefs = context.getDaybookSharedPreferences(
            name,
            DaybookOptions(importFromSharedPreferences = true),
        )
        assertEquals("hello", prefs.getString("greeting", null))
        assertEquals(3, prefs.getInt("count", 0))

        prefs.edit().putString("greeting", "edited").commit()

        DaybookPreferencesCache.resetForTesting() // プロセス再起動を模す
        context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE).edit()
            .putString("greeting", "changed later")
            .commit()
        val reopened = context.getDaybookSharedPreferences(
            name,
            DaybookOptions(importFromSharedPreferences = true),
        )

        assertEquals("edited", reopened.getString("greeting", null))
    }

    // --- 1.x 互換: 明示 import ---

    @Test
    fun explicitImport_fromRealPreferences_importsAllKeys() {
        val name = uniqueName("explicit")
        createdPrefsNames += name
        context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE).edit()
            .putString("k1", "v1")
            .putBoolean("k2", true)
            .commit()

        val prefs = context.getDaybookSharedPreferences(name)
        assertFalse(prefs.contains("k1"))

        context.importSharedPreferencesIntoDaybook(name)

        assertEquals("v1", prefs.getString("k1", null))
        assertEquals(true, prefs.getBoolean("k2", false))
    }
}
