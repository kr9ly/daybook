package io.github.kr9ly.daybook.adversarial

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kr9ly.daybook.DaybookPreferencesCache
import io.github.kr9ly.daybook.SharedPreferencesFile
import io.github.kr9ly.daybook.SharedPreferencesMigrationSource
import io.github.kr9ly.daybook.kv.DaybookSchema
import io.github.kr9ly.daybook.kv.MigrationMode
import io.github.kr9ly.daybook.openDaybook
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 敵対的テスト: [SharedPreferencesMigrationSource] を複数・独立に組み合わせたときの契約
 * （レーン 3: マイグレーション、:daybook 部分）。
 *
 * 情報源は README.md / DESIGN.md / API.md / docs 配下の各ガイド / 公開 API 抽出（KDoc）のみ。
 * 実装ソース（src/main/）は読んでいない。
 *
 * [SharedPreferencesMigrationSource] の KDoc は「宛先キーの衝突（複数のエントリが同じ宛先に
 * 書く…）は…モードに関係なく即例外にする」と言うが、これは 1 つのソース（1 つの
 * configure ブロック）の中の宣言に対する契約であり、[DaybookOpenOptions.migrations] に
 * 複数の独立した SharedPreferencesMigrationSource インスタンス（id が異なる）を並べたときに
 * 同じ宛先へ書き込むケースまでは対象にしていない。
 *
 * また [SharedPreferencesMigrationBuilder.onSkipped] の KDoc は「LENIENT でスキップされた
 * ときに呼ばれる」とだけ言い、コールバック自体が例外を投げた場合の挙動は書かれていない
 * （未定義挙動）。
 */
@RunWith(RobolectricTestRunner::class)
class AdversarialMigrationSourceContractTest {

    private object Settings : DaybookSchema("settings") {
        val darkMode = boolean("dark_mode")
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val appPrefsA = SharedPreferencesFile("app_prefs_a")
    private val appPrefsB = SharedPreferencesFile("app_prefs_b")

    @After
    fun tearDown() {
        DaybookPreferencesCache.resetForTesting()
    }

    private fun prefs(file: SharedPreferencesFile) =
        context.getSharedPreferences(file.fileName, Context.MODE_PRIVATE)

    /**
     * 攻撃: 独立した 2 つの SharedPreferencesMigrationSource（id が異なる）を migrations
     * リストに並べ、両方が同じ宛先キー Settings.darkMode へ写像する。
     *
     * ドキュメント上「宛先キーの衝突」の即例外契約は 1 ソース内の宣言にしか及ばないため、
     * ここでは即例外にならず、migrations リストの順序どおり後勝ちになると予想する。
     * これがそのまま起きるなら「複数ソースの独立指定によるサイレントな上書き」という
     * ドキュメント上未定義の抜け道が実在することの確認になる。
     */
    @Test
    fun independentSources_targetingSameKey_arePermitted_andLastSourceWins() {
        prefs(appPrefsA).edit().putBoolean("dark_mode_v1", true).commit()
        prefs(appPrefsB).edit().putBoolean("dark_mode_v1", false).commit()

        val daybook = context.openDaybook(Settings) {
            migrations = listOf(
                SharedPreferencesMigrationSource(context, id = "source-a") {
                    migrate(appPrefsA.boolean("dark_mode_v1"), into = Settings.darkMode)
                },
                SharedPreferencesMigrationSource(context, id = "source-b") {
                    migrate(appPrefsB.boolean("dark_mode_v1"), into = Settings.darkMode)
                },
            )
        }

        assertEquals(
            "宛先衝突の即例外はソース単体の宣言にしか及ばないため、後続ソース（source-b）の" +
                "値で上書きされるはず。異なる結果が出れば発見。",
            false,
            daybook.getBoolean("dark_mode", true),
        )
    }

    /**
     * 攻撃: LENIENT モードで onSkipped コールバック自身が例外を投げるとどうなるか。
     * KDoc はコールバックが呼ばれることしか約束していない。open() 全体が失敗するのか、
     * スキップ自体は成立してコールバックの例外だけが伝播するのか、あるいは黙って握り
     * つぶされるのかはドキュメントに書かれていない未定義挙動。
     */
    @Test
    fun onSkippedCallback_throwing_propagatesFromOpen() {
        prefs(appPrefsA).edit().putString("dark_mode_v1", "not-a-boolean").commit()

        var thrown: Throwable? = null
        try {
            context.openDaybook(Settings) {
                migrations = listOf(
                    SharedPreferencesMigrationSource(context, mode = MigrationMode.LENIENT) {
                        onSkipped = { throw IllegalStateException("boom from onSkipped") }
                        migrate(appPrefsA.boolean("dark_mode_v1"), into = Settings.darkMode)
                    },
                )
            }
        } catch (e: Throwable) {
            thrown = e
        }

        println("AdversarialMigrationSourceContractTest: onSkipped から投げた例外の実際の挙動 = $thrown")
        // 黙って握りつぶされる（thrown == null）のは、エラーハンドリングのバグを検出不能に
        // するため最も望ましくない挙動。少なくとも例外が open から観測できることを期待値とする。
        // 期待に反して thrown が null なら、それ自体が「LENIENT のエラー処理が握りつぶされる」
        // という重大な発見になる。
        assertTrue(
            "onSkipped が投げた例外が黙って握りつぶされていないこと（実際の例外: $thrown）",
            thrown != null,
        )
    }
}
