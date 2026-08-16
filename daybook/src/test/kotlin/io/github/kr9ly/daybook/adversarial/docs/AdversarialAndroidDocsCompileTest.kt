package io.github.kr9ly.daybook.adversarial.docs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kr9ly.daybook.DaybookOptions
import io.github.kr9ly.daybook.DaybookPreferencesCache
import io.github.kr9ly.daybook.boolean
import io.github.kr9ly.daybook.exportAllDaybookToSharedPreferences
import io.github.kr9ly.daybook.exportDaybookToSharedPreferences
import io.github.kr9ly.daybook.float
import io.github.kr9ly.daybook.getDaybookSharedPreferences
import io.github.kr9ly.daybook.getDefaultDaybookSharedPreferences
import io.github.kr9ly.daybook.importAllSharedPreferencesIntoDaybook
import io.github.kr9ly.daybook.importSharedPreferencesIntoDaybook
import io.github.kr9ly.daybook.kv.DaybookSchema
import io.github.kr9ly.daybook.kv.MigrationMode
import io.github.kr9ly.daybook.openDaybook
import io.github.kr9ly.daybook.string
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * レーン7 (ドキュメント・KDoc整合)。
 * README.md の SharedPreferences 置き換え例と docs/android.md の全コード例
 * (取得と読み書き / SharedPreferences からの移行 / マルチプロセス / 型安全 API)
 * を daybook (:daybook モジュール) のコンパイル・実行可能なテストへ移植する。
 *
 * daybook-test を使う例と Flow を伴う例 (asFlow / changesAsFlow) は別モジュールへ分離する
 * (:daybook は daybook-test / daybook-coroutines のいずれにも依存しないため、
 * 同じテストソースセットに同居できない — 環境制約として報告する)。
 *
 * 実装ソースは読まない。契約は README.md / docs/android.md / docs/android-to-kmp.md /
 * docs/ios-android-to-kmp.md / API.md / public-api-extract.md のみを根拠にする。
 */
@RunWith(RobolectricTestRunner::class)
class AdversarialAndroidDocsCompileTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        DaybookPreferencesCache.resetForTesting()
    }

    // ---- README.md: SharedPreferences をそのまま置き換える ----

    @Test
    fun readmeReplacement() {
        val prefs = context.getDaybookSharedPreferences("settings")
        assertEquals(null, prefs.getString("nickname", null))
    }

    // ---- docs/android.md: 取得と読み書き ----

    @Test
    fun androidDocs_getAndReadWrite() {
        val prefs = context.getDaybookSharedPreferences("settings")
        val default = context.getDefaultDaybookSharedPreferences()

        prefs.edit().putString("nickname", "alice").putInt("count", 1).apply()
        val nickname = prefs.getString("nickname", null)

        assertEquals("alice", nickname)
        assertEquals(null, default.getString("unrelated", null))
    }

    // ---- docs/android.md: SharedPreferences からの移行 ----

    @Test
    fun androidDocs_migration() {
        // 透過: 初回生成時に同名のフレームワーク prefs を一度だけ取り込む
        val prefs = context.getDaybookSharedPreferences(
            "settings-migrate",
            DaybookOptions(importFromSharedPreferences = true),
        )
        assertEquals(null, prefs.getString("nickname", null))

        // 明示: 個別・一括の import / export
        context.importSharedPreferencesIntoDaybook("settings-migrate")
        context.importAllSharedPreferencesIntoDaybook()
        context.exportDaybookToSharedPreferences("settings-migrate")
        context.exportAllDaybookToSharedPreferences()
    }

    // ---- docs/android.md: マルチプロセス ----

    @Test
    fun androidDocs_multiProcess() {
        val shared = context.getDaybookSharedPreferences("shared", DaybookOptions(multiProcess = true))
        assertEquals(null, shared.getString("k", null))
    }

    // ---- docs/android.md: 型安全 API (Flow を除く) ----

    private enum class Theme { SYSTEM, DARK, LIGHT }

    @Test
    fun androidDocs_typedApi() {
        val prefs = context.getDaybookSharedPreferences("typed-settings")

        class Settings(prefs: android.content.SharedPreferences) {
            var darkMode by prefs.boolean("dark_mode", default = false)
            var nickname by prefs.string("nickname") // default なし = nullable、null の代入で削除

            val fontScalePref = prefs.float("font_scale", default = 1.0f)
            var fontScale by fontScalePref

            var theme by prefs.string("theme", default = Theme.SYSTEM.name)
                .map(decode = Theme::valueOf, encode = Theme::name)
                .catch { Theme.SYSTEM }
        }

        val settings = Settings(prefs)
        settings.darkMode = true
        assertEquals(true, settings.darkMode)
        assertEquals(Theme.SYSTEM, settings.theme)
    }

    // ---- docs/android-to-kmp.md パス B: キー整理しながら新ストアへ一括移行 ----

    private object NewSettingsSchema : DaybookSchema(name = "path-b-settings") {
        val darkMode = boolean("dark_mode")
        val launchCount = long("launch_count")
    }

    private object Legacy {
        val appPrefs = io.github.kr9ly.daybook.SharedPreferencesFile("path-b-app-prefs")
        val darkModeV1 = appPrefs.boolean("dark_mode_v1")
        val count = appPrefs.long("counter")
    }

    @Test
    fun androidToKmpDocs_pathB() {
        val source = io.github.kr9ly.daybook.SharedPreferencesMigrationSource(context, mode = MigrationMode.STRICT) {
            migrate(Legacy.darkModeV1, into = NewSettingsSchema.darkMode)
            migrate(Legacy.count, into = NewSettingsSchema.launchCount)
        }
        val daybook = context.openDaybook(NewSettingsSchema) { migrations = listOf(source) }
        // 元キー (dark_mode_v1 / counter) は一度も設定されていないため正常系スキップ:
        // 取り込まれず、getter は default をそのまま返す
        assertEquals(true, daybook.getBoolean("dark_mode", default = true))
    }

    // ---- docs/ios-android-to-kmp.md: Android 側の写像宣言 ----

    private object UnifiedSchema : DaybookSchema(name = "ios-android-settings") {
        val darkMode = boolean("dark_mode")
        val userName = string("user_name")
    }

    private object UnifiedSchemaLenient : DaybookSchema(name = "ios-android-settings-lenient") {
        val darkMode = boolean("dark_mode")
        val userName = string("user_name")
    }

    private object LegacyAndroid {
        val appPrefs = io.github.kr9ly.daybook.SharedPreferencesFile("legacy-app_prefs")
        val darkModeV1 = appPrefs.boolean("dark_mode_v1")
        val userName = appPrefs.string("user")

        // ドキュメントのコメントどおり、型不一致 (Float -> Double) はコンパイルエラーになるはず。
        // Settings.fontScale が Double 宣言のスキーマに対して Float の元キーを渡す行は、
        // ドキュメントの主張を裏切らないことを確認するためにコメントアウトで保持する:
        // val fontScale = appPrefs.float("font_scale")
    }

    @Test
    fun iosAndroidToKmpDocs_androidMapping() {
        val source = io.github.kr9ly.daybook.SharedPreferencesMigrationSource(context) {
            migrate(LegacyAndroid.darkModeV1, into = UnifiedSchema.darkMode)
            migrate(LegacyAndroid.userName, into = UnifiedSchema.userName)
        }
        val daybook = context.openDaybook(UnifiedSchema) { migrations = listOf(source) }
        assertEquals(null, daybook.getString("user_name", default = null))
    }

    // ---- docs/ios-android-to-kmp.md: モードの運用 (STRICT / LENIENT) ----

    @Test
    fun iosAndroidToKmpDocs_lenientOnSkipped() {
        var loggedMessage: String? = null
        val source = io.github.kr9ly.daybook.SharedPreferencesMigrationSource(context, mode = MigrationMode.LENIENT) {
            onSkipped =
                { skip -> loggedMessage = "migration skipped: ${skip.fileName}/${skip.key} (${skip.expectedType})" }
            migrate(LegacyAndroid.darkModeV1, into = UnifiedSchemaLenient.darkMode)
        }
        context.openDaybook(UnifiedSchemaLenient) {
            migrations = listOf(source)
        }
        // 元キー欠損は正常系スキップなので onSkipped は呼ばれない想定（欠損は「型不一致」ではない）
        assertEquals(null, loggedMessage)
    }
}
