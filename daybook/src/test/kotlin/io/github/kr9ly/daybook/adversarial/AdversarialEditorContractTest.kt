package io.github.kr9ly.daybook.adversarial

import android.content.SharedPreferences
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.kr9ly.daybook.DaybookOptions
import io.github.kr9ly.daybook.DaybookPreferencesCache
import io.github.kr9ly.daybook.getDaybookSharedPreferences
import io.github.kr9ly.daybook.importSharedPreferencesIntoDaybook
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.lang.ref.WeakReference
import kotlin.random.Random

/**
 * README / DESIGN.md / KDoc の記述だけを根拠に、SharedPreferences 互換レイヤーの
 * Editor 契約・リスナー契約・キー/値の境界値を疑って壊しにいくテスト。
 *
 * 実装の内部は見ていない。観測可能な挙動だけを検証する。
 */
@RunWith(RobolectricTestRunner::class)
class AdversarialEditorContractTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun uniqueName(prefix: String = "store"): String = "$prefix-${Random.nextLong()}"

    private fun idleMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        DaybookPreferencesCache.resetForTesting()
    }

    // ------------------------------------------------------------------
    // インスタンスキャッシュ
    // ------------------------------------------------------------------

    // README/KDoc: 「同名は常に同一インスタンスを返す」
    @Test
    fun sameName_alwaysReturnsSameInstance() {
        val name = uniqueName()
        val a = context.getDaybookSharedPreferences(name)
        val b = context.getDaybookSharedPreferences(name)
        assertSame(a, b)
    }

    // KDoc: 「同名を異なる multiProcess で再取得したときは...IllegalArgumentException」
    @Test
    fun reopeningSameName_withDifferentMultiProcess_throwsIAE() {
        val name = uniqueName()
        context.getDaybookSharedPreferences(name, DaybookOptions(multiProcess = false))
        assertThrows(IllegalArgumentException::class.java) {
            context.getDaybookSharedPreferences(name, DaybookOptions(multiProcess = true))
        }
    }

    // 上と対称: true で先に開いてから false で再取得しても同様に IAE になるはず
    @Test
    fun reopeningSameName_multiProcessTrueFirst_thenFalse_throwsIAE() {
        val name = uniqueName()
        context.getDaybookSharedPreferences(name, DaybookOptions(multiProcess = true))
        assertThrows(IllegalArgumentException::class.java) {
            context.getDaybookSharedPreferences(name, DaybookOptions(multiProcess = false))
        }
    }

    // 同じ multiProcess 値での再取得は例外にならず、同一インスタンスを返す
    @Test
    fun reopeningSameName_sameMultiProcess_isFine() {
        val name = uniqueName()
        val a = context.getDaybookSharedPreferences(name, DaybookOptions(multiProcess = true))
        val b = context.getDaybookSharedPreferences(name, DaybookOptions(multiProcess = true))
        assertSame(a, b)
    }

    // KDoc: 「name は非空・`/` を含んではならない」の境界
    @Test
    fun name_containingSlash_isRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            context.getDaybookSharedPreferences("foo/bar")
        }
    }

    @Test
    fun name_empty_isRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            context.getDaybookSharedPreferences("")
        }
    }

    // ------------------------------------------------------------------
    // Editor バッチの契約 (AOSP SharedPreferencesImpl 準拠を謳っている)
    // ------------------------------------------------------------------

    // README: 「同一 edit 内で clear が put を消さない」— clear() を先に呼んでも
    // 同じ edit 内の put は生き残る（AOSP は呼び出し順ではなく mClear フラグ + 値マップの合成）
    @Test
    fun clearThenPut_sameEdit_putSurvives() {
        val prefs = context.getDaybookSharedPreferences(uniqueName())
        prefs.edit().putString("a", "old").commit()

        prefs.edit()
            .clear()
            .putString("a", "new")
            .commit()

        assertEquals("new", prefs.getString("a", null))
    }

    // 呼び出し順を逆にしても（put の後に clear）結果は同じでなければならない —
    // AOSP は呼び出し順に依存しない合成なので、put→clear でも put が勝つはず
    @Test
    fun putThenClear_sameEdit_putStillSurvives() {
        val prefs = context.getDaybookSharedPreferences(uniqueName())
        prefs.edit().putString("a", "old").commit()

        prefs.edit()
            .putString("a", "new")
            .clear()
            .commit()

        // FAILING candidate: ドキュメントの「clear が put を消さない」を素直に読むと
        // 呼び出し順に関係なく put が勝つはずだが、実装が単純な逐次適用なら
        // clear が最後に効いてキーが消える可能性がある
        assertEquals("new", prefs.getString("a", null))
    }

    // 同一 edit 内で同じキーに remove の後 put した場合、put が勝つ（後勝ちではなく
    // 「最終的な値マップ」で合成される契約なら、呼び出し順に関わらず put が残るはず）
    @Test
    fun removeThenPut_sameKey_sameEdit_putWins() {
        val prefs = context.getDaybookSharedPreferences(uniqueName())
        prefs.edit().putString("k", "v0").commit()

        prefs.edit()
            .remove("k")
            .putString("k", "v1")
            .commit()

        assertEquals("v1", prefs.getString("k", null))
    }

    // put の後に remove した場合は remove が最終結果になるべき
    @Test
    fun putThenRemove_sameKey_sameEdit_removeWins() {
        val prefs = context.getDaybookSharedPreferences(uniqueName())
        prefs.edit().putString("k", "v0").commit()

        prefs.edit()
            .putString("k", "v1")
            .remove("k")
            .commit()

        assertNull(prefs.getString("k", null))
    }

    // commit() は成功したら true を返す契約（SharedPreferences 標準）
    @Test
    fun commit_returnsTrueOnSuccess() {
        val prefs = context.getDaybookSharedPreferences(uniqueName())
        val ok = prefs.edit().putString("a", "1").commit()
        assertTrue(ok)
    }

    // ------------------------------------------------------------------
    // 変更リスナー契約
    // ------------------------------------------------------------------

    // README/DESIGN: 「同値 put は通知しない」
    @Test(timeout = 10_000)
    fun samePut_doesNotNotify() {
        val prefs = context.getDaybookSharedPreferences(uniqueName())
        prefs.edit().putString("a", "same").commit()

        val notified = mutableListOf<String?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> notified.add(key) }
        prefs.registerOnSharedPreferenceChangeListener(listener)

        prefs.edit().putString("a", "same").commit()
        idleMainLooper()

        assertTrue("同値 put で通知が発生してはいけない: $notified", notified.isEmpty())
    }

    // README/DESIGN: 「不在キーの remove は通知しない」
    @Test(timeout = 10_000)
    fun removeOfAbsentKey_doesNotNotify() {
        val prefs = context.getDaybookSharedPreferences(uniqueName())
        val notified = mutableListOf<String?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> notified.add(key) }
        prefs.registerOnSharedPreferenceChangeListener(listener)

        prefs.edit().remove("neverExisted").commit()
        idleMainLooper()

        assertTrue("不在キーの remove で通知が発生してはいけない: $notified", notified.isEmpty())
    }

    // DESIGN.md: 「変更列の逆順で...配送する（AOSP と同じ）」
    @Test(timeout = 10_000)
    fun multipleKeyChanges_notifiedInReverseOrder() {
        val prefs = context.getDaybookSharedPreferences(uniqueName())
        val notified = mutableListOf<String?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> notified.add(key) }
        prefs.registerOnSharedPreferenceChangeListener(listener)

        prefs.edit()
            .putString("first", "1")
            .putString("second", "2")
            .putString("third", "3")
            .commit()
        idleMainLooper()

        assertEquals(listOf("third", "second", "first"), notified)
    }

    // KDoc: 「clear via Editor.clear always notifies listeners once with a null key
    // (the API 30+ behavior), regardless of the OS version」
    @Test(timeout = 10_000)
    fun clear_notifiesOnceWithNullKey() {
        val prefs = context.getDaybookSharedPreferences(uniqueName())
        prefs.edit().putString("a", "1").putString("b", "2").commit()

        val notified = mutableListOf<String?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> notified.add(key) }
        prefs.registerOnSharedPreferenceChangeListener(listener)

        prefs.edit().clear().commit()
        idleMainLooper()

        assertEquals(listOf<String?>(null), notified)
    }

    // メインスレッド配送: バックグラウンドスレッドから commit しても、リスナーは
    // メインスレッドの Looper 経由でのみ呼ばれるはず（idle() するまで届かない）。
    //
    // 注記: 最初の実装では commit() をテストのメインスレッド（Robolectric の main looper
    // と同一スレッド）から直接呼んでいたため、AOSP の「呼び出しスレッドが既に main なら
    // 同期実行」という一般的挙動と区別がつかず、テスト自体の設計ミスで失敗していた。
    // 「メインスレッド配送」を検証するには、別スレッドから commit する必要がある
    @Test(timeout = 10_000)
    fun notification_fromBackgroundThread_isNotDeliveredUntilMainLooperIdles() {
        val prefs = context.getDaybookSharedPreferences(uniqueName())
        val notified = mutableListOf<String?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> notified.add(key) }
        prefs.registerOnSharedPreferenceChangeListener(listener)

        val bg = Thread {
            prefs.edit().putString("a", "1").commit()
        }
        bg.start()
        bg.join()

        // バックグラウンドスレッドからの commit 直後、まだ idle() していない時点では
        // メインスレッドに配送されていないはず
        assertTrue("idle 前に配送されてしまっている: $notified", notified.isEmpty())

        idleMainLooper()
        assertEquals(listOf("a"), notified)
    }

    // unregister 後は通知が来ない
    @Test(timeout = 10_000)
    fun unregisteredListener_doesNotReceiveFurtherNotifications() {
        val prefs = context.getDaybookSharedPreferences(uniqueName())
        val notified = mutableListOf<String?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> notified.add(key) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

        prefs.edit().putString("a", "1").commit()
        idleMainLooper()

        assertTrue(notified.isEmpty())
    }

    // 複数リスナーが登録されていれば全員に配送される
    @Test(timeout = 10_000)
    fun multipleListeners_allNotified() {
        val prefs = context.getDaybookSharedPreferences(uniqueName())
        val notifiedA = mutableListOf<String?>()
        val notifiedB = mutableListOf<String?>()
        val listenerA = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> notifiedA.add(key) }
        val listenerB = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> notifiedB.add(key) }
        prefs.registerOnSharedPreferenceChangeListener(listenerA)
        prefs.registerOnSharedPreferenceChangeListener(listenerB)

        prefs.edit().putString("a", "1").commit()
        idleMainLooper()

        assertEquals(listOf("a"), notifiedA)
        assertEquals(listOf("a"), notifiedB)
    }

    // DESIGN.md: 互換リスナーは WeakHashMap 保持。強参照を持たなければ GC で
    // 黙って消えるはず（register-and-forget はリークしない）。
    // GC 依存のテストは本質的に不確実だが、System.gc() を複数回叩いて best-effort で検証する。
    @Test(timeout = 10_000)
    fun listenerWithoutStrongReference_canBeGarbageCollected() {
        val prefs = context.getDaybookSharedPreferences(uniqueName())
        // キャプチャなしラムダは Kotlin がシングルトン化して生成クラスの static が
        // 強参照を持ち続けるため、GC テストには使えない。ローカルをキャプチャさせて
        // インスタンス生成を強制する
        val captured = StringBuilder()
        var listener: SharedPreferences.OnSharedPreferenceChangeListener? =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key -> captured.append(key) }
        val ref = WeakReference(listener)
        prefs.registerOnSharedPreferenceChangeListener(listener)
        listener = null

        var collected = false
        for (i in 0 until 10) {
            System.gc()
            System.runFinalization()
            if (ref.get() == null) {
                collected = true
                break
            }
        }

        // DESIGN.md「互換リスナーの参照保持: フレームワークと同じ WeakHashMap」の検証。
        // 当初キャプチャなしラムダで「回収されない」と誤検出した（Kotlin のシングルトン化が
        // 原因のテスト側バグ）。キャプチャありに修正後は期待どおり回収される
        if (!collected) {
            fail(
                "WeakHashMap 保持のはずのリスナーが GC 後も回収されない。" +
                    "強参照で保持している可能性がある（DESIGN.md: 互換リスナーは WeakHashMap）",
            )
        }
    }

    // ------------------------------------------------------------------
    // キー・値の境界値
    // ------------------------------------------------------------------

    // KDoc/DESIGN: 「null キーは受け付けない」
    @Test
    fun putString_withNullKey_isRejected() {
        val prefs = context.getDaybookSharedPreferences(uniqueName())
        assertThrows(RuntimeException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (prefs.edit() as SharedPreferences.Editor).putString(null as String?, "v")
        }
    }

    // 空文字キーはドキュメント上禁止されていない（禁止対象は store 名）。
    // 素直に動くはず
    @Test
    fun emptyStringKey_isAllowed() {
        val prefs = context.getDaybookSharedPreferences(uniqueName())
        prefs.edit().putString("", "value-for-empty-key").commit()
        assertEquals("value-for-empty-key", prefs.getString("", null))
    }

    // マルチバイト文字のキー・値が壊れず往復する
    @Test
    fun multiByteKeyAndValue_roundTrip() {
        val prefs = context.getDaybookSharedPreferences(uniqueName())
        val key = "設定キー🔑絵文字"
        val value = "日本語の値😀こんにちは\nマルチライン\tタブ"
        prefs.edit().putString(key, value).commit()
        assertEquals(value, prefs.getString(key, null))
    }

    // 巨大な値も往復する（数千キー・小さな状態を想定とはあるが、単一値サイズの上限は明記されていない）
    @Test
    fun largeValue_roundTrip() {
        val prefs = context.getDaybookSharedPreferences(uniqueName())
        val big = "x".repeat(1_000_000)
        prefs.edit().putString("big", big).commit()
        assertEquals(big.length, prefs.getString("big", null)?.length)
    }

    // defValue はキー不在時のみ使われる。各型で確認
    @Test
    fun defValue_usedOnlyWhenKeyAbsent() {
        val prefs = context.getDaybookSharedPreferences(uniqueName())
        assertEquals(42, prefs.getInt("absent-int", 42))
        assertEquals(true, prefs.getBoolean("absent-bool", true))

        prefs.edit().putInt("present-int", 7).commit()
        assertEquals(7, prefs.getInt("present-int", 42))
    }

    // 型を跨いだ取得は AOSP と同様 ClassCastException になるはず
    // (KDoc の "互換 API の ClassCastException と同じ fail-fast" という記述に依拠)
    @Test
    fun wrongTypeGet_throwsClassCastException() {
        val prefs = context.getDaybookSharedPreferences(uniqueName())
        prefs.edit().putString("s", "not-an-int").commit()
        assertThrows(ClassCastException::class.java) {
            prefs.getInt("s", 0)
        }
    }

    // getAll() は防御的コピーを返すはず — 返り値をいじってもストアには影響しない
    @Test
    fun getAll_returnsDefensiveCopy() {
        val prefs = context.getDaybookSharedPreferences(uniqueName())
        prefs.edit().putString("a", "1").commit()

        val all = prefs.all

        @Suppress("UNCHECKED_CAST")
        val mutableAll = all as? MutableMap<String, Any?>
        try {
            mutableAll?.put("a", "tampered")
            mutableAll?.put("injected", "boom")
        } catch (e: UnsupportedOperationException) {
            // イミュータブルであれば、それはそれで防御的コピーの一形態として許容
        }

        assertEquals("1", prefs.getString("a", null))
        assertNull(prefs.getString("injected", null))
    }

    // stringSet はストア内部の Set への参照ではなく防御的コピーを返すはず
    @Test
    fun stringSet_isDefensiveCopy_mutatingReturnedSetDoesNotAffectStore() {
        val prefs = context.getDaybookSharedPreferences(uniqueName())
        val original = setOf("a", "b")
        prefs.edit().putStringSet("set", original).commit()

        val returned = prefs.getStringSet("set", null)
        try {
            (returned as? MutableSet<String>)?.add("injected")
        } catch (e: UnsupportedOperationException) {
            // イミュータブルなら OK
        }

        // daybook の意図的な非互換（DESIGN.md）: getStringSet は防御コピーを返す。
        // AOSP 実装は内部 Set の生参照を返す既知の罠（framework KDoc 自身が「変更するな」と
        // 警告する）で、daybook はこれを踏襲せず塞いでいる。
        // この敵対的テストが生参照の漏れを検出したことを受けて防御コピー化された
        val second = prefs.getStringSet("set", null)
        assertFalse("返り値の変更がストアに漏れている", second?.contains("injected") == true)
    }

    // stringSet: 渡した Set を後から呼び出し側が変更しても、ストアに反映されてはいけない
    // (put 時にも防御的コピーを取るべき)
    @Test
    fun stringSet_putDoesNotAliasCallerSet() {
        val prefs = context.getDaybookSharedPreferences(uniqueName())
        val mutableSource = mutableSetOf("a", "b")
        prefs.edit().putStringSet("set", mutableSource).commit()

        mutableSource.add("injected-after-put")

        val stored = prefs.getStringSet("set", null)
        assertFalse(
            "put に渡した Set への後からの変更がストアに反映されてしまっている",
            stored?.contains("injected-after-put") == true,
        )
    }

    // contains() の素直な確認
    @Test
    fun contains_reflectsPresenceCorrectly() {
        val prefs = context.getDaybookSharedPreferences(uniqueName())
        assertFalse(prefs.contains("k"))
        prefs.edit().putString("k", "v").commit()
        assertTrue(prefs.contains("k"))
        prefs.edit().remove("k").commit()
        assertFalse(prefs.contains("k"))
    }

    // README: importFromSharedPreferences は「生成時のみ有効、キャッシュヒット時は無視」
    @Test
    fun importFlag_isIgnoredOnCacheHit() {
        val name = uniqueName()
        // 先にフレームワーク prefs に値を仕込む
        val framework = context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
        framework.edit().putString("frameworkKey", "frameworkValue").commit()

        // import なしで daybook 側を先に生成（インスタンスをキャッシュに載せる）
        val prefs = context.getDaybookSharedPreferences(name, DaybookOptions(importFromSharedPreferences = false))
        assertFalse(prefs.contains("frameworkKey"))

        // 独自にキーを書いておく
        prefs.edit().putString("daybookKey", "daybookValue").commit()

        // 同名で import = true を指定して再取得してもキャッシュヒットのはずなので、
        // 取り込みが後追いで走ってはいけない
        val again = context.getDaybookSharedPreferences(name, DaybookOptions(importFromSharedPreferences = true))
        assertSame(prefs, again)
        assertFalse(
            "キャッシュヒット時に import が後から走ってしまっている",
            again.contains("frameworkKey"),
        )
        assertEquals("daybookValue", again.getString("daybookKey", null))
    }

    // 明示 import API: 2 回目の呼び出しは何もしない（false を返す）契約
    @Test(timeout = 10_000)
    fun explicitImport_isIdempotent_secondCallReturnsFalse() {
        val name = uniqueName()
        val framework = context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
        framework.edit().putString("k", "v").commit()

        val first = context.importSharedPreferencesIntoDaybook(name)
        val second = context.importSharedPreferencesIntoDaybook(name)

        assertTrue("初回の import は true を返すはず", first)
        assertFalse("2 回目の import は何もせず false を返すはず", second)
    }

    // import はマージ上書き: daybook 固有のキーは残るはず
    @Test(timeout = 10_000)
    fun explicitImport_mergesRatherThanReplaces() {
        val name = uniqueName()
        val prefs = context.getDaybookSharedPreferences(name)
        prefs.edit().putString("daybookOnly", "keepMe").commit()

        val framework = context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
        framework.edit().putString("frameworkKey", "fromFramework").commit()

        context.importSharedPreferencesIntoDaybook(name)

        assertEquals("keepMe", prefs.getString("daybookOnly", null))
        assertEquals("fromFramework", prefs.getString("frameworkKey", null))
    }
}
