package io.github.kr9ly.daybook

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.kr9ly.daybook.kv.DaybookSchema
import io.github.kr9ly.daybook.kv.Durability
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * API 間のストア共有のテスト — 同じ name の [Context.openDaybook]（Daybook API）と
 * [Context.getDaybookSharedPreferences]（SharedPreferences 互換 API）が同一ストアを共有する契約。
 */
@RunWith(RobolectricTestRunner::class)
class DualApiTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private object SettingsSchema : DaybookSchema("settings")
    private object MpSchema : DaybookSchema("mp")

    @After
    fun tearDown() {
        DaybookPreferencesCache.resetForTesting()
    }

    // --- ストア共有 ---

    @Test
    fun bothApis_shareTheSameStore() {
        val daybook = context.openDaybook(SettingsSchema)
        val prefs = context.getDaybookSharedPreferences("settings")

        prefs.edit().putString("from-prefs", "value").commit()
        assertEquals("value", daybook.getString("from-prefs", null))

        daybook.edit { putString("from-daybook", "value") }
        assertEquals("value", prefs.getString("from-daybook", null))
    }

    @Test
    fun bothApis_shareTheSameStore_whenPrefsApiOpensFirst() {
        val prefs = context.getDaybookSharedPreferences("settings")
        val daybook = context.openDaybook(SettingsSchema)
        prefs.edit().putInt("count", 7).commit()
        assertEquals(7, daybook.getInt("count", 0))
    }

    @Test
    fun defaultPrefsName_pointsToTheSameStore() {
        // スキーマの宣言名を prefs 規約（<packageName>_preferences）にすると、
        // デフォルトの SharedPreferences 互換 API と同一ストアを指す（裁定 2026-08-15）
        val schema = object : DaybookSchema("${context.packageName}_preferences") {}
        val daybook = context.openDaybook(schema)
        val prefs = context.getDefaultDaybookSharedPreferences()
        prefs.edit().putBoolean("flag", true).commit()
        assertTrue(daybook.getBoolean("flag", false))
    }

    // --- リスナーの相互可視性 ---

    @Test
    fun daybookListener_seesPrefsApiEdits() {
        val daybook = context.openDaybook(SettingsSchema)
        val prefs = context.getDaybookSharedPreferences("settings")
        val latch = CountDownLatch(1)
        var seen: Any? = null
        daybook.addChangeListener { key, newValue ->
            if (key == "key") {
                seen = newValue
                latch.countDown()
            }
        }
        prefs.edit().putString("key", "value").commit()
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        assertEquals("value", seen)
    }

    @Test
    fun prefsListener_doesNotSeeDaybookApiEdits() {
        // SharedPreferences リスナーはフレームワークの契約を再現しており、
        // SharedPreferences の Editor 経由の編集だけが届く（KDoc に明記した非対称性）
        val daybook = context.openDaybook(SettingsSchema)
        val prefs = context.getDaybookSharedPreferences("settings")
        val seenKeys = mutableListOf<String?>()
        // 登録はローカル変数経由（リスナー保持は WeakHashMap のため、無名ラムダ直渡しだと回収されうる）
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> seenKeys.add(key) }
        prefs.registerOnSharedPreferenceChangeListener(listener)

        daybook.edit { putString("from-daybook", "value") }
        prefs.edit().putString("from-prefs", "value").commit()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf<String?>("from-prefs"), seenKeys)
    }

    // --- オプション不一致の fail-fast ---

    @Test
    fun prefsApi_onSyncDurabilityStore_isRejected() {
        context.openDaybook(SettingsSchema) { durability = Durability.SYNC }
        assertThrows(IllegalArgumentException::class.java) {
            context.getDaybookSharedPreferences("settings")
        }
    }

    @Test
    fun multiProcessFlagMismatchAcrossApis_isRejected() {
        context.openDaybook(SettingsSchema) { multiProcess = true }
        assertThrows(IllegalArgumentException::class.java) {
            context.getDaybookSharedPreferences("settings")
        }
    }

    @Test
    fun matchingOptionsAcrossApis_areAccepted() {
        context.openDaybook(MpSchema) { multiProcess = true }
        val prefs = context.getDaybookSharedPreferences("mp", DaybookOptions(multiProcess = true))
        prefs.edit().putInt("n", 1).commit()
        assertEquals(1, context.openDaybook(MpSchema) { multiProcess = true }.getInt("n", 0))
    }

    // --- 透過 import はストア生成時のみ ---

    @Test
    fun importFlag_hasNoEffectWhenDaybookApiCreatedStoreFirst() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putString("legacy", "value").commit()

        context.openDaybook(SettingsSchema)
        val prefs = context.getDaybookSharedPreferences(
            "settings",
            DaybookOptions(importFromSharedPreferences = true),
        )
        // ストアは openDaybook が生成済みなのでキャッシュヒット扱いになり、取り込みは走らない
        assertEquals(null, prefs.getString("legacy", null))
    }

    // --- 永続化 ---

    @Test
    fun daybookApiData_survivesCacheReset() {
        context.openDaybook(SettingsSchema).edit { putInt("count", 42) }
        DaybookPreferencesCache.resetForTesting()
        assertEquals(42, context.openDaybook(SettingsSchema).getInt("count", 0))
        assertEquals(42, context.getDaybookSharedPreferences("settings").getInt("count", 0))
    }
}
