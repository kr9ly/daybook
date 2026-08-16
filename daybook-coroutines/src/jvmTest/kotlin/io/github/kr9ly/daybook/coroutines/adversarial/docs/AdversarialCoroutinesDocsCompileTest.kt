package io.github.kr9ly.daybook.coroutines.adversarial.docs

import io.github.kr9ly.daybook.coroutines.asFlow
import io.github.kr9ly.daybook.coroutines.changesAsFlow
import io.github.kr9ly.daybook.kv.Daybook
import io.github.kr9ly.daybook.kv.DaybookSchema
import io.github.kr9ly.daybook.kv.property
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * レーン7 (ドキュメント・KDoc整合)。
 * README.md クイックスタート後半 (asFlow) と docs/common-api.md の Flow セクションを
 * daybook-coroutines のコンパイル可能なテストへ移植する。
 *
 * 実装ソースは読まず、README.md / docs/common-api.md / public-api-extract.md の
 * シグネチャのみを根拠にする。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdversarialCoroutinesDocsCompileTest {

    private fun tempDir(): String = Files.createTempDirectory("adversarial-coroutines-docs").toString()

    private object Settings : DaybookSchema(name = "flow-docs") {
        val darkMode = boolean("dark_mode")
        val fontScale = double("font_scale")
    }

    // ---- README.md クイックスタート: daybook.property(...).asFlow() ----

    @Test
    fun readmeQuickstart_asFlow() = runTest {
        val daybook = Daybook.open(tempDir(), Settings)
        val flow = daybook.property(Settings.darkMode, default = false).asFlow()
        assertEquals(false, flow.first())
    }

    // ---- docs/common-api.md: Flow (daybook-coroutines) ----

    @Test
    fun commonApiDocs_propertyAsFlowAndChangesAsFlow() = runTest {
        val daybook = Daybook.open(tempDir(), Settings)
        val fontScalePref = daybook.property(Settings.fontScale, default = 1.0)

        // asFlow は状態の観測: collect 時に現在値を発火 → 変更のたび再読して発火
        assertEquals(1.0, fontScalePref.asFlow().first())

        // changesAsFlow は操作の観測: 変更キーのイベント流。collect 開始後の変更だけが流れる
        val changes = daybook.changesAsFlow().produceIn(backgroundScope).also { yield() }
        daybook.edit { putDouble("font_scale", 2.0) }
        assertEquals("font_scale", changes.receive())
    }
}
