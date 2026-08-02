package io.github.kr9ly.daybook.prefs

import android.content.SharedPreferences
import android.os.Looper
import io.github.kr9ly.daybook.journal.FileSink
import io.github.kr9ly.daybook.journal.JournalSink
import io.github.kr9ly.daybook.kv.KvStore
import java.io.IOException
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * [DaybookSharedPreferences] の SharedPreferences 契約テスト。
 * 比較対象はフレームワーク実装（SharedPreferencesImpl）の観測可能な挙動。
 */
@RunWith(RobolectricTestRunner::class)
class DaybookSharedPreferencesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var store: KvStore? = null

    private fun openPrefs(): DaybookSharedPreferences {
        val opened = KvStore.open(tmp.root, "prefs")
        store = opened
        return DaybookSharedPreferences(opened)
    }

    @After
    fun tearDown() {
        store?.close()
    }

    /** 通知イベント（key と配送スレッド）の記録。Robolectric のテストスレッド = メインスレッド。 */
    private class RecordingListener : SharedPreferences.OnSharedPreferenceChangeListener {
        val keys = mutableListOf<String?>()
        val threads = mutableListOf<Thread>()

        override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
            keys.add(key)
            threads.add(Thread.currentThread())
        }
    }

    // --- getter の契約 ---

    @Test
    fun getters_returnDefaultsForAbsentKeys() {
        val prefs = openPrefs()
        assertEquals("def", prefs.getString("absent", "def"))
        assertNull(prefs.getString("absent", null))
        assertEquals(setOf("def"), prefs.getStringSet("absent", setOf("def")))
        assertNull(prefs.getStringSet("absent", null))
        assertEquals(42, prefs.getInt("absent", 42))
        assertEquals(42L, prefs.getLong("absent", 42L))
        assertEquals(1.5f, prefs.getFloat("absent", 1.5f))
        assertTrue(prefs.getBoolean("absent", true))
    }

    @Test
    fun getters_returnStoredValues() {
        val prefs = openPrefs()
        assertTrue(
            prefs.edit()
                .putString("string", "value")
                .putStringSet("set", setOf("a", "b"))
                .putInt("int", 7)
                .putLong("long", 7L)
                .putFloat("float", 2.5f)
                .putBoolean("boolean", false)
                .commit(),
        )
        assertEquals("value", prefs.getString("string", null))
        assertEquals(setOf("a", "b"), prefs.getStringSet("set", null))
        assertEquals(7, prefs.getInt("int", 0))
        assertEquals(7L, prefs.getLong("long", 0L))
        assertEquals(2.5f, prefs.getFloat("float", 0f))
        // 格納値 false と defValue true の区別（elvis が null のときだけ落ちること）
        assertFalse(prefs.getBoolean("boolean", true))
    }

    @Test
    fun getter_withMismatchedType_throwsClassCastException() {
        val prefs = openPrefs()
        prefs.edit().putString("key", "value").commit()
        assertThrows(ClassCastException::class.java) {
            prefs.getInt("key", 0)
        }
    }

    @Test
    fun getAll_returnsSnapshot() {
        val prefs = openPrefs()
        prefs.edit().putString("key", "value").commit()
        val snapshot = prefs.all
        prefs.edit().putString("key2", "value2").commit()
        assertEquals(mapOf<String, Any?>("key" to "value"), snapshot)
    }

    @Test
    fun contains_reflectsState() {
        val prefs = openPrefs()
        assertFalse(prefs.contains("key"))
        prefs.edit().putString("key", "value").commit()
        assertTrue(prefs.contains("key"))
    }

    // --- Editor のバッチ契約 ---

    @Test
    fun edit_isNotVisibleUntilCommit() {
        val prefs = openPrefs()
        val editor = prefs.edit().putString("key", "value")
        assertNull(prefs.getString("key", null))
        editor.commit()
        assertEquals("value", prefs.getString("key", null))
    }

    @Test
    fun clearInSameEdit_doesNotEraseLaterPuts() {
        // SharedPreferences の有名な仕様: clear は「commit 時点の既存キー」を消し、同一 edit の put は生き残る
        val prefs = openPrefs()
        prefs.edit().putString("old", "1").commit()
        prefs.edit()
            .putString("new", "2")
            .clear()
            .commit()
        assertEquals(mapOf<String, Any?>("new" to "2"), prefs.all)
    }

    @Test
    fun removeThenPutSameKeyInOneEdit_putWins() {
        val prefs = openPrefs()
        prefs.edit().putString("key", "old").commit()
        prefs.edit()
            .remove("key")
            .putString("key", "new")
            .commit()
        assertEquals("new", prefs.getString("key", null))
    }

    @Test
    fun putNullString_actsAsRemove() {
        val prefs = openPrefs()
        prefs.edit().putString("key", "value").putStringSet("set", setOf("a")).commit()
        prefs.edit()
            .putString("key", null)
            .putStringSet("set", null)
            .commit()
        assertFalse(prefs.contains("key"))
        assertFalse(prefs.contains("set"))
    }

    @Test
    fun putStringSet_takesDefensiveCopyAtPutTime() {
        val prefs = openPrefs()
        val mutable = mutableSetOf("a")
        val editor = prefs.edit().putStringSet("set", mutable)
        mutable.add("b")
        editor.commit()
        assertEquals(setOf("a"), prefs.getStringSet("set", null))
    }

    @Test
    fun editorAfterCommit_startsFromCleanBuffer() {
        // フレームワーク実装と同じく commit はバッファを消費する（再 commit で二重適用されない）
        val prefs = openPrefs()
        val listener = RecordingListener()
        prefs.registerOnSharedPreferenceChangeListener(listener)
        val editor = prefs.edit().putString("key", "value")
        editor.commit()
        prefs.edit().remove("key").commit()
        editor.commit() // バッファは空: key は復活しない
        assertFalse(prefs.contains("key"))
        assertEquals(listOf<String?>("key", "key"), listener.keys)
    }

    @Test
    fun commitWithoutChanges_returnsTrueWithoutNotifying() {
        val prefs = openPrefs()
        val listener = RecordingListener()
        prefs.registerOnSharedPreferenceChangeListener(listener)
        assertTrue(prefs.edit().commit())
        assertEquals(emptyList<String?>(), listener.keys)
    }

    @Test
    fun apply_appliesSynchronouslyInThisImplementation() {
        val prefs = openPrefs()
        prefs.edit().putString("key", "value").apply()
        assertEquals("value", prefs.getString("key", null))
    }

    // --- 変更通知 ---

    @Test
    fun listener_receivesChangedKeysInReverseOrderOnMainThread() {
        val prefs = openPrefs()
        val listener = RecordingListener()
        prefs.registerOnSharedPreferenceChangeListener(listener)
        prefs.edit()
            .putString("a", "1")
            .putInt("b", 2)
            .commit()
        // フレームワーク実装と同じく変更列の逆順。メインスレッドからの commit は同期配送
        assertEquals(listOf<String?>("b", "a"), listener.keys)
        assertTrue(listener.threads.all { it == Looper.getMainLooper().thread })
    }

    @Test
    fun listener_notNotifiedForSameValuePut() {
        val prefs = openPrefs()
        prefs.edit().putString("string", "value").putStringSet("set", setOf("a")).commit()
        val listener = RecordingListener()
        prefs.registerOnSharedPreferenceChangeListener(listener)
        prefs.edit()
            .putString("string", "value")
            .putStringSet("set", setOf("a")) // Set は equals 比較
            .commit()
        assertEquals(emptyList<String?>(), listener.keys)
    }

    @Test
    fun listener_notNotifiedForRemoveOfAbsentKey() {
        val prefs = openPrefs()
        val listener = RecordingListener()
        prefs.registerOnSharedPreferenceChangeListener(listener)
        prefs.edit().remove("absent").commit()
        assertEquals(emptyList<String?>(), listener.keys)
    }

    @Test
    fun listener_notifiedWhenValueChangesType() {
        val prefs = openPrefs()
        prefs.edit().putInt("key", 1).commit()
        val listener = RecordingListener()
        prefs.registerOnSharedPreferenceChangeListener(listener)
        prefs.edit().putString("key", "1").commit()
        assertEquals(listOf<String?>("key"), listener.keys)
    }

    @Test
    fun clear_notifiesNullKeyOnceBeforeChangedKeys() {
        val prefs = openPrefs()
        prefs.edit().putString("old", "1").commit()
        val listener = RecordingListener()
        prefs.registerOnSharedPreferenceChangeListener(listener)
        prefs.edit()
            .clear()
            .putString("a", "1")
            .putString("b", "2")
            .commit()
        // null（clear）→ 変更キーの逆順。消えたキーは個別通知されない
        assertEquals(listOf<String?>(null, "b", "a"), listener.keys)
    }

    @Test
    fun clearOnEmptyPrefs_stillNotifiesNullKey() {
        // フレームワーク実装は空でも keysCleared を立てる（API 30+ 挙動）
        val prefs = openPrefs()
        val listener = RecordingListener()
        prefs.registerOnSharedPreferenceChangeListener(listener)
        prefs.edit().clear().commit()
        assertEquals(listOf<String?>(null), listener.keys)
    }

    @Test
    fun multipleListeners_allReceiveEachKey() {
        val prefs = openPrefs()
        val first = RecordingListener()
        val second = RecordingListener()
        prefs.registerOnSharedPreferenceChangeListener(first)
        prefs.registerOnSharedPreferenceChangeListener(second)
        prefs.edit().putString("key", "value").commit()
        assertEquals(listOf<String?>("key"), first.keys)
        assertEquals(listOf<String?>("key"), second.keys)
    }

    @Test
    fun unregisteredListener_stopsReceiving() {
        val prefs = openPrefs()
        val listener = RecordingListener()
        prefs.registerOnSharedPreferenceChangeListener(listener)
        prefs.edit().putString("a", "1").commit()
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        prefs.edit().putString("b", "2").commit()
        assertEquals(listOf<String?>("a"), listener.keys)
    }

    @Test
    fun commitFromBackgroundThread_postsNotificationToMainLooper() {
        val prefs = openPrefs()
        val listener = RecordingListener()
        prefs.registerOnSharedPreferenceChangeListener(listener)
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit {
                prefs.edit().putString("key", "value").commit()
            }.get()
            // post 済みだが未配送（メインルーパーはテストが進めるまで止まっている）
            assertEquals(emptyList<String?>(), listener.keys)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf<String?>("key"), listener.keys)
            assertTrue(listener.threads.all { it == Looper.getMainLooper().thread })
        } finally {
            executor.shutdown()
        }
    }

    // --- 書き込み失敗 ---

    /** フラグを立てた後の追記が IOException で失敗する sink。 */
    private class FailingSink(private val delegate: JournalSink) : JournalSink {
        var failWrites = false

        override fun write(data: ByteArray) {
            if (failWrites) throw IOException("injected write failure")
            delegate.write(data)
        }

        override fun force() = delegate.force()

        override fun truncate(size: Long) = delegate.truncate(size)

        override fun close() = delegate.close()
    }

    @Test
    fun commitWithDiskFailure_returnsFalseWithoutApplyingOrNotifying() {
        var sink: FailingSink? = null
        val failingStore = KvStore.open(
            directory = tmp.root,
            name = "prefs",
            sinkFactory = { FailingSink(FileSink(it)).also { s -> sink = s } },
        )
        store = failingStore
        val prefs = DaybookSharedPreferences(failingStore)
        prefs.edit().putString("key", "before").commit()

        val listener = RecordingListener()
        prefs.registerOnSharedPreferenceChangeListener(listener)
        sink!!.failWrites = true
        assertFalse(prefs.edit().putString("key", "after").commit())
        // メモリだけ更新された状態を作らない（意図的な非互換: 編集を丸ごと破棄する）
        assertEquals("before", prefs.getString("key", null))
        assertEquals(emptyList<String?>(), listener.keys)

        // apply は同じ失敗を握りつぶす（フレームワーク実装の apply も失敗を通知しない）
        prefs.edit().putString("key", "applied").apply()
        assertEquals("before", prefs.getString("key", null))
    }

    // --- 永続化との統合 ---

    @Test
    fun committedEdits_surviveReopen() {
        openPrefs().edit()
            .putString("string", "value")
            .putInt("int", 7)
            .commit()
        store!!.close()

        val reopened = openPrefs()
        assertEquals("value", reopened.getString("string", null))
        assertEquals(7, reopened.getInt("int", 0))
    }
}
