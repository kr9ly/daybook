package io.github.kr9ly.daybook.test

import io.github.kr9ly.daybook.io.IoException
import io.github.kr9ly.daybook.kv.DaybookChangeListener
import io.github.kr9ly.daybook.kv.string
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** common の [TestDaybook]（Daybook の顔）の契約テスト。 */
class TestDaybookCommonTest {

    // --- インスタンス管理 ---

    @Test
    fun sameName_returnsSameInstance() {
        val world = TestDaybook()
        assertSame(world.getDaybook("store"), world.getDaybook("store"))
    }

    @Test
    fun differentNames_areIsolated() {
        val world = TestDaybook()
        world.getDaybook("a").edit { putInt("key", 1) }
        assertFalse(world.getDaybook("b").contains("key"))
    }

    @Test
    fun instances_areIsolatedWorlds() {
        val first = TestDaybook()
        val second = TestDaybook()
        first.getDaybook("store").edit { putInt("key", 1) }
        assertFalse(second.getDaybook("store").contains("key"))
    }

    @Test
    fun defaultDaybook_usesPackageNameConvention() {
        val world = TestDaybook(packageName = "com.example")
        world.getDefaultDaybook().edit { putInt("key", 1) }
        assertEquals(1, world.getDaybook("com.example_preferences").getInt("key", 0))
    }

    @Test
    fun multiProcessMismatch_throws() {
        val world = TestDaybook()
        world.getDaybook("store", multiProcess = false)
        assertFailsWith<IllegalArgumentException> {
            world.getDaybook("store", multiProcess = true)
        }
    }

    @Test
    fun invalidNames_throw() {
        val world = TestDaybook()
        assertFailsWith<IllegalArgumentException> { world.getDaybook("") }
        assertFailsWith<IllegalArgumentException> { world.getDaybook("a/b") }
        assertFailsWith<IllegalArgumentException> { world.commits("") }
    }

    // --- 同期配送 ---

    @Test
    fun listenerNotifications_areSynchronous() {
        val world = TestDaybook()
        val daybook = world.getDaybook("store")
        val events = mutableListOf<Pair<String, Any?>>()
        daybook.addChangeListener(DaybookChangeListener { key, newValue -> events += key to newValue })
        daybook.edit {
            putString("a", "1")
            remove("a")
        }
        // edit が返った時点で配送済み（待ち合わせ不要）
        assertEquals(listOf<Pair<String, Any?>>("a" to "1", "a" to null), events)
    }

    @Test
    fun propertyDelegate_worksOnTestDaybook() {
        val world = TestDaybook()
        var name by world.getDaybook("store").string("name", default = "unset")
        assertEquals("unset", name)
        name = "value"
        assertEquals("value", name)
    }

    // --- commits ---

    @Test
    fun commits_recordOneEntryPerBatchInWriteOrder() {
        val world = TestDaybook()
        val daybook = world.getDaybook("store")
        daybook.edit { putInt("a", 1) }
        daybook.edit {
            clear()
            putString("b", "2")
            remove("gone")
        }
        assertEquals(
            listOf(
                RecordedCommit(clearRequested = false, changes = mapOf("a" to 1)),
                RecordedCommit(clearRequested = true, changes = mapOf("b" to "2", "gone" to null)),
            ),
            world.commits("store"),
        )
    }

    @Test
    fun commits_areSnapshots() {
        val world = TestDaybook()
        val daybook = world.getDaybook("store")
        daybook.edit { putInt("a", 1) }
        val snapshot = world.commits("store")
        daybook.edit { putInt("b", 2) }
        assertEquals(1, snapshot.size)
        assertEquals(2, world.commits("store").size)
    }

    @Test
    fun commits_beforeAnyAccess_isEmpty() {
        val world = TestDaybook()
        assertEquals(emptyList(), world.commits("store"))
    }

    @Test
    fun emptyEdit_isNotRecorded() {
        val world = TestDaybook()
        world.getDaybook("store").edit {}
        assertEquals(emptyList(), world.commits("store"))
    }

    // --- failNextWrite ---

    @Test
    fun failNextWrite_failsOnceThenRecovers() {
        val world = TestDaybook()
        val daybook = world.getDaybook("store")
        val events = mutableListOf<String>()
        daybook.addChangeListener(DaybookChangeListener { key, _ -> events += key })

        world.failNextWrite("store")
        assertFailsWith<IoException> {
            daybook.edit { putInt("key", 1) }
        }
        // 状態は無傷・通知なし・記録なし
        assertFalse(daybook.contains("key"))
        assertEquals(emptyList(), events)
        assertEquals(emptyList(), world.commits("store"))

        daybook.edit { putInt("key", 2) }
        assertEquals(2, daybook.getInt("key", 0))
        assertEquals(listOf("key"), events)
    }

    @Test
    fun failNextWrite_beforeFirstAccess_appliesToFirstWrite() {
        val world = TestDaybook()
        world.failNextWrite("store")
        val daybook = world.getDaybook("store")
        assertFailsWith<IoException> {
            daybook.edit { putInt("key", 1) }
        }
    }

    @Test
    fun failNextWrite_isNotQueued() {
        val world = TestDaybook()
        world.failNextWrite("store")
        world.failNextWrite("store")
        val daybook = world.getDaybook("store")
        assertFailsWith<IoException> {
            daybook.edit { putInt("key", 1) }
        }
        daybook.edit { putInt("key", 2) }
        assertTrue(daybook.contains("key"))
    }
}
