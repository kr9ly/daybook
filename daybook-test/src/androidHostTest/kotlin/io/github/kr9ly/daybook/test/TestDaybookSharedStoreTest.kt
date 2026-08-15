package io.github.kr9ly.daybook.test

import android.content.SharedPreferences
import io.github.kr9ly.daybook.kv.DaybookChangeListener
import io.github.kr9ly.daybook.kv.DaybookSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 同じ name の Daybook の顔と SharedPreferences の顔が同一ストアを共有する契約のテスト。
 * 1.x prefs API と 2.0 共通 API が混在するアプリを、そのままの配線でテストできることの検証。
 */
class TestDaybookSharedStoreTest {

    private object StoreSchema : DaybookSchema("store")

    @Test
    fun writesThroughEitherFace_areVisibleFromTheOther() {
        val world = TestDaybook()
        val prefs = world.getSharedPreferences("store")
        val daybook = world.getDaybook(StoreSchema)

        prefs.edit().putString("from-prefs", "value").commit()
        assertEquals("value", daybook.getString("from-prefs", null))

        daybook.edit { putInt("from-daybook", 7) }
        assertEquals(7, prefs.getInt("from-daybook", 0))
    }

    @Test
    fun multiProcessFlag_isSharedAcrossFaces() {
        val world = TestDaybook()
        world.getDaybook(StoreSchema, multiProcess = false)
        assertThrows(IllegalArgumentException::class.java) {
            world.getSharedPreferences("store", multiProcess = true)
        }
    }

    @Test
    fun commits_recordWritesFromBothFaces() {
        val world = TestDaybook()
        world.getSharedPreferences("store").edit().putString("a", "1").commit()
        world.getDaybook(StoreSchema).edit { putInt("b", 2) }
        assertEquals(
            listOf(
                RecordedCommit(clearRequested = false, changes = mapOf("a" to "1")),
                RecordedCommit(clearRequested = false, changes = mapOf("b" to 2)),
            ),
            world.commits("store"),
        )
    }

    @Test
    fun daybookListeners_seeWritesFromBothFaces() {
        val world = TestDaybook()
        val daybook = world.getDaybook(StoreSchema)
        val events = mutableListOf<Pair<String, Any?>>()
        daybook.addChangeListener(DaybookChangeListener { key, newValue -> events += key to newValue })

        world.getSharedPreferences("store").edit().putString("a", "1").commit()
        daybook.edit { putInt("b", 2) }
        assertEquals(listOf<Pair<String, Any?>>("a" to "1", "b" to 2), events)
    }

    @Test
    fun prefsListeners_seeOnlyPrefsFaceWrites() {
        val world = TestDaybook()
        val prefs = world.getSharedPreferences("store")
        val daybook = world.getDaybook(StoreSchema)
        val delivered = mutableListOf<String?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> delivered += key }
        prefs.registerOnSharedPreferenceChangeListener(listener)

        daybook.edit { putInt("from-daybook", 1) } // prefs リスナーには届かない（KDoc の非対称性）
        prefs.edit().putString("from-prefs", "v").commit()
        assertEquals(listOf<String?>("from-prefs"), delivered)
    }

    @Test
    fun failNextWrite_appliesToWhicheverFaceWritesNext() {
        val world = TestDaybook()
        val prefs = world.getSharedPreferences("store")
        world.failNextWrite("store")
        assertEquals(false, prefs.edit().putInt("key", 1).commit())
        world.getDaybook(StoreSchema).edit { putInt("key", 2) }
        assertEquals(2, prefs.getInt("key", 0))
    }
}
