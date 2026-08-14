package io.github.kr9ly.daybook.coroutines.adversarial

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.kr9ly.daybook.getDaybookSharedPreferences
import io.github.kr9ly.daybook.int
import io.github.kr9ly.daybook.string
import io.github.kr9ly.daybook.coroutines.asFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.time.Duration.Companion.seconds

/**
 * 敵対的テスト: PreferenceProperty<T>.asFlow() の契約検証。
 *
 * 対象ドキュメント（公開 API リファレンス, daybook-coroutines モジュール）:
 * 「Emits the current value on collection, then the new value whenever the property's key
 *  changes (a `clear()` re-reads as well). Values are conflated ... equal consecutive
 *  values are dropped (distinctUntilChanged). Backed by
 *  [SharedPreferences.OnSharedPreferenceChangeListener], so it works against any
 *  `SharedPreferences` implementation and shares its contract: change callbacks arrive on
 *  the main thread, and only edits made in the same process are observed.」
 *
 * 各テストのケース名の前に、検証しているドキュメントの記述を短くコメントする。
 */
@RunWith(RobolectricTestRunner::class)
class AdversarialFlowTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private enum class Theme { LIGHT, DARK }

    // 「Emits the current value on collection」— 変更が一度も起きていなくても、
    // かつ変更リスナー配送経路（idle()）を一切ポンプしなくても、collect 開始時点の
    // 現在値がそのまま同期的に読めるはず（初期発火はリスナー経由ではないと期待される）。
    @Test(timeout = 20_000)
    fun initialValueEmittedWithoutPumpingListenerDelivery() = runTest(timeout = 20.seconds) {
        val prefs = context.getSharedPreferences("adv_initial", Context.MODE_PRIVATE)
        prefs.edit().putInt("k", 55).apply()
        // 意図的に idle() を呼ばない — リスナー配送を一切ポンプしない状態で
        // collect した初期値が読めるかを確認する
        val prop = prefs.int("k", default = 0)
        val received = mutableListOf<Int>()
        val job = launch { prop.asFlow().collect { received.add(it) } }
        runCurrent()
        job.cancelAndJoin()

        assertEquals(listOf(55), received)
    }

    // 「then the new value whenever the property's key changes」
    // 素直な変更 → 再発火の確認（基本契約）
    @Test(timeout = 20_000)
    fun changeToObservedKeyReEmitsNewValue() = runTest(timeout = 20.seconds) {
        val prefs = context.getSharedPreferences("adv_basic_change", Context.MODE_PRIVATE)
        val prop = prefs.int("k", default = 0)
        val received = mutableListOf<Int>()
        val job = launch { prop.asFlow().collect { received.add(it) } }
        runCurrent()

        prefs.edit().putInt("k", 1).apply()
        idle()
        runCurrent()

        job.cancelAndJoin()
        assertEquals(listOf(0, 1), received)
    }

    // 「a clear() re-reads as well」
    // clear() 後にプロパティの default へ再読み込みされ、Flow が再発火するはず。
    // Robolectric の compileSdk 36 環境での SharedPreferences.clear() が
    // どんな key で通知するか（null か、消えた各キーか）に asFlow が耐えられるかを見る。
    @Test(timeout = 20_000)
    fun clearReReadsAsDeclaredDefault() = runTest(timeout = 20.seconds) {
        val prefs = context.getSharedPreferences("adv_clear", Context.MODE_PRIVATE)
        prefs.edit().putInt("k", 123).apply()
        val prop = prefs.int("k", default = 9)
        val received = mutableListOf<Int>()
        val job = launch { prop.asFlow().collect { received.add(it) } }
        runCurrent()

        prefs.edit().clear().apply()
        idle()
        runCurrent()

        job.cancelAndJoin()
        assertEquals(listOf(123, 9), received)
    }

    // daybook 実装での clear。DESIGN.md は「clear の通知は常に API30+ 挙動
    // (key=null を1回)」と明言しているため、こちらでも同じ契約が成り立つはず。
    @Test(timeout = 20_000)
    fun clearReReadsAsDeclaredDefault_daybookBacked() = runTest(timeout = 20.seconds) {
        val prefs = context.getDaybookSharedPreferences("adv_clear_daybook_1")
        prefs.edit().putInt("k", 123).apply()
        val prop = prefs.int("k", default = 9)
        val received = mutableListOf<Int>()
        val job = launch { prop.asFlow().collect { received.add(it) } }
        runCurrent()

        prefs.edit().clear().apply()
        idle()
        runCurrent()

        job.cancelAndJoin()
        assertEquals(listOf(123, 9), received)
    }

    // 「equal consecutive values are dropped (distinctUntilChanged)」
    // 現在値と同じ値を明示的に put しても、Flow としては新規発火してはいけない。
    @Test(timeout = 20_000)
    fun sameValueWriteDoesNotReEmit() = runTest(timeout = 20.seconds) {
        val prefs = context.getSharedPreferences("adv_dedup", Context.MODE_PRIVATE)
        val prop = prefs.int("k", default = 5)
        val received = mutableListOf<Int>()
        val job = launch { prop.asFlow().collect { received.add(it) } }
        runCurrent()

        prefs.edit().putInt("k", 5).apply() // 現在値と同じ
        idle()
        runCurrent()

        job.cancelAndJoin()
        assertEquals(listOf(5), received)
    }

    // 「only edits made in the same process are observed」の弱いが検証可能な系:
    // このプロパティの「キー」以外の変更は observed であってはならない
    // （OnSharedPreferenceChangeListener はプロパティ外の全キー変更も通知してくるため、
    // asFlow はキーでフィルタしているはず — フィルタ漏れは他プロパティの書き込みが
    // 素通しで漏れてくる形で顕在化する）
    @Test(timeout = 20_000)
    fun changeToOtherKeyIsNotObserved() = runTest(timeout = 20.seconds) {
        val prefs = context.getSharedPreferences("adv_isolation", Context.MODE_PRIVATE)
        val propA = prefs.int("a", default = 0)
        val propB = prefs.int("b", default = 0)
        val received = mutableListOf<Int>()
        val job = launch { propA.asFlow().collect { received.add(it) } }
        runCurrent()

        prefs.edit().putInt("b", 99).apply()
        idle()
        runCurrent()

        job.cancelAndJoin()
        assertEquals(listOf(0), received)
    }

    // 「Values are conflated — a slow collector sees the latest state, not every
    //  intermediate write」
    // collector を意図的にブロックした状態で複数回書き込み、再開後に中間値(1,2)を
    // 飛ばして最新値(3)だけが届くことを確認する。
    @Test(timeout = 20_000)
    fun slowCollectorSeesOnlyLatestValueNotEveryIntermediateWrite() = runTest(timeout = 20.seconds) {
        val prefs = context.getSharedPreferences("adv_conflate", Context.MODE_PRIVATE)
        val prop = prefs.int("k", default = 0)
        val received = mutableListOf<Int>()
        val blocker = CompletableDeferred<Unit>()
        val job = launch {
            prop.asFlow().collect { v ->
                received.add(v)
                if (v == 0) {
                    // 初期値を受け取った直後にブロックし、「遅い collector」を模す
                    blocker.await()
                }
            }
        }
        runCurrent() // collect 開始 → 初期値 0 を受け取り blocker.await() で停止するところまで進める

        prefs.edit().putInt("k", 1).apply()
        idle()
        prefs.edit().putInt("k", 2).apply()
        idle()
        prefs.edit().putInt("k", 3).apply()
        idle()

        blocker.complete(Unit)
        runCurrent()

        job.cancelAndJoin()
        assertEquals(listOf(0, 3), received)
    }

    // 「Backed by OnSharedPreferenceChangeListener」— collector が cancel されたら
    // リスナーは解除され、以後の書き込みはどこにも配送されない。
    // かつ、その後に新規 collect した Flow は「その時点の現在値」を初期値として
    // 正しく拾える（古い collector の残骸が悪さをしない）。
    @Test(timeout = 20_000)
    fun cancellationStopsObservationAndFreshCollectSeesLatestState() = runTest(timeout = 20.seconds) {
        val prefs = context.getSharedPreferences("adv_cancel", Context.MODE_PRIVATE)
        val prop = prefs.int("k", default = 0)

        val firstReceived = mutableListOf<Int>()
        val job1 = launch { prop.asFlow().collect { firstReceived.add(it) } }
        runCurrent()
        job1.cancelAndJoin()

        // cancel 後の書き込み。もし解除漏れがあればここでクラッシュするか、
        // 後続の collector に紛れ込んで観測されるはず
        prefs.edit().putInt("k", 42).apply()
        idle()

        val secondReceived = mutableListOf<Int>()
        val job2 = launch { prop.asFlow().collect { secondReceived.add(it) } }
        runCurrent()
        job2.cancelAndJoin()

        assertEquals(listOf(0), firstReceived)
        assertEquals(listOf(42), secondReceived)
    }

    // 「it works against any SharedPreferences implementation」の複数 collector 版:
    // 同じプロパティを 2 つの独立した Flow として同時に collect したとき、
    // 両方が同じ初期値・同じ変更列を独立に観測できるはず（cold flow・複数配送）。
    @Test(timeout = 20_000)
    fun multipleIndependentCollectorsEachObserveFullSequence() = runTest(timeout = 20.seconds) {
        val prefs = context.getSharedPreferences("adv_multi", Context.MODE_PRIVATE)
        val prop = prefs.int("k", default = 0)

        val received1 = mutableListOf<Int>()
        val received2 = mutableListOf<Int>()
        val job1 = launch { prop.asFlow().collect { received1.add(it) } }
        val job2 = launch { prop.asFlow().collect { received2.add(it) } }
        runCurrent()

        prefs.edit().putInt("k", 7).apply()
        idle()
        runCurrent()

        job1.cancelAndJoin()
        job2.cancelAndJoin()

        assertEquals(listOf(0, 7), received1)
        assertEquals(listOf(0, 7), received2)
    }

    // 「it works against any SharedPreferences implementation」— framework 実装だけでなく
    // daybook 実装の上でも同じ契約が成り立つはず。加えて DESIGN.md の
    // 「同値 put と不在キーの remove は通知しない」（daybook 互換層固有の契約）と
    // asFlow 自身の distinctUntilChanged が二重に効いても壊れないことを確認する。
    @Test(timeout = 20_000)
    fun worksAgainstDaybookBackedSharedPreferences() = runTest(timeout = 20.seconds) {
        val prefs = context.getDaybookSharedPreferences("adv_flow_daybook_2")
        val prop = prefs.string("name", default = "anon")
        val received = mutableListOf<String>()
        val job = launch { prop.asFlow().collect { received.add(it) } }
        runCurrent()

        prefs.edit().putString("name", "alice").apply()
        idle()
        runCurrent()

        prefs.edit().putString("name", "alice").apply() // 同値 put
        idle()
        runCurrent()

        job.cancelAndJoin()
        assertEquals(listOf("anon", "alice"), received)
    }

    // 「delegation and asFlow() work unchanged」（map の KDoc）
    // map(decode, encode) を通した後も asFlow はそのまま効き、変換後の値が流れるはず。
    @Test(timeout = 20_000)
    fun asFlowWorksThroughMapAdapter() = runTest(timeout = 20.seconds) {
        val prefs = context.getSharedPreferences("adv_map", Context.MODE_PRIVATE)
        val themeProp = prefs.string("theme", default = Theme.LIGHT.name)
            .map(decode = Theme::valueOf, encode = Theme::name)
        val received = mutableListOf<Theme>()
        val job = launch { themeProp.asFlow().collect { received.add(it) } }
        runCurrent()

        prefs.edit().putString("theme", Theme.DARK.name).apply()
        idle()
        runCurrent()

        job.cancelAndJoin()
        assertEquals(listOf(Theme.LIGHT, Theme.DARK), received)
    }

    // 「distinctUntilChanged は変換後の equals」(DESIGN.md 型安全 API 節)
    // 生ストレージ上は書き込みが起きていても、map 後の値が既存と等しければ
    // Flow としては再発火しないはず。
    @Test(timeout = 20_000)
    fun mapAdapterDistinctUntilChangedUsesDecodedEquality() = runTest(timeout = 20.seconds) {
        val prefs = context.getSharedPreferences("adv_map_dedup", Context.MODE_PRIVATE)
        val themeProp = prefs.string("theme", default = Theme.LIGHT.name)
            .map(decode = Theme::valueOf, encode = Theme::name)
        val received = mutableListOf<Theme>()
        val job = launch { themeProp.asFlow().collect { received.add(it) } }
        runCurrent()

        // 生ストレージの値としては同じ文字列 (Theme.LIGHT.name) を書き直すだけ
        prefs.edit().putString("theme", Theme.LIGHT.name).apply()
        idle()
        runCurrent()

        job.cancelAndJoin()
        assertEquals(listOf(Theme.LIGHT), received)
    }

    // 「Conversion failures ... propagate as-is; catch inside decode and return a
    //  fallback if you want lenient reads」+ PreferenceProperty.catch の KDoc
    // 「only the read path (including upstream map decoding) is covered」
    // collect 開始前から壊れている生値の初期発火が catch で回復されるはず。
    @Test(timeout = 20_000)
    fun catchRecoversPreCorruptedValueOnInitialEmission() = runTest(timeout = 20.seconds) {
        val prefs = context.getSharedPreferences("adv_catch_initial", Context.MODE_PRIVATE)
        prefs.edit().putString("theme", "GARBAGE").apply() // decode 不能な値を先に仕込む
        val themeProp = prefs.string("theme", default = Theme.LIGHT.name)
            .map(decode = Theme::valueOf, encode = Theme::name)
            .catch { Theme.DARK }
        val received = mutableListOf<Theme>()
        val job = launch { themeProp.asFlow().collect { received.add(it) } }
        runCurrent()

        job.cancelAndJoin()
        assertEquals(listOf(Theme.DARK), received)
    }

    // 同じく catch の読み取り回復だが、collect 中の再読み込みパス（変更検知後の
    // re-read）でも効くかを確認する。生の Editor で decode 不能な値へ書き換える
    // ことで PreferenceProperty.set() の encode を経由せずに decode 失敗を発生させる。
    @Test(timeout = 20_000)
    fun catchRecoversDecodeFailureOnChangeTriggeredReRead() = runTest(timeout = 20.seconds) {
        val prefs = context.getSharedPreferences("adv_catch_rereads", Context.MODE_PRIVATE)
        val themeProp = prefs.string("theme", default = Theme.LIGHT.name)
            .map(decode = Theme::valueOf, encode = Theme::name)
            .catch { Theme.DARK }
        val received = mutableListOf<Theme>()
        val job = launch { themeProp.asFlow().collect { received.add(it) } }
        runCurrent()

        // PreferenceProperty を経由せず、生の Editor で decode 不能な文字列を書き込む
        prefs.edit().putString("theme", "NOT_A_THEME").apply()
        idle()
        runCurrent()

        job.cancelAndJoin()
        assertEquals(listOf(Theme.LIGHT, Theme.DARK), received)
    }
}
