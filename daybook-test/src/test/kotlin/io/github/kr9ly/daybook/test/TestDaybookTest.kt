package io.github.kr9ly.daybook.test

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TestDaybook] のテスト。
 * Robolectric なしの素の JVM で走ること自体がこのモジュールの検証対象なので、
 * ランナー指定はあえてしない。
 */
class TestDaybookTest {

    private val daybook = TestDaybook()

    // --- 取得口 ---

    @Test
    fun sameName_returnsSameInstance() {
        assertSame(
            daybook.getSharedPreferences("settings"),
            daybook.getSharedPreferences("settings"),
        )
    }

    @Test
    fun differentNames_areIsolated() {
        daybook.getSharedPreferences("alpha").edit().putInt("key", 1).commit()
        assertEquals(0, daybook.getSharedPreferences("beta").getInt("key", 0))
    }

    @Test
    fun separateContainers_areIsolated() {
        val other = TestDaybook()
        daybook.getSharedPreferences("settings").edit().putInt("key", 1).commit()
        assertEquals(0, other.getSharedPreferences("settings").getInt("key", 0))
        assertNotSame(
            daybook.getSharedPreferences("settings"),
            other.getSharedPreferences("settings"),
        )
    }

    @Test
    fun multiProcessFlagMismatch_throws() {
        daybook.getSharedPreferences("settings", multiProcess = false)
        assertThrows(IllegalArgumentException::class.java) {
            daybook.getSharedPreferences("settings", multiProcess = true)
        }
    }

    @Test
    fun multiProcessFlagConsistent_returnsSameInstance() {
        assertSame(
            daybook.getSharedPreferences("mp", multiProcess = true),
            daybook.getSharedPreferences("mp", multiProcess = true),
        )
    }

    @Test
    fun emptyName_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            daybook.getSharedPreferences("")
        }
    }

    @Test
    fun nameWithSlash_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            daybook.getSharedPreferences("foo/bar")
        }
    }

    @Test
    fun defaultSharedPreferences_derivesNameFromPackageName() {
        val scoped = TestDaybook(packageName = "com.example.app")
        assertSame(
            scoped.getDefaultSharedPreferences(),
            scoped.getSharedPreferences("com.example.app_preferences"),
        )
    }

    // --- 本物のアダプタが動いていることのスモーク ---

    @Test
    fun editorBatch_clearThenPutInSameEdit_putSurvives() {
        val prefs = daybook.getSharedPreferences("settings")
        prefs.edit().putString("old", "value").commit()

        prefs.edit().clear().putString("new", "value").commit()
        assertNull(prefs.getString("old", null))
        assertEquals("value", prefs.getString("new", null))
    }

    @Test
    fun listener_deliveredSynchronously_inReverseKeyOrder() {
        val prefs = daybook.getSharedPreferences("settings")
        val received = mutableListOf<String?>()
        // WeakHashMap 保持のため強参照をローカルに持つ
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            received += key
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)

        prefs.edit().putInt("a", 1).putInt("b", 2).commit()
        // commit が返った時点で配送済み（同期配送）、順序は変更列の逆順
        assertEquals(listOf<String?>("b", "a"), received)
    }

    @Test
    fun clear_notifiesNullKeyOnce() {
        val prefs = daybook.getSharedPreferences("settings")
        prefs.edit().putInt("a", 1).commit()
        val received = mutableListOf<String?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            received += key
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)

        prefs.edit().clear().commit()
        assertEquals(listOf<String?>(null), received)
    }

    @Test
    fun getStringSet_returnsDefensiveCopy() {
        val prefs = daybook.getSharedPreferences("settings")
        prefs.edit().putStringSet("tags", setOf("a", "b")).commit()
        val returned = (prefs.getStringSet("tags", null) as MutableSet<String>)
        returned.add("c")
        assertEquals(setOf("a", "b"), prefs.getStringSet("tags", null))
    }

    // --- commits の記録 ---

    @Test
    fun commits_recordsEffectiveChangesPerCommit() {
        val prefs = daybook.getSharedPreferences("settings")
        prefs.edit().putString("name", "alice").putInt("age", 30).commit()
        prefs.edit().remove("age").commit()

        val commits = daybook.commits("settings")
        assertEquals(2, commits.size)
        assertFalse(commits[0].clearRequested)
        assertEquals(mapOf<String, Any?>("name" to "alice", "age" to 30), commits[0].changes)
        assertEquals(mapOf<String, Any?>("age" to null), commits[1].changes)
    }

    @Test
    fun commits_recordsClearRequested() {
        val prefs = daybook.getSharedPreferences("settings")
        prefs.edit().clear().putString("fresh", "value").commit()

        val commit = daybook.commits("settings").single()
        assertTrue(commit.clearRequested)
        assertEquals(mapOf<String, Any?>("fresh" to "value"), commit.changes)
    }

    @Test
    fun commits_omitsIneffectiveEdits() {
        val prefs = daybook.getSharedPreferences("settings")
        prefs.edit().putInt("a", 1).commit()

        prefs.edit().putInt("a", 1).commit() // 同値 put
        prefs.edit().remove("absent").commit() // 不在キーの remove
        prefs.edit().commit() // 空 edit
        assertEquals(1, daybook.commits("settings").size)
    }

    @Test
    fun commits_returnsSnapshot() {
        val prefs = daybook.getSharedPreferences("settings")
        prefs.edit().putInt("a", 1).commit()
        val snapshot = daybook.commits("settings")

        prefs.edit().putInt("b", 2).commit()
        assertEquals(1, snapshot.size)
        assertEquals(2, daybook.commits("settings").size)
    }

    @Test
    fun commits_unknownName_isEmpty() {
        assertEquals(emptyList<RecordedCommit>(), daybook.commits("never-touched"))
    }

    @Test
    fun recordedCommit_toStringShowsContent() {
        val prefs = daybook.getSharedPreferences("settings")
        prefs.edit().putInt("a", 1).commit()
        val text = daybook.commits("settings").single().toString()
        assertTrue(text, text.contains("clearRequested=false") && text.contains("a=1"))
    }

    // --- 失敗注入 ---

    @Test
    fun failNextWrite_commitReturnsFalse_stateUntouched_notRecorded_noNotification() {
        val prefs = daybook.getSharedPreferences("settings")
        val received = mutableListOf<String?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            received += key
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)

        daybook.failNextWrite("settings")
        assertFalse(prefs.edit().putInt("a", 1).commit())
        assertFalse(prefs.contains("a"))
        assertEquals(emptyList<RecordedCommit>(), daybook.commits("settings"))
        assertEquals(emptyList<String?>(), received)
    }

    @Test
    fun failNextWrite_consumedByOneFailure_laterWritesSucceed() {
        val prefs = daybook.getSharedPreferences("settings")
        daybook.failNextWrite("settings")

        assertFalse(prefs.edit().putInt("a", 1).commit())
        assertTrue(prefs.edit().putInt("a", 1).commit())
        assertEquals(1, prefs.getInt("a", 0))
        assertEquals(1, daybook.commits("settings").size)
    }

    @Test
    fun failNextWrite_notConsumedByIneffectiveEdit() {
        val prefs = daybook.getSharedPreferences("settings")
        prefs.edit().putInt("a", 1).commit()
        daybook.failNextWrite("settings")

        assertTrue(prefs.edit().putInt("a", 1).commit()) // 同値 put はディスクに届かない
        assertFalse(prefs.edit().putInt("a", 2).commit())
        assertEquals(1, prefs.getInt("a", 0))
    }

    @Test
    fun failNextWrite_beforeFirstAccess_applies() {
        daybook.failNextWrite("settings")
        val prefs = daybook.getSharedPreferences("settings")
        assertFalse(prefs.edit().putInt("a", 1).commit())
    }

    @Test
    fun failNextWrite_applyDiscardsEditSilently() {
        val prefs = daybook.getSharedPreferences("settings")
        daybook.failNextWrite("settings")

        prefs.edit().putInt("a", 1).apply()
        assertFalse(prefs.contains("a"))
        assertTrue(prefs.edit().putInt("a", 1).commit())
    }
}
