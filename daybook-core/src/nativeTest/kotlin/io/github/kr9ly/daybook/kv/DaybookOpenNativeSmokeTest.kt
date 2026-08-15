package io.github.kr9ly.daybook.kv

import io.github.kr9ly.daybook.io.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Native ターゲットでの Daybook.open のスモークテスト。
 *
 * POSIX actual 一式（ファイル IO・ロック・配送スレッド）がジャーナルエンジンの
 * 実経路で組み合わさって動くことを確認する。個別 actual の契約は各単体テストが担う。
 */
class DaybookOpenNativeSmokeTest {

    private object SmokeSchema : DaybookSchema("smoke")
    private object PersistSchema : DaybookSchema("persist")
    private object SameSchema : DaybookSchema("same")

    @AfterTest
    fun resetRegistry() {
        DaybookRegistry.resetForTesting()
    }

    @Test
    fun openWriteRead_roundTrip() {
        val dir = createTempDirectory()
        val daybook = Daybook.open(dir.path, SmokeSchema)
        daybook.edit {
            putString("string", "value")
            putInt("int", 42)
            putBoolean("bool", true)
            putStringSet("set", setOf("a", "b"))
        }
        assertEquals("value", daybook.getString("string", null))
        assertEquals(42, daybook.getInt("int", 0))
        assertEquals(true, daybook.getBoolean("bool", false))
        assertEquals(setOf("a", "b"), daybook.getStringSet("set", null))
        assertTrue(daybook.contains("string"))
    }

    @Test
    fun reopen_afterRegistryReset_replaysJournalFromDisk() {
        val dir = createTempDirectory()
        Daybook.open(dir.path, PersistSchema).edit {
            putString("key", "persisted")
            putLong("long", 1L shl 40)
            putDouble("double", 1.5)
        }

        DaybookRegistry.resetForTesting()

        val reopened = Daybook.open(dir.path, PersistSchema)
        assertEquals("persisted", reopened.getString("key", null))
        assertEquals(1L shl 40, reopened.getLong("long", 0))
        assertEquals(1.5, reopened.getDouble("double", 0.0))
    }

    @Test
    fun open_samePath_returnsSameInstance() {
        val dir = createTempDirectory()
        val first = Daybook.open(dir.path, SameSchema)
        val second = Daybook.open("${dir.path}/sub/..", SameSchema)
        assertSame(first, second)
    }
}
