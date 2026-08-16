package io.github.kr9ly.daybook

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kr9ly.daybook.kv.Daybook
import io.github.kr9ly.daybook.kv.DaybookSchema
import io.github.kr9ly.daybook.kv.Durability
import io.github.kr9ly.daybook.kv.MigrationMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 敵対的テスト — レーン 2: スキーマとストア束縛（:daybook 固有: SharedPreferences 互換 API との
 * 束縛境界）。
 *
 * 契約の根拠:
 * - DaybookOpen.kt / Daybook.Companion.open KDoc（public-api-extract.md 経由）:
 *   「SharedPreferences 互換 API が先に同名のストアを生成していた場合、最初のスキーマ付き
 *   open がそのストアにスキーマを採用させ、以後は同じ検査に載る」
 * - SharedPreferencesMigrationSource KDoc: 型付きマイグレーションソースの宛先は
 *   開こうとしているスキーマに属していなければならない（MigrationSource.kt の
 *   SchemaTargetedMigrationSource 契約）
 * - DaybookPreferences.kt KDoc: SharedPreferences 互換 API の durability は常に既定
 *   （ASYNC）で、SYNC で開いている名前とは不一致で例外
 */
@RunWith(RobolectricTestRunner::class)
class AdversarialSchemaBindingAndroidTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private object SettingsSchema : DaybookSchema("settings") {
        val darkMode = boolean("dark_mode")
    }

    @After
    fun tearDown() {
        DaybookPreferencesCache.resetForTesting()
    }

    @Test
    fun prefsApiOpensFirst_thenSchemaOpen_adoptsSchemaOnTheSameStoreInstance() {
        // 攻撃: 「最初のスキーマ付き open がそのストアにスキーマを採用させる」契約を、
        // schema プロパティの中身（採用したスキーマがまさにこのオブジェクトか）まで検査する。
        // DualApiTest.bothApis_shareTheSameStore_whenPrefsApiOpensFirst は値の相互可視性しか
        // 見ておらず、daybook.schema の同一性は未検証だった。
        context.getDaybookSharedPreferences("settings")
        val daybook = context.openDaybook(SettingsSchema)
        assertSame(SettingsSchema, daybook.schema)
    }

    @Test
    fun prefsApiOpensFirst_thenSecondSchemaOpenWithDifferentObject_throws() {
        // 攻撃: prefs API が先にストアを生成したケースでも、後続の「別スキーマオブジェクトでの
        // open」の fail-fast が生きているか（採用フローが検査をバイパスしていないか）。
        context.getDaybookSharedPreferences("settings")
        context.openDaybook(SettingsSchema)
        val anotherSchemaObject = object : DaybookSchema("settings") {
            val darkMode = boolean("dark_mode")
        }
        assertThrows(IllegalArgumentException::class.java) {
            context.openDaybook(anotherSchemaObject)
        }
    }

    @Test
    fun schemaOpenFirst_thenPrefsApiWithSyncDurabilityStore_isRejected_reverseOrder() {
        // 攻撃: DualApiTest.prefsApi_onSyncDurabilityStore_isRejected は
        // 「openDaybook(SYNC) が先・prefs が後」の順序だけを見ている。
        // 逆順（prefs が先に既定 ASYNC でストアを生成 → 後から SYNC で openDaybook）でも
        // 同じ不一致検査が対称に効くかを確認する。
        context.getDaybookSharedPreferences("settings") // 既定 ASYNC でストア生成
        assertThrows(IllegalArgumentException::class.java) {
            context.openDaybook(SettingsSchema) { durability = Durability.SYNC }
        }
    }

    @Test
    fun sharedPreferencesMigrationSource_targetingForeignSchema_isRejectedAtOpen() {
        // 攻撃: SharedPreferencesMigrationSource（:daybook の公開ラッパー）経由でも、
        // 宛先キーが開こうとしているスキーマに属さない場合に即例外になるか。
        // MigrationTargetValidationTest（daybook-core）は core の内部 Fake ソースで
        // 直接検査済みだが、公開ラッパーが同じ検査に正しく載っているかは別問題。
        val legacyFile = SharedPreferencesFile("legacy_prefs")
        val legacyFlag = legacyFile.boolean("flag")

        val foreignSchema = object : DaybookSchema("foreign-target") {
            val flag = boolean("flag")
        }

        val source = SharedPreferencesMigrationSource(context, mode = MigrationMode.STRICT) {
            migrate(legacyFlag, into = foreignSchema.flag) // SettingsSchema には属さない宛先
        }

        assertThrows(IllegalArgumentException::class.java) {
            context.openDaybook(SettingsSchema) { migrations = listOf(source) }
        }
    }

    @Test
    fun defaultDaybookSharedPreferences_name_matchesSchemaConventionExactly() {
        // 契約(DaybookOpen.kt): 「デフォルトの SharedPreferences（getDefaultDaybookSharedPreferences）
        // とストアを共有したい場合は、スキーマの宣言名を <packageName>_preferences にすること」
        // 攻撃: パッケージ名の大文字小文字や前後の余分な文字があるスキーマ名では
        // 共有が成立しない（=文字列完全一致が要求される）ことの境界確認。
        val almostRightSchema = object : DaybookSchema("${context.packageName}_preferences_x") {}
        val daybook = context.openDaybook(almostRightSchema)
        val defaultPrefs = context.getDefaultDaybookSharedPreferences()
        daybook.edit { putBoolean("flag", true) }
        // 名前が完全一致していないので、デフォルト prefs 側には見えないはず
        assertEquals(false, defaultPrefs.getBoolean("flag", false))
    }
}
