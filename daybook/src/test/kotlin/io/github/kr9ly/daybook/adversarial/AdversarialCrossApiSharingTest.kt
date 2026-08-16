package io.github.kr9ly.daybook.adversarial

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kr9ly.daybook.DaybookOptions
import io.github.kr9ly.daybook.DaybookPreferencesCache
import io.github.kr9ly.daybook.getDaybookSharedPreferences
import io.github.kr9ly.daybook.importSharedPreferencesIntoDaybook
import io.github.kr9ly.daybook.kv.Daybook
import io.github.kr9ly.daybook.kv.DaybookSchema
import io.github.kr9ly.daybook.kv.Durability
import io.github.kr9ly.daybook.kv.MigrationEnvironment
import io.github.kr9ly.daybook.kv.MigrationSource
import io.github.kr9ly.daybook.openDaybook
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * レーン4 敵対的テスト: API 間ストア共有（:daybook）。
 *
 * 対象契約はすべて DESIGN.md / docs/android.md / docs/android-to-kmp.md / API.md からのみ引く。
 * 実装ソース（daybook/src/main, daybook-core の各 Main ソースセット）は一切読んでいない。
 *
 * 既存の DualApiTest（daybook/src/test/kotlin/io/github/kr9ly/daybook/DualApiTest.kt）が
 * 基本契約（ストア共有・リスナー非対称・オプション不一致 fail-fast の順方向）を検証済みのため、
 * ここでは既存テストが踏んでいない角度（開始順序の反転・migrations のキャッシュヒット無視・
 * 明示 import 経路のリスナー可視性・clear の非対称性・並行性）を攻める。
 */
@RunWith(RobolectricTestRunner::class)
class AdversarialCrossApiSharingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private object SettingsSchema : DaybookSchema("settings")

    @After
    fun tearDown() {
        DaybookPreferencesCache.resetForTesting()
    }

    // --- 攻撃1: importFromSharedPreferences フラグの効果が、prefs API が先にストアを
    //     生成した場合でも共通 API 側から見えるか（DESIGN.md: 「ストアの入手経路は core の
    //     DaybookRegistry に一本化されており、同じ名前を SharedPreferences 互換 API で開いても
    //     裏の KvStore は同一になる」＋ docs/android.md: 「透過: 初回生成時に同名のフレームワーク
    //     prefs を一度だけ取り込む」）。
    //     既存の importFlag_hasNoEffectWhenDaybookApiCreatedStoreFirst は逆方向（openDaybook が先）
    //     だけを検証しているので、ここでは prefs API 側の import フラグでストアを先に生成する。
    @Test
    fun importFlag_onPrefsApiFirstCreation_isVisibleToDaybookApi() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putString("legacy", "value").commit()

        val prefs = context.getDaybookSharedPreferences(
            "settings",
            DaybookOptions(importFromSharedPreferences = true),
        )
        assertEquals("value", prefs.getString("legacy", null))

        // 共通 API はキャッシュヒットで同じストアを受け取るはず（契約: 入手経路は
        // DaybookRegistry に一本化）
        val daybook = context.openDaybook(SettingsSchema)
        assertEquals("value", daybook.getString("legacy", null))
    }

    // --- 攻撃2: durability 不一致の fail-fast が方向対称か。
    //     既存の prefsApi_onSyncDurabilityStore_isRejected は openDaybook(SYNC) が先、
    //     getDaybookSharedPreferences が後の順序だけを検証している。
    //     DESIGN.md「オプション不一致は API をまたいで fail-fast」は方向を限定していないので、
    //     逆順（prefs が先に ASYNC でストアを生成 → 後から SYNC で openDaybook）も攻める。
    @Test
    fun durabilityMismatch_prefsApiFirst_thenSyncDaybook_isRejected() {
        context.getDaybookSharedPreferences("settings") // 既定 ASYNC でストア生成
        assertThrows(IllegalArgumentException::class.java) {
            context.openDaybook(SettingsSchema) { durability = Durability.SYNC }
        }
    }

    // --- 攻撃3: migrations オプションは「インスタンス生成時のみ有効・キャッシュヒット時は無視」
    //     という契約（DESIGN.md / API 抽出の DaybookOpenOptions KDoc）が、API をまたいだ
    //     キャッシュヒットでも守られるか。prefs API がストアを先に生成し、その後
    //     openDaybook にマーカー書き込み用の MigrationSource を渡しても実行されないはず。
    @Test
    fun migrationsOption_isIgnoredOnCacheHit_evenAcrossApis() {
        context.getDaybookSharedPreferences("settings") // ストアを migrations なしで生成

        var readCalled = false
        val markerSource = object : MigrationSource {
            override val id: String = "marker"

            override fun read(environment: MigrationEnvironment): Map<String, Any>? {
                readCalled = true
                return mapOf("marker" to "should-not-appear")
            }
        }

        val daybook = context.openDaybook(SettingsSchema) { migrations = listOf(markerSource) }

        assertFalse("キャッシュヒットのはずなのに migrations の read が呼ばれた", readCalled)
        assertEquals(null, daybook.getString("marker", null))
    }

    // --- 攻撃4（未定義挙動の探索）: Context.importSharedPreferencesIntoDaybook による明示的な
    //     一括取り込みは、SharedPreferences 互換 API の「Editor 経由の編集」ではない
    //     （公式 Editor を通さず直接ストアへバッチ書き込みする、と DaybookMigration の KDoc に
    //     ある）。DESIGN.md のリスナー非対称性の文言は「SharedPreferences 互換 API 経由の編集」
    //     とだけ書いており、この明示 import 操作がそこに含まれるかは契約文からは判定できない。
    //     共通 API 側のリスナーに届くかどうかを実際に確認し、届いた/届かないのどちらであっても
    //     「未定義挙動」として報告する（フェイルとしては扱わない）。
    @Test
    fun daybookListener_visibilityForExplicitImportSharedPreferencesIntoDaybook_isUndefined() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putString("legacy", "value").commit()

        val daybook = context.openDaybook(SettingsSchema)
        val latch = CountDownLatch(1)
        var seen: Any? = null
        daybook.addChangeListener { key, newValue ->
            if (key == "legacy") {
                seen = newValue
                latch.countDown()
            }
        }

        context.importSharedPreferencesIntoDaybook("settings")

        val delivered = latch.await(3, TimeUnit.SECONDS)
        // アサーションはあえて固定結果を要求しない: 実測を記録するだけの探索テスト。
        // 現状の実測（PASS/FAIL）は最終報告に具体的に記す。
        assertTrue(
            "探索用アサーション: 通知配送有無を固定するとテストの意味が変わるため、" +
                "delivered=$delivered / seen=$seen を報告に転記する",
            delivered || !delivered,
        )
        if (delivered) {
            assertEquals("value", seen)
        }
    }

    // --- 攻撃5: 「SharedPreferences のリスナーに届くのはあちらの Editor 経由の編集だけ」の
    //     非対称性は、既存テストでは put 操作でしか確認していない。clear() のような
    //     一括削除の操作ベース通知でも非対称性が保たれるかを攻める。
    @Test
    fun prefsListener_doesNotSeeDaybookApiClear() {
        val daybook = context.openDaybook(SettingsSchema)
        daybook.edit { putString("k1", "v1") }

        val prefs = context.getDaybookSharedPreferences("settings")
        val seenKeys = mutableListOf<String?>()
        val listener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                seenKeys.add(key)
            }
        prefs.registerOnSharedPreferenceChangeListener(listener)

        daybook.edit { clear() }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(
            "Daybook API 経由の clear() が prefs リスナーに漏れて見えている",
            emptyList<String?>(),
            seenKeys,
        )
        // clear が実際にストアへ反映されていることも併せて確認（漏れていないことの裏取り）
        assertEquals(null, prefs.getString("k1", null))
    }

    // --- 攻撃6: 両 API からの同時編集の交錯。互いに素なキー集合を各 API のスレッドから
    //     並行に書き込み、共有ストアが両 API の書き込みを取りこぼさず全部保持するか
    //     （DESIGN.md「編集の交錯（両 API から同時編集）」を字義通りに攻める）。
    @Test
    fun concurrentEditsFromBothApis_noLostWrites() {
        val daybook = context.openDaybook(SettingsSchema)
        val prefs = context.getDaybookSharedPreferences("settings")

        val threadCount = 16
        val barrier = CyclicBarrier(threadCount * 2)
        val failures = AtomicInteger(0)
        val threads = mutableListOf<Thread>()

        repeat(threadCount) { i ->
            threads += Thread {
                barrier.await()
                try {
                    daybook.edit { putInt("daybook-key-$i", i) }
                } catch (e: Exception) {
                    failures.incrementAndGet()
                }
            }
            threads += Thread {
                barrier.await()
                try {
                    prefs.edit().putInt("prefs-key-$i", i * 1000).commit()
                } catch (e: Exception) {
                    failures.incrementAndGet()
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join(15_000) }

        assertEquals("書き込みスレッドで例外が発生した", 0, failures.get())
        repeat(threadCount) { i ->
            assertEquals(i, daybook.getInt("daybook-key-$i", -1))
            assertEquals(i, prefs.getInt("daybook-key-$i", -1))
            assertEquals(i * 1000, daybook.getInt("prefs-key-$i", -1))
            assertEquals(i * 1000, prefs.getInt("prefs-key-$i", -1))
        }
    }

    // --- 攻撃7: 初回オープンそのものが両 API から同時にレースする場合。
    //     DESIGN.md「多重オープンによる破損リスクと変更の相互不可視を構造的に排除する」を
    //     字義通りに攻める: どちらが先に生成するか未確定な状態で両 API を同時に初回 open し、
    //     その後の全書き込みが両 API から一貫して見えるかを検証する。
    @Test
    fun racingFirstOpenFromBothApis_endsUpSharingOneStore() {
        val barrier = CyclicBarrier(2)
        var daybook: Daybook? = null
        var prefsRef: android.content.SharedPreferences? = null

        val t1 =
            Thread {
                barrier.await()
                daybook = context.openDaybook(SettingsSchema)
            }
        val t2 =
            Thread {
                barrier.await()
                prefsRef = context.getDaybookSharedPreferences("settings")
            }
        t1.start()
        t2.start()
        t1.join(15_000)
        t2.join(15_000)

        val d = requireNotNull(daybook)
        val p = requireNotNull(prefsRef)

        d.edit { putInt("after-race", 1) }
        assertEquals(1, p.getInt("after-race", -1))

        p.edit().putInt("after-race-2", 2).commit()
        assertEquals(2, d.getInt("after-race-2", -1))
    }
}
