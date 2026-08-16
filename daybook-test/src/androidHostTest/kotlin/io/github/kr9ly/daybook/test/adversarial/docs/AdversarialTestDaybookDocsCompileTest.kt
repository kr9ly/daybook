package io.github.kr9ly.daybook.test.adversarial.docs

import io.github.kr9ly.daybook.kv.DaybookSchema
import io.github.kr9ly.daybook.kv.property
import io.github.kr9ly.daybook.test.RecordedCommit
import io.github.kr9ly.daybook.test.TestDaybook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * レーン7 (ドキュメント・KDoc整合)。
 * docs/common-api.md「テスト (daybook-test)」節と docs/android.md「アプリのテスト
 * (daybook-test)」節のコード例を daybook-test androidHostTest へ移植する
 * (:daybook モジュール自身は daybook-test に依存しないため、このファイルは daybook-test 側に置く)。
 *
 * 実装ソースは読まない。契約は docs/common-api.md / docs/android.md / API.md /
 * public-api-extract.md のみを根拠にする。
 */
class AdversarialTestDaybookDocsCompileTest {

    // ---- docs/common-api.md: テスト (daybook-test) — 共通 API 側 ----

    private object Settings : DaybookSchema(name = "docs-settings") {
        val userName = string("user_name")
    }

    private class SettingsRepository(daybook: io.github.kr9ly.daybook.kv.Daybook) {
        var userName by daybook.property(Settings.userName)

        fun updateProfile(name: String) {
            userName = name
        }
    }

    @Test
    fun commonApiDocs_testDaybook() {
        val testDaybook = TestDaybook() // テストごとに new すれば隔離される
        val daybook = testDaybook.getDaybook(Settings) // 本番と同じスキーマ宣言を共有する
        val repository = SettingsRepository(daybook)

        // 通知は同期配送: edit が返った時点でリスナー・Flow まで届いている（決定的アサーション）
        repository.updateProfile("alice")

        // commit 粒度の書き込み記録: 「関連キーが 1 つの edit にまとまっているか」を直接検証できる
        assertEquals(
            listOf(RecordedCommit(clearRequested = false, changes = mapOf("user_name" to "alice"))),
            testDaybook.commits("docs-settings"),
        )

        // 失敗注入: 書き込み失敗（IOException）のエラーハンドリングをテストする
        testDaybook.failNextWrite("docs-settings")
        var threw = false
        try {
            daybook.edit { putString("user_name", "bob") }
        } catch (e: java.io.IOException) {
            threw = true
        }
        assertEquals(true, threw)
    }

    // ---- docs/android.md: アプリのテスト (daybook-test) — SharedPreferences 互換 API 側 ----

    @Test
    fun androidDocs_testDaybookSharedPreferences() {
        val daybook = TestDaybook() // テストごとに new すれば隔離される（reset 不要）
        val prefs = daybook.getSharedPreferences("settings") // アプリのコードに注入する

        // 通知は同期配送: commit() が返った時点でリスナー・Flow まで届いている（決定的アサーション）
        prefs.edit().putString("name", "alice").apply()

        // commit 粒度の書き込み記録: 「関連キーが 1 つの edit にまとまっているか」を直接検証できる
        assertEquals(
            listOf(RecordedCommit(clearRequested = false, changes = mapOf("name" to "alice"))),
            daybook.commits("settings"),
        )

        // 失敗注入: commit() == false / apply 破棄のエラーハンドリングをテストする
        daybook.failNextWrite("settings")
        assertFalse(prefs.edit().putString("name", "bob").commit())
    }
}
