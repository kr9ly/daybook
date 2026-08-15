package io.github.kr9ly.daybook.kv

import io.github.kr9ly.daybook.io.createTempDirectory
import platform.Foundation.NSUserDefaults
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * [NSUserDefaultsMigrationSource] の契約テスト（シミュレータで実 NSUserDefaults を使う）—
 * 期待型による NSNumber の曖昧性解消・モードの意味論・prewarming ガードの再試行・
 * 冪等マーカー。
 */
class NSUserDefaultsMigrationTest {

    private object Schema : DaybookSchema("ud-migration") {
        val flag = boolean("flag")
        val count = long("count")
        val scale = double("scale")
        val label = string("label")
        val tags = stringSet("tags")
    }

    private val defaults = NSUserDefaults.standardUserDefaults
    private val usedKeys = listOf("src_flag", "src_count", "src_scale", "src_label", "src_tags")

    @AfterTest
    fun tearDown() {
        usedKeys.forEach { defaults.removeObjectForKey(it) }
        DaybookRegistry.resetForTesting()
    }

    private fun open(source: MigrationSource): Daybook =
        Daybook.open(createTempDirectory().path, Schema) { migrations = listOf(source) }

    // --- 写像と型 ---

    @Test
    fun migrate_mapsDeclaredKeysWithExpectedTypes() {
        defaults.setBool(true, forKey = "src_flag")
        defaults.setInteger(42L, forKey = "src_count")
        defaults.setDouble(1.5, forKey = "src_scale")
        defaults.setObject("value", forKey = "src_label")

        val daybook = open(
            NSUserDefaultsMigrationSource {
                migrate(UserDefaultsSuite.standard.boolean("src_flag"), into = Schema.flag)
                migrate(UserDefaultsSuite.standard.long("src_count"), into = Schema.count)
                migrate(UserDefaultsSuite.standard.double("src_scale"), into = Schema.scale)
                migrate(UserDefaultsSuite.standard.string("src_label"), into = Schema.label)
            },
        )

        assertEquals(true, daybook.getBoolean("flag", false))
        assertEquals(42L, daybook.getLong("count", 0))
        assertEquals(1.5, daybook.getDouble("scale", 0.0))
        assertEquals("value", daybook.getString("label", null))
    }

    @Test
    fun stringSet_readsStringArray() {
        defaults.setObject(listOf("a", "b", "a"), forKey = "src_tags")
        val daybook = open(
            NSUserDefaultsMigrationSource {
                migrate(UserDefaultsSuite.standard.stringSet("src_tags"), into = Schema.tags)
            },
        )
        assertEquals(setOf("a", "b"), daybook.getStringSet("tags", null))
    }

    @Test
    fun expectedType_disambiguatesNSNumber() {
        // NSUserDefaults は数値の宣言型を保存しない。期待型のアクセサで読むことを確認する
        defaults.setInteger(1L, forKey = "src_flag")
        val daybook = open(
            NSUserDefaultsMigrationSource {
                migrate(UserDefaultsSuite.standard.boolean("src_flag"), into = Schema.flag)
            },
        )
        assertEquals(true, daybook.getBoolean("flag", false))
    }

    @Test
    fun absentSourceKey_isSkipped() {
        val daybook = open(
            NSUserDefaultsMigrationSource {
                migrate(UserDefaultsSuite.standard.boolean("src_flag"), into = Schema.flag)
            },
        )
        assertFalse(daybook.contains("flag"))
    }

    // --- モード ---

    @Test
    fun strict_typeMismatchFailsOpen() {
        defaults.setObject("not a number", forKey = "src_count")
        assertFailsWith<MigrationException> {
            open(
                NSUserDefaultsMigrationSource(mode = MigrationMode.STRICT) {
                    migrate(UserDefaultsSuite.standard.long("src_count"), into = Schema.count)
                },
            )
        }
    }

    @Test
    fun lenient_skipsMismatchAndCompletes() {
        defaults.setObject("not a number", forKey = "src_count")
        defaults.setObject("kept", forKey = "src_label")
        val skips = mutableListOf<UserDefaultsMigrationSkip>()

        val daybook = open(
            NSUserDefaultsMigrationSource(mode = MigrationMode.LENIENT) {
                onSkipped = { skips += it }
                migrate(UserDefaultsSuite.standard.long("src_count"), into = Schema.count)
                migrate(UserDefaultsSuite.standard.string("src_label"), into = Schema.label)
            },
        )

        assertFalse(daybook.contains("count"))
        assertEquals("kept", daybook.getString("label", null))
        assertEquals(1, skips.size)
        assertEquals("src_count", skips.single().key)
        assertEquals(null, skips.single().suiteName)
        assertEquals("long", skips.single().expectedType)
    }

    // --- prewarming ガード ---

    @Test
    fun unavailableSource_isRetriedOnNextOpen() {
        defaults.setObject("value", forKey = "src_label")
        val dir = createTempDirectory().path
        fun source(availableNow: Boolean) = NSUserDefaultsMigrationSource(available = { availableNow }) {
            migrate(UserDefaultsSuite.standard.string("src_label"), into = Schema.label)
        }

        val first = Daybook.open(dir, Schema) { migrations = listOf(source(availableNow = false)) }
        assertFalse(first.contains("label")) // 読めない状態では何も取り込まない

        DaybookRegistry.resetForTesting() // プロセス再起動を模す
        val second = Daybook.open(dir, Schema) { migrations = listOf(source(availableNow = true)) }
        assertEquals("value", second.getString("label", null)) // マーカーがないので再試行される
    }

    @Test
    fun migration_runsOnceAcrossReopen() {
        defaults.setObject("original", forKey = "src_label")
        val dir = createTempDirectory().path
        fun source() = NSUserDefaultsMigrationSource {
            migrate(UserDefaultsSuite.standard.string("src_label"), into = Schema.label)
        }

        Daybook.open(dir, Schema) { migrations = listOf(source()) }.edit { putString("label", "edited") }

        DaybookRegistry.resetForTesting()
        defaults.setObject("changed later", forKey = "src_label")
        val reopened = Daybook.open(dir, Schema) { migrations = listOf(source()) }
        assertEquals("edited", reopened.getString("label", null))
    }

    // --- 宣言の矛盾 ---

    @Test
    fun duplicateDeclarations_failAtDeclaration() {
        assertFailsWith<IllegalArgumentException> {
            NSUserDefaultsMigrationSource {
                migrate(UserDefaultsSuite.standard.string("src_label"), into = Schema.label)
                migrate(UserDefaultsSuite.standard.string("src_label"), into = Schema.label)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            NSUserDefaultsMigrationSource {
                migrate(UserDefaultsSuite.standard.string("src_label"), into = Schema.label)
                migrate(UserDefaultsSuite.standard.string("src_tags"), into = Schema.label)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            UserDefaultsSuite.named("")
        }
    }
}
