package io.github.kr9ly.daybook.settings.adversarial.docs

import com.russhwolf.settings.ExperimentalSettingsApi
import com.russhwolf.settings.ObservableSettings
import com.russhwolf.settings.coroutines.FlowSettings
import io.github.kr9ly.daybook.kv.Daybook
import io.github.kr9ly.daybook.kv.DaybookSchema
import io.github.kr9ly.daybook.settings.DaybookFlowSettings
import io.github.kr9ly.daybook.settings.DaybookSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * レーン7 (ドキュメント・KDoc整合)。
 * docs/common-api.md「multiplatform-settings アダプタ」節のコード例を移植する。
 *
 * ドキュメントのコードブロックは以下のとおりで、@OptIn は本文コメントで言及されるのみで
 * コード上には現れない:
 *
 * ```kotlin
 * val settings: ObservableSettings = DaybookSettings(daybook)
 * val flowSettings: FlowSettings = DaybookFlowSettings(daybook)   // @ExperimentalSettingsApi
 * ```
 *
 * DaybookFlowSettings は @ExperimentalSettingsApi (multiplatform-settings の @RequiresOptIn)
 * が付与されているため、このコードをそのまま貼り付けても呼び出し側で opt-in しない限り
 * コンパイルエラーになる。ここでは実際にコンパイルを通すために @OptIn を関数に付与しており、
 * この不足はドキュメント側の曖昧さとして別途報告する。
 *
 * 実装ソースは読まない。契約は docs/common-api.md / public-api-extract.md のみを根拠にする。
 */
@OptIn(ExperimentalSettingsApi::class, ExperimentalCoroutinesApi::class)
class AdversarialSettingsDocsCompileTest {

    private object Settings : DaybookSchema(name = "settings-adapter-docs") {
        val darkMode = boolean("dark_mode")
    }

    private fun tempDir(): String = Files.createTempDirectory("adversarial-settings-docs").toString()

    @Test
    fun commonApiDocs_settingsAdapter() = runTest {
        val daybook: Daybook = Daybook.open(tempDir(), Settings)

        val settings: ObservableSettings = DaybookSettings(daybook)
        val flowSettings: FlowSettings = DaybookFlowSettings(daybook)

        settings.putBoolean("dark_mode", true)
        assertEquals(true, settings.getBoolean("dark_mode", false))
        assertEquals(true, flowSettings.getBooleanFlow("dark_mode", false).first())
    }
}
