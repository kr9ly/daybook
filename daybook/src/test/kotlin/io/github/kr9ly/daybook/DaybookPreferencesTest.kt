package io.github.kr9ly.daybook

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Context 拡張（公開 API）とプロセス内キャッシュのテスト。 */
@RunWith(RobolectricTestRunner::class)
class DaybookPreferencesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        DaybookPreferencesCache.resetForTesting()
    }

    // --- インスタンスキャッシュ ---

    @Test
    fun sameName_returnsSameInstance() {
        // フレームワークの getSharedPreferences と同じ「同名は同一インスタンス」契約。
        // 別の取得口から登録したリスナーにも届くことの土台
        val first = context.getDaybookSharedPreferences("settings")
        val second = context.getDaybookSharedPreferences("settings")
        assertSame(first, second)
    }

    @Test
    fun differentNames_returnDifferentInstances() {
        val settings = context.getDaybookSharedPreferences("settings")
        val other = context.getDaybookSharedPreferences("other")
        assertNotSame(settings, other)
        settings.edit().putString("key", "value").commit()
        assertEquals(emptyMap<String, Any?>(), other.all)
    }

    @Test
    fun sameNameWithDifferentMultiProcessFlag_isRejected() {
        context.getDaybookSharedPreferences("settings", DaybookOptions(multiProcess = false))
        assertThrows(IllegalArgumentException::class.java) {
            context.getDaybookSharedPreferences("settings", DaybookOptions(multiProcess = true))
        }
    }

    @Test
    fun multiProcessName_reopensWithSameFlag() {
        val first = context.getDaybookSharedPreferences("mp", DaybookOptions(multiProcess = true))
        val second = context.getDaybookSharedPreferences("mp", DaybookOptions(multiProcess = true))
        assertSame(first, second)
    }

    // --- name の検証 ---

    @Test
    fun emptyName_isRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            context.getDaybookSharedPreferences("")
        }
    }

    @Test
    fun nameContainingSlash_isRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            context.getDaybookSharedPreferences("../escape")
        }
    }

    // --- デフォルト名 ---

    @Test
    fun defaultPreferences_useFrameworkNamingConvention() {
        // PreferenceManager.getDefaultSharedPreferences と同じ <packageName>_preferences
        val default = context.getDefaultDaybookSharedPreferences()
        val explicit = context.getDaybookSharedPreferences("${context.packageName}_preferences")
        assertSame(explicit, default)
    }

    // --- 保存先と永続化 ---

    @Test
    fun data_isStoredUnderFilesDirDaybook() {
        context.getDaybookSharedPreferences("settings").edit().putString("key", "value").commit()
        assertTrue(File(context.filesDir, "daybook/settings.1.journal").exists())
    }

    @Test
    fun committedData_survivesCacheReset() {
        // キャッシュ破棄（≒ プロセス再起動）後もジャーナルから状態が復元される
        context.getDaybookSharedPreferences("settings").edit().putInt("count", 42).commit()
        DaybookPreferencesCache.resetForTesting()
        assertEquals(42, context.getDaybookSharedPreferences("settings").getInt("count", 0))
    }
}
