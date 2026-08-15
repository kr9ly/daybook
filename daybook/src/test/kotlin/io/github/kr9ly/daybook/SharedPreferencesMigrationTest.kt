package io.github.kr9ly.daybook

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kr9ly.daybook.kv.DaybookSchema
import io.github.kr9ly.daybook.kv.MigrationException
import io.github.kr9ly.daybook.kv.MigrationMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [SharedPreferencesMigrationSource] の契約テスト — 型付き写像・複数ファイル集約・
 * 全キー import・モードの意味論（宣言の矛盾は常に即例外 / ソースデータの問題だけが
 * モードの対象 / 欠損は正常系スキップ）・冪等マーカー。
 */
@RunWith(RobolectricTestRunner::class)
class SharedPreferencesMigrationTest {

    private object Settings : DaybookSchema("settings") {
        val darkMode = boolean("dark_mode")
        val count = long("count")
        val label = string("label")
        val tags = stringSet("tags")
    }

    private object OtherSchema : DaybookSchema("settings") {
        val foreign = boolean("foreign")
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val appPrefs = SharedPreferencesFile("app_prefs")
    private val legacyPrefs = SharedPreferencesFile("legacy_prefs")

    @After
    fun tearDown() {
        DaybookPreferencesCache.resetForTesting()
    }

    private fun prefs(file: SharedPreferencesFile) =
        context.getSharedPreferences(file.fileName, Context.MODE_PRIVATE)

    // --- 写像と集約 ---

    @Test
    fun migrate_mapsDeclaredKeysAcrossFiles() {
        prefs(appPrefs).edit().putBoolean("dark_mode_v1", true).putString("ignored", "x").commit()
        prefs(legacyPrefs).edit().putLong("counter", 42L).commit()

        val daybook = context.openDaybook(Settings) {
            migrations = listOf(
                SharedPreferencesMigrationSource(context) {
                    migrate(appPrefs.boolean("dark_mode_v1"), into = Settings.darkMode)
                    migrate(legacyPrefs.long("counter"), into = Settings.count)
                },
            )
        }

        assertEquals(true, daybook.getBoolean("dark_mode", false))
        assertEquals(42L, daybook.getLong("count", 0))
        assertFalse(daybook.contains("ignored")) // 宣言していないキーは取り込まれない
    }

    @Test
    fun migrate_supportsAllSourceTypes() {
        prefs(appPrefs).edit()
            .putBoolean("b", true)
            .putInt("i", 7)
            .putLong("l", 1L shl 40)
            .putFloat("f", 1.5f)
            .putString("s", "value")
            .putStringSet("set", setOf("a", "b"))
            .commit()
        val schema = object : DaybookSchema("all-types") {
            val b = boolean("b2")
            val i = int("i2")
            val l = long("l2")
            val f = float("f2")
            val s = string("s2")
            val set = stringSet("set2")
        }

        val daybook = context.openDaybook(schema) {
            migrations = listOf(
                SharedPreferencesMigrationSource(context) {
                    migrate(appPrefs.boolean("b"), into = schema.b)
                    migrate(appPrefs.int("i"), into = schema.i)
                    migrate(appPrefs.long("l"), into = schema.l)
                    migrate(appPrefs.float("f"), into = schema.f)
                    migrate(appPrefs.string("s"), into = schema.s)
                    migrate(appPrefs.stringSet("set"), into = schema.set)
                },
            )
        }

        assertEquals(true, daybook.getBoolean("b2", false))
        assertEquals(7, daybook.getInt("i2", 0))
        assertEquals(1L shl 40, daybook.getLong("l2", 0))
        assertEquals(1.5f, daybook.getFloat("f2", 0f), 0f)
        assertEquals("value", daybook.getString("s2", null))
        assertEquals(setOf("a", "b"), daybook.getStringSet("set2", null))
    }

    @Test
    fun migrate_absentSourceKey_isSkippedInBothModes() {
        val daybook = context.openDaybook(Settings) {
            migrations = listOf(
                SharedPreferencesMigrationSource(context, mode = MigrationMode.STRICT) {
                    migrate(appPrefs.boolean("never_written"), into = Settings.darkMode)
                },
            )
        }
        assertFalse(daybook.contains("dark_mode"))
    }

    // --- 全キー import ---

    @Test
    fun importAllKeys_importsIdentityMapped_andCoexistsWithExplicitEntries() {
        prefs(appPrefs).edit().putString("label", "kept").putInt("free", 9).commit()
        prefs(legacyPrefs).edit().putLong("counter", 5L).commit()

        val daybook = context.openDaybook(Settings) {
            migrations = listOf(
                SharedPreferencesMigrationSource(context) {
                    importAllKeys(appPrefs)
                    migrate(legacyPrefs.long("counter"), into = Settings.count)
                },
            )
        }

        assertEquals("kept", daybook.getString("label", null))
        assertEquals(9, daybook.getInt("free", 0))
        assertEquals(5L, daybook.getLong("count", 0))
    }

    // --- モード ---

    @Test
    fun strict_typeMismatch_failsOpenAndRetriesNextOpen() {
        prefs(appPrefs).edit().putString("dark_mode_v1", "not a boolean").commit()
        fun open() = context.openDaybook(Settings) {
            migrations = listOf(
                SharedPreferencesMigrationSource(context, mode = MigrationMode.STRICT) {
                    migrate(appPrefs.boolean("dark_mode_v1"), into = Settings.darkMode)
                },
            )
        }
        assertThrows(MigrationException::class.java) { open() }

        // マーカーは作られていないので、原因を直せば次のオープンで取り込まれる
        prefs(appPrefs).edit().putBoolean("dark_mode_v1", true).commit()
        assertEquals(true, open().getBoolean("dark_mode", false))
    }

    @Test
    fun lenient_typeMismatch_skipsEntryAndCompletes() {
        prefs(appPrefs).edit()
            .putString("dark_mode_v1", "not a boolean")
            .putString("label_v1", "kept")
            .commit()
        val skips = mutableListOf<SharedPreferencesMigrationSkip>()

        val daybook = context.openDaybook(Settings) {
            migrations = listOf(
                SharedPreferencesMigrationSource(context, mode = MigrationMode.LENIENT) {
                    onSkipped = { skips += it }
                    migrate(appPrefs.boolean("dark_mode_v1"), into = Settings.darkMode)
                    migrate(appPrefs.string("label_v1"), into = Settings.label)
                },
            )
        }

        assertFalse(daybook.contains("dark_mode"))
        assertEquals("kept", daybook.getString("label", null))
        assertEquals(1, skips.size)
        assertEquals("app_prefs", skips.single().fileName)
        assertEquals("dark_mode_v1", skips.single().key)
        assertEquals("not a boolean", skips.single().value)
        assertEquals("boolean", skips.single().expectedType)
    }

    @Test
    fun lenient_createsMarker_soSkippedEntriesAreNotRetried() {
        prefs(appPrefs).edit().putString("dark_mode_v1", "not a boolean").commit()
        context.openDaybook(Settings) {
            migrations = listOf(
                SharedPreferencesMigrationSource(context, mode = MigrationMode.LENIENT) {
                    migrate(appPrefs.boolean("dark_mode_v1"), into = Settings.darkMode)
                },
            )
        }

        // プロセス再起動を模す。原因を直しても、マーカー済みなので再取り込みされない
        DaybookPreferencesCache.resetForTesting()
        prefs(appPrefs).edit().putBoolean("dark_mode_v1", true).commit()
        val reopened = context.openDaybook(Settings) {
            migrations = listOf(
                SharedPreferencesMigrationSource(context, mode = MigrationMode.STRICT) {
                    migrate(appPrefs.boolean("dark_mode_v1"), into = Settings.darkMode)
                },
            )
        }
        assertFalse(reopened.contains("dark_mode"))
    }

    @Test
    fun migration_runsOnceAcrossReopen() {
        prefs(appPrefs).edit().putString("label_v1", "original").commit()
        fun open() = context.openDaybook(Settings) {
            migrations = listOf(
                SharedPreferencesMigrationSource(context) {
                    migrate(appPrefs.string("label_v1"), into = Settings.label)
                },
            )
        }
        open().edit { putString("label", "edited") }

        DaybookPreferencesCache.resetForTesting()
        prefs(appPrefs).edit().putString("label_v1", "changed later").commit()
        assertEquals("edited", open().getString("label", null))
    }

    @Test
    fun lenient_typeMismatch_coversAllSourceTypes() {
        @Suppress("UNCHECKED_CAST")
        prefs(appPrefs).edit()
            .putString("i", "x")
            .putString("l", "x")
            .putString("f", "x")
            .putInt("s", 1)
            .putString("set", "x")
            .putStringSet("bad_set", setOf(1) as Set<String>) // erasure を突いた非文字列要素
            .commit()
        val schema = object : DaybookSchema("mismatch-types") {
            val i = int("i2")
            val l = long("l2")
            val f = float("f2")
            val s = string("s2")
            val set = stringSet("set2")
            val badSet = stringSet("bad_set2")
        }
        val skips = mutableListOf<SharedPreferencesMigrationSkip>()

        val daybook = context.openDaybook(schema) {
            migrations = listOf(
                SharedPreferencesMigrationSource(context, mode = MigrationMode.LENIENT) {
                    onSkipped = { skips += it }
                    migrate(appPrefs.int("i"), into = schema.i)
                    migrate(appPrefs.long("l"), into = schema.l)
                    migrate(appPrefs.float("f"), into = schema.f)
                    migrate(appPrefs.string("s"), into = schema.s)
                    migrate(appPrefs.stringSet("set"), into = schema.set)
                    migrate(appPrefs.stringSet("bad_set"), into = schema.badSet)
                },
            )
        }

        assertEquals(6, skips.size)
        listOf("i2", "l2", "f2", "s2", "set2", "bad_set2").forEach { assertFalse(daybook.contains(it)) }
    }

    @Test
    fun strict_mismatchMessage_truncatesLongValues() {
        prefs(appPrefs).edit().putString("dark_mode_v1", "x".repeat(60)).commit()
        val e = assertThrows(MigrationException::class.java) {
            context.openDaybook(Settings) {
                migrations = listOf(
                    SharedPreferencesMigrationSource(context) {
                        migrate(appPrefs.boolean("dark_mode_v1"), into = Settings.darkMode)
                    },
                )
            }
        }
        assertTrue(e.message!!.contains("\u2026"))
    }

    // --- 宣言の矛盾（モード非依存で即例外） ---

    @Test
    fun duplicateSourceKey_failsAtDeclaration() {
        assertThrows(IllegalArgumentException::class.java) {
            SharedPreferencesMigrationSource(context, mode = MigrationMode.LENIENT) {
                migrate(appPrefs.string("key"), into = Settings.label)
                migrate(appPrefs.string("key"), into = Settings.label)
            }
        }
    }

    @Test
    fun duplicateTargetKey_failsAtDeclaration() {
        assertThrows(IllegalArgumentException::class.java) {
            SharedPreferencesMigrationSource(context, mode = MigrationMode.LENIENT) {
                migrate(appPrefs.string("a"), into = Settings.label)
                migrate(legacyPrefs.string("b"), into = Settings.label)
            }
        }
    }

    @Test
    fun explicitEntryOnImportAllFile_failsAtDeclaration_inBothOrders() {
        assertThrows(IllegalArgumentException::class.java) {
            SharedPreferencesMigrationSource(context, mode = MigrationMode.LENIENT) {
                importAllKeys(appPrefs)
                migrate(appPrefs.string("key"), into = Settings.label)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            SharedPreferencesMigrationSource(context, mode = MigrationMode.LENIENT) {
                migrate(appPrefs.string("key"), into = Settings.label)
                importAllKeys(appPrefs)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            SharedPreferencesMigrationSource(context, mode = MigrationMode.LENIENT) {
                importAllKeys(appPrefs)
                importAllKeys(appPrefs)
            }
        }
    }

    @Test
    fun targetCollisionAcrossFiles_failsAtRead_evenInLenientMode() {
        // 2 つの全キー import が同じキー名を持つ → 暗黙の上書き順序を作らず即例外
        prefs(appPrefs).edit().putString("shared", "a").commit()
        prefs(legacyPrefs).edit().putString("shared", "b").commit()
        assertThrows(IllegalArgumentException::class.java) {
            context.openDaybook(Settings) {
                migrations = listOf(
                    SharedPreferencesMigrationSource(context, mode = MigrationMode.LENIENT) {
                        importAllKeys(appPrefs)
                        importAllKeys(legacyPrefs)
                    },
                )
            }
        }
    }

    @Test
    fun migrateTargetCollidingWithImportedKey_failsAtRead() {
        prefs(appPrefs).edit().putString("label", "from-import").commit()
        prefs(legacyPrefs).edit().putString("label_v1", "from-migrate").commit()
        assertThrows(IllegalArgumentException::class.java) {
            context.openDaybook(Settings) {
                migrations = listOf(
                    SharedPreferencesMigrationSource(context) {
                        importAllKeys(appPrefs)
                        migrate(legacyPrefs.string("label_v1"), into = Settings.label)
                    },
                )
            }
        }
    }

    // --- スキーマ整合検査 ---

    @Test
    fun migrationTargetingAnotherSchema_failsAtOpen() {
        assertThrows(IllegalArgumentException::class.java) {
            context.openDaybook(Settings) {
                migrations = listOf(
                    SharedPreferencesMigrationSource(context) {
                        migrate(appPrefs.boolean("flag"), into = OtherSchema.foreign)
                    },
                )
            }
        }
    }

    // --- 宣言型の検証 ---

    @Test
    fun emptyFileName_failsAtDeclaration() {
        assertThrows(IllegalArgumentException::class.java) {
            SharedPreferencesFile("")
        }
    }

    @Test
    fun failedMigration_leavesStoreUnopened() {
        prefs(appPrefs).edit().putString("dark_mode_v1", "boom").commit()
        assertThrows(MigrationException::class.java) {
            context.openDaybook(Settings) {
                migrations = listOf(
                    SharedPreferencesMigrationSource(context) {
                        migrate(appPrefs.boolean("dark_mode_v1"), into = Settings.darkMode)
                    },
                )
            }
        }
        // 失敗したストアはキャッシュに載らない: マイグレーションなしで開き直せる
        val daybook = context.openDaybook(Settings)
        assertNull(daybook.getString("dark_mode", null))
    }
}
