package io.github.kr9ly.daybook.settings

import com.russhwolf.settings.ExperimentalSettingsApi
import io.github.kr9ly.daybook.kv.ChangeNotificationDelivery
import io.github.kr9ly.daybook.kv.Daybook
import io.github.kr9ly.daybook.kv.DaybookSchema
import io.github.kr9ly.daybook.kv.KvStore
import io.github.kr9ly.daybook.kv.asDaybook
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * レーン 5 敵対的テスト: DaybookFlowSettings（FlowSettings アダプタ）の契約攻撃。
 *
 * 契約の出典: docs/common-api.md「multiplatform-settings アダプタ」節、
 * public-api-extract.md の DaybookFlowSettings KDoc（conflate + distinctUntilChanged、
 * suspend 関数はディスパッチャ退避なし）。
 *
 * 既存テスト（DaybookFlowSettingsTest）がすでにカバーしている観点（初期値発火・
 * 同値 put のデデュープ・全型変種・OrNull の null 遷移）は重複させない。
 */
@OptIn(ExperimentalSettingsApi::class, ExperimentalCoroutinesApi::class)
class AdversarialDaybookFlowSettingsTest {

    private object PlainSchema : DaybookSchema("test")

    private fun open(): Daybook =
        KvStore.openInMemory(delivery = ChangeNotificationDelivery { it() }).asDaybook(PlainSchema)

    private fun openSettings(): DaybookFlowSettings = DaybookFlowSettings(open())

    // --- 攻撃 1: conflate は「遅い collector が中間の書き込みを見ない」を実際に満たすか ---
    // KDoc: 「値は conflate される — 遅い collector は中間の書き込みでなく最新状態を見る」。
    // collector が receive しない間に複数回 put し、次の receive で中間値 (1, 2) を
    // 飛ばして最新値 (3) だけを受け取ることを確認する。

    @Test
    fun getIntFlow_conflate_slowCollectorSkipsIntermediateValues() = runTest {
        val settings = openSettings()
        val values = settings.getIntFlow("key", -1).produceIn(backgroundScope)
        yield() // 初期値 (-1) の発火をバッファに積ませる
        assertEquals(-1, values.receive())

        // collector が receive しない間に 3 連続で put する
        settings.putInt("key", 1)
        settings.putInt("key", 2)
        settings.putInt("key", 3)
        repeat(5) { yield() } // conflate の内部コルーチンが upstream を drain しきるまで複数ターン必要

        // 実測: 最初の変化 (1) は既に produceIn 側のチャネルへ転送済みで届くが、
        // 2 回目の receive では中間値 2 が conflate によって握りつぶされ 3 に飛ぶ。
        // 「conflate されるので中間の書き込みは一切見えない」という素朴な読みとは異なり、
        // 「最初の 1 回だけ conflate 前に漏れうる」境界がドキュメントには書かれていない。
        assertEquals(1, values.receive())
        assertEquals(3, values.receive())
        assertTrue(values.tryReceive().isFailure, "中間値 2 はバッファに残っていない")
    }

    // --- 攻撃 2: string-set キーへ非対応の型付き Flow でアクセスした場合の CCE 発生タイミング ---
    // 契約: 「string-set が格納されたキーは型付き getter で ClassCastException」としか
    // 書かれておらず、Flow ではメソッド呼び出し時（Flow 構築時）に投げるのか、
    // collect（値の読み出し）時に投げるのかは未定義。

    @Test
    fun stringSetKey_typedFlow_throwsClassCastExceptionOnCollectionNotOnBuild() = runTest {
        val daybook = open()
        daybook.edit { putStringSet("set", setOf("x")) }
        val settings = DaybookFlowSettings(daybook)

        // Flow の生成自体（メソッド呼び出し）は例外にならないはず（cold flow の契約）。
        val flow = settings.getStringFlow("set", "default")

        // 実際に collect（内部の初期値読み取り）した時点で CCE が起きることを確認する。
        // 実測（発見）: produceIn(backgroundScope) 越しに receive() を待っても、CCE は
        // その呼び出しの例外としては捕捉できない。callbackFlow の producer コルーチンが
        // backgroundScope 上で例外終了し、構造化された並行性を通じて runTest 全体を
        // 失敗させる形で表面化する（flow.first() で直接 collect すれば同じ例外を
        // 呼び出し元で同期的に捕捉できる）。
        assertFailsWith<ClassCastException> {
            flow.first()
        }
    }

    // --- 攻撃 3: suspend put がディスパッチャ退避なしにその場で完了するか ---
    // KDoc: 「suspend 関数は名ばかりで…ディスパッチャへの退避なしにその場で完了する」。
    // yield を挟まずに put 直後、同じ Daybook を直接読んで即座に反映されているかを確認する
    // （ディスパッチャに退避していれば runTest 内でも順序が乱れうる）。

    @Test
    fun suspendPut_completesSynchronously_visibleImmediatelyViaCoreApi() = runTest {
        val daybook = open()
        val settings = DaybookFlowSettings(daybook)

        settings.putInt("key", 42) // yield なし
        // 同一コルーチン内、ディスパッチャに戻らず直後に core API で読めるはず
        assertEquals(42, daybook.getInt("key", -1))
    }

    // --- 攻撃 4: 同じ Daybook を core API と Flow アダプタで併用したときの相互可視性 ---

    @Test
    fun coreApiWrite_isVisibleThroughFlowSettingsFlow() = runTest {
        val daybook = open()
        val settings = DaybookFlowSettings(daybook)

        val values = settings.getIntFlow("key", -1).produceIn(backgroundScope)
        yield()
        assertEquals(-1, values.receive())

        daybook.edit { putInt("key", 7) } // core API 経由の書き込み
        assertEquals(7, values.receive(), "core API 経由の edit も Flow に届く")
    }

    // --- 攻撃 5: clear() 後の keys()/size() と、進行中の Flow への反映 ---

    @Test
    fun clear_reflectedInKeysAndSizeAndActiveFlow() = runTest {
        val settings = openSettings()
        settings.putInt("a", 1)
        settings.putString("b", "2")

        val values = settings.getIntFlow("a", -1).produceIn(backgroundScope)
        yield()
        assertEquals(1, values.receive())

        settings.clear()
        assertEquals(emptySet(), settings.keys())
        assertEquals(0, settings.size())
        assertEquals(-1, values.receive(), "clear は進行中の Flow にもデフォルト値として届く")
    }
}
