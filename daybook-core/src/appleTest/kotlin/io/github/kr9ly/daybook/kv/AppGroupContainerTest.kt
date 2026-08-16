package io.github.kr9ly.daybook.kv

import io.github.kr9ly.daybook.io.createTempDirectory
import platform.Foundation.NSUserDefaults
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * App Group 連携のうち、シミュレータのテストハーネスで検証できる範囲の検証。
 *
 * App Group スタイルの suite 名（`group.…`）を持つ NSUserDefaults からの取り込みは
 * app bundle なしでも実 API で動くため、ここで常時検証する。
 *
 * コンテナ実パス（containerURLForSecurityApplicationGroupIdentifier）はここでは検証できない:
 * K/N の Gradle テストは app bundle を持たない実行ファイルを simctl spawn で走らせるため
 * アプリの identity がなく、コンテナ解決が null を返す（2026-08-16 に CI で実測）。
 * コンテナ実パス上のストア動作と実 2 プロセス共有（コールドリード + watcher キャッチアップ）は
 * Xcode ホストアプリのハーネス（ios-harness/ + daybook-ios-harness、device-test.yml の
 * ios-host-app-test）で常時検証している。multiProcess 保証の格上げは実機検証待ちで据え置き —
 * KMP-2.0.md を参照。
 */
class AppGroupContainerTest {

    private object Schema : DaybookSchema("app-group") {
        val label = string("label")
    }

    private val groupId = "group.io.github.kr9ly.daybook.tests"

    @AfterTest
    fun tearDown() {
        DaybookRegistry.resetForTesting()
        NSUserDefaults(suiteName = groupId).removeObjectForKey("src_label")
    }

    @Test
    fun migration_fromAppGroupStyleSuite() {
        NSUserDefaults(suiteName = groupId).setObject("from app group", forKey = "src_label")

        val daybook = Daybook.open(createTempDirectory().path, Schema) {
            migrations = listOf(
                NSUserDefaultsMigrationSource {
                    migrate(UserDefaultsSuite.named(groupId).string("src_label"), into = Schema.label)
                },
            )
        }

        assertEquals("from app group", daybook.getString("label", null))
    }
}
