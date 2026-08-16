package io.github.kr9ly.daybook.adversarial.docs

import io.github.kr9ly.daybook.kv.Daybook
import io.github.kr9ly.daybook.kv.DaybookChangeListener
import io.github.kr9ly.daybook.kv.DaybookKey
import io.github.kr9ly.daybook.kv.DaybookSchema
import io.github.kr9ly.daybook.kv.Durability
import io.github.kr9ly.daybook.kv.MigrationEnvironment
import io.github.kr9ly.daybook.kv.MigrationSource
import io.github.kr9ly.daybook.kv.property
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * レーン7 (ドキュメント・KDoc整合) の敵対的テスト。
 *
 * README.md / docs/common-api.md / public-api-extract.md の commonMain 側コード例を
 * 実際にコンパイル・実行できる形へ移植する。合格基準は「コンパイルが通ること」で、
 * ここでは追加で最小限の実行も行い、ドキュメントの主張どおりに動くかも確認する。
 *
 * 実装ソース(各モジュールのプラットフォーム別ソースセット配下)は読まず、
 * 契約ドキュメントと public-api-extract.md のシグネチャのみを根拠にする。
 */
class AdversarialCommonApiDocsCompileTest {

    private fun tempDir(): String = Files.createTempDirectory("adversarial-docs").toString()

    // ---- README.md クイックスタート (core 部分。asFlow() は daybook-coroutines 側で別途検証) ----

    private object QuickstartSettings : DaybookSchema(name = "settings") {
        val darkMode = boolean("dark_mode")
        val userName = string("user_name")
    }

    @Test
    fun readmeQuickstart_coreApi() {
        val daybook = Daybook.open(tempDir(), QuickstartSettings)

        var darkMode by daybook.property(QuickstartSettings.darkMode, default = false)
        darkMode = true

        daybook.edit {
            putBoolean("dark_mode", false)
            putString("user_name", "alice")
        }

        daybook.addChangeListener { _, _ -> }

        assertEquals(false, daybook.getBoolean("dark_mode", default = true))
        assertEquals("alice", daybook.getString("user_name", default = null))
    }

    // ---- docs/common-api.md: スキーマ宣言 ----

    private object Settings : DaybookSchema(name = "settings-full") {
        val darkMode = boolean("dark_mode")
        val fontScale = double("font_scale")
        val userName = string("user_name")
        val theme = string("theme")
        val tags = stringSet("tags")
    }

    // ---- docs/common-api.md: ストアを開く（オプション付き） ----

    @Test
    fun openWithOptions() {
        val source = object : MigrationSource {
            override val id: String = "noop"
            override fun read(environment: MigrationEnvironment): Map<String, Any> = emptyMap()
        }

        val daybook = Daybook.open(tempDir(), Settings) {
            durability = Durability.SYNC // 既定は ASYNC
            multiProcess = false // ドキュメント例は true だが単一ディレクトリでの単体実行のため false に固定
            migrations = listOf(source)
        }

        assertEquals(false, daybook.contains("darkMode-not-a-real-key"))
    }

    // ---- docs/common-api.md: 読み書き ----

    @Test
    fun readWrite() {
        val daybook = Daybook.open(tempDir(), Settings)

        val name = daybook.getString("user_name", default = null)
        val scale = daybook.getDouble("font_scale", default = 1.0)
        val hasTags = daybook.contains("tags")
        val allKeys = daybook.keys

        daybook.edit {
            putString("user_name", "alice")
            putDouble("font_scale", 1.5)
            remove("tags")
        }

        assertEquals(null, name)
        assertEquals(1.0, scale)
        assertEquals(false, hasTags)
        assertEquals(emptySet(), allKeys)
    }

    // ---- docs/common-api.md: 型安全プロパティ ----

    private class SettingsRepository(daybook: Daybook) {
        var darkMode by daybook.property(Settings.darkMode, default = false)
        var userName by daybook.property(Settings.userName) // default なし = nullable

        val fontScalePref = daybook.property(Settings.fontScale, default = 1.0)
        var fontScale by fontScalePref
    }

    @Test
    fun typedProperty() {
        val daybook = Daybook.open(tempDir(), Settings)
        val repo = SettingsRepository(daybook)
        repo.darkMode = true
        repo.userName = "bob"
        repo.fontScale = 2.0
        assertEquals(true, repo.darkMode)
        assertEquals("bob", repo.userName)
        assertEquals(2.0, repo.fontScale)
    }

    // ---- docs/common-api.md: 値のアダプタ (map / catch) ----

    private enum class Theme { SYSTEM, DARK, LIGHT }

    @Test
    fun mapAndCatch() {
        val daybook = Daybook.open(tempDir(), Settings)
        var theme by daybook.property(Settings.theme, default = Theme.SYSTEM.name)
            .map(decode = Theme::valueOf, encode = Theme::name)
            .catch { Theme.SYSTEM }

        assertEquals(Theme.SYSTEM, theme)
        theme = Theme.DARK
        assertEquals(Theme.DARK, theme)
    }

    // ---- docs/common-api.md: 変更リスナー ----

    @Test
    fun changeListener() {
        val daybook = Daybook.open(tempDir(), Settings)
        val received = mutableListOf<Pair<String, Any?>>()
        val listener = DaybookChangeListener { key, newValue -> received += key to newValue }
        daybook.addChangeListener(listener)
        daybook.removeChangeListener(listener)
    }

    // ---- docs/common-api.md: マルチプロセス ----

    private object SharedSchema : DaybookSchema(name = "shared-docs") {
        val flag = boolean("flag")
    }

    @Test
    fun multiProcessOption() {
        val shared = Daybook.open(tempDir(), SharedSchema) { multiProcess = true }
        assertEquals(false, shared.getBoolean("flag", default = false))
    }

    // ---- docs/common-api.md: マイグレーション ----

    private object MigrationTargetSchema : DaybookSchema(name = "migration-docs") {
        val userName = string("user_name")
    }

    @Test
    fun migrationsOption() {
        val source = object : MigrationSource {
            override val id: String = "seed"
            override fun read(environment: MigrationEnvironment): Map<String, Any> =
                mapOf("user_name" to "migrated")
        }
        val daybook = Daybook.open(tempDir(), MigrationTargetSchema) {
            migrations = listOf(source)
        }
        assertEquals("migrated", daybook.getString("user_name", default = null))
    }

    // ---- public-api-extract.md: DaybookSchema の KDoc 例 ----

    private object KDocSchemaExample : DaybookSchema(name = "kdoc-schema-example") {
        val darkMode = boolean("dark_mode")
        val fontScale = double("font_scale")
        val userName = string("user_name")
    }

    @Test
    fun daybookSchemaKDocExample() {
        val daybook = Daybook.open(tempDir(), KDocSchemaExample)
        var darkMode by daybook.property(KDocSchemaExample.darkMode, default = false)
        darkMode = true
        assertEquals(true, darkMode)
    }

    // ---- public-api-extract.md: DaybookProperty の KDoc 例 ----

    private object KDocPropertyExample : DaybookSchema("kdoc-property-example") {
        val darkMode = boolean("dark_mode")
        val fontScale = double("font_scale")
    }

    private class AppSettings(daybook: Daybook) {
        var darkMode by daybook.property(KDocPropertyExample.darkMode, default = false)

        val fontScalePref = daybook.property(KDocPropertyExample.fontScale, default = 1.0)
        var fontScale by fontScalePref
    }

    @Test
    fun daybookPropertyKDocExample() {
        val daybook = Daybook.open(tempDir(), KDocPropertyExample)
        val settings = AppSettings(daybook)
        settings.darkMode = true
        settings.fontScale = 3.0
        assertEquals(true, settings.darkMode)
        assertEquals(3.0, settings.fontScale)
    }

    // ---- public-api-extract.md: DaybookProperty.map / catch の KDoc 例 ----

    private object KDocMapCatchExample : DaybookSchema(name = "kdoc-map-catch-example") {
        val theme = string("theme")
    }

    @Test
    fun daybookPropertyMapCatchKDocExample() {
        val daybook = Daybook.open(tempDir(), KDocMapCatchExample)
        var theme by daybook.property(KDocMapCatchExample.theme, default = Theme.SYSTEM.name)
            .map(decode = Theme::valueOf, encode = Theme::name)
            .catch { Theme.SYSTEM }
        assertEquals(Theme.SYSTEM, theme)
    }

    // ---- ストア束縛の攻撃: 別スキーマオブジェクトのキーを渡すと即例外 (DaybookSchema.kt KDoc の主張) ----

    private object SchemaA : DaybookSchema(name = "bind-a") {
        val value = boolean("value")
    }

    private object SchemaB : DaybookSchema(name = "bind-b") {
        val value = boolean("value")
    }

    @Test
    fun crossSchemaKeyIsRejected() {
        val daybookA = Daybook.open(tempDir(), SchemaA)
        var threw = false
        try {
            @Suppress("UNUSED_VARIABLE")
            val prop = daybookA.property(SchemaB.value as DaybookKey<Boolean>, default = false)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertEquals(true, threw)
    }
}
