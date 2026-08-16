package io.github.kr9ly.daybook.settings

import io.github.kr9ly.daybook.kv.ChangeNotificationDelivery
import io.github.kr9ly.daybook.kv.Daybook
import io.github.kr9ly.daybook.kv.DaybookChangeListener
import io.github.kr9ly.daybook.kv.DaybookSchema
import io.github.kr9ly.daybook.kv.KvStore
import io.github.kr9ly.daybook.kv.asDaybook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * レーン 5 敵対的テスト: DaybookSettings（ObservableSettings アダプタ）の契約攻撃。
 *
 * 契約の出典: docs/common-api.md「multiplatform-settings アダプタ」節、
 * public-api-extract.md の DaybookSettings / DedupingKeyListener / DeactivatableListener KDoc。
 *
 * 既存テスト（DaybookSettingsTest）がすでに広くカバーしている観点（同値 put の
 * デデュープ基本形・型不一致の登録時 CCE・登録後の型不一致サイレントドロップ・
 * string-set の keys/size 可視性と型付き getter の CCE・deactivate の基本形・clear の
 * デフォルト値発火）は重複させず、ドキュメントに明記のない境界だけを狙う。
 */
class AdversarialDaybookSettingsTest {

    private object PlainSchema : DaybookSchema("test")

    private fun open(): Daybook =
        KvStore.openInMemory(delivery = ChangeNotificationDelivery { it() }).asDaybook(PlainSchema)

    private fun openSettings(): DaybookSettings = DaybookSettings(open())

    // --- 攻撃 1: 値変化ベースのデデュープは「直前値」との比較か「初出値」との比較か ---
    // 契約: 「同値の put では発火しない」としか書かれておらず、A->B->A の A が
    // 「直前の B」と違うので発火するのか、「最初の A」と同じだから発火しないのかは未定義。

    @Test
    fun listener_togglingBackToOriginalValue_firesOnBothTransitions() {
        val settings = openSettings()
        settings.putInt("key", 1)
        val received = mutableListOf<Int>()
        settings.addIntListener("key", -1) { received += it }

        settings.putInt("key", 2) // A(1) -> B(2): 変化、発火
        settings.putInt("key", 1) // B(2) -> A(1): 直前値と違うので変化、発火のはず
        assertEquals(listOf(2, 1), received)
    }

    // --- 攻撃 2: Double.NaN の同値判定境界 ---
    // IEEE754 では NaN != NaN だが、Kotlin の Double#equals はボックス化比較で
    // NaN.equals(NaN) == true。デデュープ判定が equals ベースなら NaN の連続 put は
    // 「同値」として握りつぶされるはず（ドキュメントは equals とも != とも明記していない）。

    @Test
    fun listener_doubleNaN_consecutivePuts_dedupeSemantics() {
        val settings = openSettings()
        val received = mutableListOf<Double>()
        settings.addDoubleListener("key", 0.0) { received += it }

        settings.putDouble("key", Double.NaN)
        settings.putDouble("key", Double.NaN) // 2 回目: equals ベースなら握りつぶされる
        settings.putDouble("key", 1.0)

        // 契約は「同値の put は発火しない」としか言っておらず、NaN の同値性の定義
        // （IEEE754 の != か、boxed equals の ==）は未定義。実測値をそのまま記録する。
        assertEquals(listOf(Double.NaN, 1.0), received)
    }

    // --- 攻撃 3: リスナーコールバックが例外を投げた場合 ---
    // 契約（DaybookSettings KDoc、裁定 2026-08-16）: コールバックの例外は隔離される。
    // 他のリスナーへの配送や書き込み元には影響せず、そのリスナーの以後の通知も継続する。

    @Test
    fun listenerCallbackThrows_isIsolatedFromWriterAndOtherListeners() {
        val settings = openSettings()
        val firstReceived = mutableListOf<Int>()
        val secondReceived = mutableListOf<Int>()

        settings.addIntListener("key", -1) { value ->
            firstReceived += value
            throw IllegalStateException("boom")
        }
        settings.addIntListener("key", -1) { secondReceived += it }

        // 例外は書き込み元へ伝播しない
        settings.putInt("key", 1)
        // 他のリスナーへの配送は継続する
        assertEquals(listOf(1), secondReceived)
        // 例外を投げたリスナー自身も以後の通知を受け続ける
        settings.putInt("key", 2)
        assertEquals(listOf(1, 2), firstReceived)
        assertEquals(listOf(1, 2), secondReceived)
    }

    // --- 攻撃 4: リスナー内から同じ Settings を再操作（再入） ---
    // core の Daybook 契約（配送は書き込みロック外）はデッドロックしないと明記しているが、
    // この保証がアダプタ経由でも維持されるかを実測する。

    @Test
    fun listenerCallback_reentrantWriteToSameSettings_doesNotDeadlock() {
        val settings = openSettings()
        val order = mutableListOf<String>()

        settings.addIntListener("trigger", -1) { value ->
            order += "trigger:$value"
            if (value == 1) {
                settings.putInt("derived", value * 10)
            }
        }
        settings.addIntListener("derived", -1) { order += "derived:$it" }

        settings.putInt("trigger", 1)

        assertEquals(listOf("trigger:1", "derived:10"), order)
    }

    // --- 攻撃 5: deactivate の多重呼び出し ---
    // ドキュメントは deactivate の効果（daybook 側のリスナー解除）だけを述べ、
    // 二重呼び出しの挙動（冪等か例外か）には触れていない。

    @Test
    fun listener_deactivateCalledTwice_isIdempotent() {
        val settings = openSettings()
        val received = mutableListOf<Int>()
        val listener = settings.addIntListener("key", -1) { received += it }

        listener.deactivate()
        listener.deactivate() // 2 回目: 例外にならないことを期待（未定義動作の実測）

        settings.putInt("key", 1)
        assertEquals(emptyList(), received)
    }

    // --- 攻撃 6: API 間のリスナー可視性の非対称 ---
    // 契約: DaybookSettings のリスナーは値変化ベース、core (Daybook) のリスナーは
    // 操作ベース（同値 put も通知）。同じストアの同じキーへ Settings 経由で同値を
    // 2 回書いたとき、core 側リスナーは操作ベースなので 2 回とも通知され、
    // Settings 側リスナーは 1 回目しか通知されないはず — 同じ書き込み列を
    // 2 つの契約が同時に見ることの整合性を確認する。

    @Test
    fun sameWriteSequence_coreListenerOperationBased_settingsListenerValueChangeBased() {
        val daybook = open()
        val settings = DaybookSettings(daybook)

        val coreReceived = mutableListOf<Any?>()
        daybook.addChangeListener(DaybookChangeListener { _, newValue -> coreReceived += newValue })

        val settingsReceived = mutableListOf<Int>()
        settings.addIntListener("key", -1) { settingsReceived += it }

        settings.putInt("key", 1)
        settings.putInt("key", 1) // 同値 put

        assertEquals(listOf<Any?>(1, 1), coreReceived, "core リスナーは操作ベースで同値 put も通知される")
        assertEquals(listOf(1), settingsReceived, "Settings リスナーは値変化ベースで同値 put は握りつぶす")
    }

    // --- 攻撃 7: string-set キーへの nullable typed getter (OrNull 系) の CCE ---
    // 既存テストは getString / getInt の非 null 版のみ CCE を確認している。
    // OrNull 系の getter でも同じ fail-fast が働くかを別途確認する。

    @Test
    fun stringSetKey_orNullTypedGetter_throwsClassCastException() {
        val daybook = open()
        daybook.edit { putStringSet("set", setOf("x", "y")) }
        val settings = DaybookSettings(daybook)

        assertFailsWith<ClassCastException> { settings.getStringOrNull("set") }
        assertFailsWith<ClassCastException> { settings.getIntOrNull("set") }
    }

    // --- 攻撃 8: clear() 前後で string-set キーが size に含まれ続けるか ---

    @Test
    fun clear_removesStringSetKeyToo() {
        val daybook = open()
        daybook.edit { putStringSet("set", setOf("x")) }
        val settings = DaybookSettings(daybook)

        assertEquals(1, settings.size)
        settings.clear()
        assertEquals(0, settings.size)
        assertTrue(!settings.hasKey("set"))
    }
}
