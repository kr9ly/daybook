package io.github.kr9ly.daybook.coroutines.adversarial.docs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kr9ly.daybook.boolean
import io.github.kr9ly.daybook.coroutines.asFlow
import io.github.kr9ly.daybook.coroutines.changesAsFlow
import io.github.kr9ly.daybook.float
import io.github.kr9ly.daybook.getDaybookSharedPreferences
import io.github.kr9ly.daybook.string
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * レーン7 (ドキュメント・KDoc整合)。
 * docs/android.md「型安全 API と Flow」節のコード例を、daybook-coroutines
 * androidHostTest (:daybook の SharedPreferences 互換 API + daybook-coroutines の
 * PreferenceProperty.asFlow / SharedPreferences.changesAsFlow) へ移植する。
 * :daybook モジュール自身は daybook-coroutines に依存しないため、
 * このファイルは daybook-coroutines 側に置く。
 *
 * 実装ソースは読まない。契約は docs/android.md / API.md / public-api-extract.md のみを根拠にする。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AdversarialAndroidFlowDocsCompileTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private enum class Theme { SYSTEM, DARK, LIGHT }

    private class Settings(prefs: android.content.SharedPreferences) {
        var darkMode by prefs.boolean("dark_mode", default = false)
        var nickname by prefs.string("nickname")

        val fontScalePref = prefs.float("font_scale", default = 1.0f)
        var fontScale by fontScalePref

        var theme by prefs.string("theme", default = Theme.SYSTEM.name)
            .map(decode = Theme::valueOf, encode = Theme::name)
            .catch { Theme.SYSTEM }
    }

    @Test
    fun androidDocs_typedApiAndFlow() = runTest(UnconfinedTestDispatcher()) {
        val prefs = context.getDaybookSharedPreferences("typed-settings-flow")
        val settings = Settings(prefs)

        settings.darkMode = true // putBoolean + apply

        val fontScaleReceived = mutableListOf<Float>()
        val keyReceived = mutableListOf<String?>()
        val fontScaleJob = launch { settings.fontScalePref.asFlow().toList(fontScaleReceived) }
        val changesJob = launch { prefs.changesAsFlow().toList(keyReceived) }

        settings.fontScale = 2.0f

        assertEquals(listOf(1.0f, 2.0f), fontScaleReceived)
        assertEquals(listOf("font_scale"), keyReceived)

        fontScaleJob.cancel()
        changesJob.cancel()
    }
}
