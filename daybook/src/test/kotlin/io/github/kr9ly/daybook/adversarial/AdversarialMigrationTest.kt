package io.github.kr9ly.daybook.adversarial

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kr9ly.daybook.DaybookPreferencesCache
import io.github.kr9ly.daybook.exportAllDaybookToSharedPreferences
import io.github.kr9ly.daybook.exportDaybookToSharedPreferences
import io.github.kr9ly.daybook.getDaybookSharedPreferences
import io.github.kr9ly.daybook.importAllSharedPreferencesIntoDaybook
import io.github.kr9ly.daybook.importSharedPreferencesIntoDaybook
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

/**
 * 敵対的テスト: SharedPreferences <-> daybook 相互マイグレーション。
 *
 * 対象: importSharedPreferencesIntoDaybook / importAllSharedPreferencesIntoDaybook /
 * exportDaybookToSharedPreferences / exportAllDaybookToSharedPreferences /
 * getDaybookSharedPreferences(importFromSharedPreferences = true)
 *
 * 情報源は README / DESIGN.md / 公開 API リファレンス（KDoc）のみ。実装は見ていない。
 */
@RunWith(RobolectricTestRunner::class)
class AdversarialMigrationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val seq = AtomicInteger(0)

    /** テストごとに一意なストア名を発行する（Robolectric のプロセス内汚染を避ける）。 */
    private fun uniqueName(label: String): String = "adv_${label}_${seq.incrementAndGet()}"

    @After
    fun tearDown() {
        DaybookPreferencesCache.resetForTesting()
    }

    // ------------------------------------------------------------------
    // 1. 透過取り込み（getDaybookSharedPreferences(importFromSharedPreferences = true)）
    // ------------------------------------------------------------------

    /**
     * README/KDoc: 「取り込みが走るのは生成時（ユーザー編集の前）だけで、キャッシュヒット時は
     * フラグを無視する — 生成後の編集を後からの取り込みが上書きする事故を構造的に排除する」
     *
     * 「app restart」を DaybookPreferencesCache.resetForTesting() でシミュレートし、
     * 一度取り込んだ後に framework 側へ加えられた変更が、再取り込みで daybook 側の
     * ユーザー編集を踏み潰さないことを検証する。
     */
    @Test(timeout = 10_000)
    fun transparentImport_onceOnly_editsAfterMigrationSurviveRestart() {
        val name = uniqueName("transparent_once")
        val framework = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        framework.edit().putInt("a", 1).commit()

        val prefs1 = context.getDaybookSharedPreferences(name, importFromSharedPreferences = true)
        assertEquals(1, prefs1.getInt("a", -1))

        // ユーザーが取り込み後に daybook 側を編集する
        prefs1.edit().putInt("b", 2).commit()

        // framework 側がその後さらに変化する（取り込み後の外部変更）
        framework.edit().putInt("a", 999).putInt("c", 3).commit()

        // プロセス内キャッシュをクリアして「再起動」を模す。ディスク上のマーカーは残る
        DaybookPreferencesCache.resetForTesting()

        val prefs2 = context.getDaybookSharedPreferences(name, importFromSharedPreferences = true)

        assertEquals("再取り込みされ元の値が上書きされてはならない", 1, prefs2.getInt("a", -1))
        assertEquals("取り込み後のユーザー編集が保持されていなければならない", 2, prefs2.getInt("b", -1))
        assertFalse("マーカーにより再取り込みされないため framework の事後追加キーは見えないはず", prefs2.contains("c"))
    }

    /**
     * KDoc: 「取り込みが走るのは生成時...だけで、キャッシュヒット時はフラグを無視する」
     * 同一プロセス内で既にインスタンスがキャッシュされている名前に対し、後から
     * importFromSharedPreferences = true で取得しても取り込みは発生しないはず。
     */
    @Test(timeout = 10_000)
    fun transparentImport_cacheHit_ignoresFlag() {
        val name = uniqueName("cache_hit")
        val framework = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        framework.edit().putString("shouldNotAppear", "x").commit()

        // 先にフラグなしでインスタンスを生成（キャッシュに乗る）
        val prefs1 = context.getDaybookSharedPreferences(name)
        assertFalse(prefs1.contains("shouldNotAppear"))

        // 同一プロセスで同名を import フラグ付きで再取得 → キャッシュヒットのはず
        val prefs2 = context.getDaybookSharedPreferences(name, importFromSharedPreferences = true)

        assertFalse(
            "キャッシュヒット時はフラグを無視するはずなので取り込まれてはいけない",
            prefs2.contains("shouldNotAppear"),
        )
    }

    /**
     * KDoc: getDaybookSharedPreferences の multiProcess は「同名を異なる値で再取得すると
     * IllegalArgumentException」と明記されているが、importFromSharedPreferences は
     * その対象として言及されていない。フラグ違いだけでの再取得は例外を投げないはず。
     */
    @Test(timeout = 10_000)
    fun transparentImport_flagMismatchOnCacheHit_doesNotThrow() {
        val name = uniqueName("flag_mismatch")
        context.getDaybookSharedPreferences(name, importFromSharedPreferences = false)

        // 例外を投げないことそのものが期待値
        context.getDaybookSharedPreferences(name, importFromSharedPreferences = true)
    }

    /**
     * KDoc（getDaybookSharedPreferences と importSharedPreferencesIntoDaybook の両方）は
     * 「一度だけ」取り込む契約をそれぞれ独立に説明しているが、マーカーが名前単位で共有されるのか
     * 明言されていない。明示 import で先にマーカーを立てた後、初めて透過フラグ付きで
     * インスタンス生成した場合に二重取り込みが起きないかを確認する（ドキュメントの曖昧箇所）。
     */
    @Test(timeout = 10_000)
    fun explicitImportThenTransparentFirstAccess_doesNotDoubleImport() {
        val name = uniqueName("explicit_then_transparent")
        val framework = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        framework.edit().putInt("a", 1).commit()

        // 明示 import で先にマーカーを立てる
        val firstImportRan = context.importSharedPreferencesIntoDaybook(name)
        assertTrue(firstImportRan)

        // その後 framework 側が変化
        framework.edit().putInt("a", 42).putInt("late", 7).commit()

        // getDaybookSharedPreferences 経由でこの名前のインスタンスを初めて生成する
        val prefs = context.getDaybookSharedPreferences(name, importFromSharedPreferences = true)

        // マーカーが名前単位で共有されているなら、再取り込みは起きず a==1, late は存在しないはず
        assertEquals(
            "明示 import 済みのマーカーが尊重されるなら再取り込みされないはず",
            1,
            prefs.getInt("a", -1),
        )
        assertFalse(
            "再取り込みされるなら見えてしまう事後キー",
            prefs.contains("late"),
        )
    }

    // ------------------------------------------------------------------
    // 2. 明示 import: 冪等性・マージ規則
    // ------------------------------------------------------------------

    /**
     * KDoc: 「@return true if the import ran, false if it had already been done before.」
     * 2 回目の呼び出しは false を返し、1 回目以降の framework 側変化は取り込まれない。
     */
    @Test(timeout = 10_000)
    fun explicitImport_secondCallReturnsFalse_andDoesNotReimport() {
        val name = uniqueName("explicit_idempotent")
        val framework = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        framework.edit().putInt("a", 1).commit()

        assertTrue(context.importSharedPreferencesIntoDaybook(name))

        framework.edit().putInt("a", 2).putInt("b", 99).commit()

        val secondRun = context.importSharedPreferencesIntoDaybook(name)
        assertFalse("2 回目は既に取り込み済みなので false のはず", secondRun)

        val prefs = context.getDaybookSharedPreferences(name)
        assertEquals(1, prefs.getInt("a", -1))
        assertFalse(prefs.contains("b"))
    }

    /**
     * DESIGN.md: 「import はマージ上書き: 同名キーはフレームワーク値で上書きし、
     * daybook 固有のキーは残す」
     */
    @Test(timeout = 10_000)
    fun explicitImport_mergeSemantics_frameworkOverwritesSharedKeys_daybookUniqueKeysSurvive() {
        val name = uniqueName("merge_semantics")

        val daybookPrefs = context.getDaybookSharedPreferences(name)
        daybookPrefs.edit()
            .putString("onlyDaybook", "daybookOnly")
            .putString("shared", "daybookValue")
            .commit()

        val framework = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        framework.edit()
            .putString("shared", "frameworkValue")
            .putString("onlyFramework", "frameworkOnly")
            .commit()

        context.importSharedPreferencesIntoDaybook(name)

        assertEquals(
            "daybook 固有キーは残るはず",
            "daybookOnly",
            daybookPrefs.getString("onlyDaybook", null),
        )
        assertEquals(
            "同名キーは framework 値で上書きされるはず",
            "frameworkValue",
            daybookPrefs.getString("shared", null),
        )
        assertEquals(
            "framework 固有キーは追加されるはず",
            "frameworkOnly",
            daybookPrefs.getString("onlyFramework", null),
        )
    }

    /**
     * DESIGN.md/KDoc: import はマージ上書きであり、同名キーは framework 値で置き換わる。
     * 値の型自体が daybook 側と framework 側で異なっていた場合でも、framework の型・値が
     * 勝つはず（型システムの制約内: String/Int/Long/Float/Boolean/Set<String>）。
     */
    @Test(timeout = 10_000)
    fun explicitImport_typeChangeOnCollision_frameworkTypeWins() {
        val name = uniqueName("type_change")

        val daybookPrefs = context.getDaybookSharedPreferences(name)
        daybookPrefs.edit().putInt("x", 123).commit()

        val framework = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        framework.edit().putString("x", "now a string").commit()

        context.importSharedPreferencesIntoDaybook(name)

        assertEquals("now a string", daybookPrefs.getString("x", null))
    }

    /**
     * DESIGN.md: 「予約キーでなくサイドカーにするのは getAll の結果（互換 API の観測可能な状態）
     * を汚さないため」。取り込み後の getAll() に取り込み済みマーカー由来のキーが混入しないこと。
     */
    @Test(timeout = 10_000)
    fun explicitImport_markerDoesNotLeakIntoGetAll() {
        val name = uniqueName("marker_leak")
        val framework = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        framework.edit().putInt("a", 1).putString("b", "v").commit()

        context.importSharedPreferencesIntoDaybook(name)

        val prefs = context.getDaybookSharedPreferences(name)
        val all = prefs.all

        assertEquals("取り込んだキー数だけが getAll に現れるはず", 2, all.size)
        assertTrue(all.containsKey("a"))
        assertTrue(all.containsKey("b"))
    }

    /**
     * KDoc: 「By default the source is left as is... pass deleteSource = true to clear it
     * after a successful import.」公式 SharedPreferences API 経由でクリアされ、
     * かつクリア後も framework 側インスタンスは普通に使い続けられるはず。
     */
    @Test(timeout = 10_000)
    fun explicitImport_deleteSource_clearsFrameworkButKeepsItUsable() {
        val name = uniqueName("delete_source")
        val framework = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        framework.edit().putInt("a", 1).putString("b", "v").commit()

        context.importSharedPreferencesIntoDaybook(name, deleteSource = true)

        assertTrue("deleteSource=true なら framework 側は空になるはず", framework.all.isEmpty())

        // クリア後も framework prefs は普通に使えるはず
        framework.edit().putInt("fresh", 7).commit()
        assertEquals(7, framework.getInt("fresh", -1))
    }

    /**
     * KDoc には「framework 側にそのストアが存在しない」場合の挙動が明記されていないが、
     * DESIGN.md の「一度だけ実行」原則から、存在しないソースに対する import は
     * 空の取り込みとして成功し（true を返し）、以降は false を返すべきと解釈できる。
     */
    @Test(timeout = 10_000)
    fun explicitImport_nonexistentFrameworkSource_succeedsVacuouslyAndIsIdempotent() {
        val name = uniqueName("nonexistent_source")

        val firstRun = context.importSharedPreferencesIntoDaybook(name)
        assertTrue("存在しないソースでも初回は true を返すはず（空の取り込み）", firstRun)

        val prefs = context.getDaybookSharedPreferences(name)
        assertTrue(prefs.all.isEmpty())

        val secondRun = context.importSharedPreferencesIntoDaybook(name)
        assertFalse("2 回目は既に実行済みとして false のはず", secondRun)
    }

    // ------------------------------------------------------------------
    // 3. importAll: 網羅性・列挙結果
    // ------------------------------------------------------------------

    /**
     * KDoc: 「Enumerates the app's shared_prefs directory and runs
     * importSharedPreferencesIntoDaybook for each name」「@return The names that were
     * actually imported by this call, sorted.」
     */
    @Test(timeout = 10_000)
    fun importAll_enumeratesAllFrameworkFiles_returnsSortedNames() {
        val base = uniqueName("importall")
        val names = listOf("${base}_zzz", "${base}_aaa", "${base}_mmm")
        names.forEach { n ->
            context.getSharedPreferences(n, Context.MODE_PRIVATE)
                .edit().putString("v", n).commit()
        }

        val imported = context.importAllSharedPreferencesIntoDaybook()

        assertTrue(
            "少なくとも今回作った3つが含まれ、かつソート済みであるはず",
            imported.containsAll(names) && imported == imported.sorted(),
        )

        names.forEach { n ->
            val prefs = context.getDaybookSharedPreferences(n)
            assertEquals(n, prefs.getString("v", null))
        }
    }

    /**
     * KDoc: 「calling this repeatedly (e.g. on every app start) only picks up preferences
     * files that appeared since the last call.」
     */
    @Test(timeout = 10_000)
    fun importAll_secondCall_onlyPicksUpNewlyAppearedFiles() {
        val base = uniqueName("importall_repeat")
        val first = "${base}_first"
        context.getSharedPreferences(first, Context.MODE_PRIVATE)
            .edit().putInt("v", 1).commit()

        val firstImported = context.importAllSharedPreferencesIntoDaybook()
        assertTrue(firstImported.contains(first))

        // 2回目: 新しいファイルを1つ追加
        val second = "${base}_second"
        context.getSharedPreferences(second, Context.MODE_PRIVATE)
            .edit().putInt("v", 2).commit()

        val secondImported = context.importAllSharedPreferencesIntoDaybook()

        assertFalse(
            "既に取り込み済みの名前は2回目の返り値に含まれないはず",
            secondImported.contains(first),
        )
        assertTrue(secondImported.contains(second))
    }

    // ------------------------------------------------------------------
    // 4. export: 完全上書き（stale キー削除）
    // ------------------------------------------------------------------

    /**
     * KDoc: 「After the call the framework preferences hold exactly the daybook store's
     * current entries (stale framework keys are removed).」
     */
    @Test(timeout = 10_000)
    fun export_fullReplace_removesStaleFrameworkKeysAndOverwritesShared() {
        val name = uniqueName("export_replace")

        val daybookPrefs = context.getDaybookSharedPreferences(name)
        daybookPrefs.edit()
            .putInt("x", 1)
            .putInt("y", 2)
            .commit()

        val framework = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        framework.edit()
            .putInt("y", 999) // 上書きされるべき
            .putInt("z", 3)   // stale、削除されるべき
            .commit()

        context.exportDaybookToSharedPreferences(name)

        val all = framework.all
        assertEquals(2, all.size)
        assertEquals(1, all["x"])
        assertEquals(2, all["y"])
        assertFalse("stale キーは削除されるはず", all.containsKey("z"))
    }

    /**
     * KDoc: exportAll は「The names of the exported stores, sorted.」を返す。
     * 複数ストアを不揃いな順で開いても、返り値はソート済みで全ストアが含まれるはず。
     */
    @Test(timeout = 10_000)
    fun exportAll_returnsSortedNamesAndReplicatesEachStore() {
        val base = uniqueName("exportall")
        val names = listOf("${base}_zzz", "${base}_aaa", "${base}_mmm")

        names.forEach { n ->
            context.getDaybookSharedPreferences(n).edit().putString("v", n).commit()
        }

        val exported = context.exportAllDaybookToSharedPreferences()

        assertTrue(exported.containsAll(names))
        assertEquals("返り値はソート済みのはず", exported.sorted(), exported)

        names.forEach { n ->
            val framework = context.getSharedPreferences(n, Context.MODE_PRIVATE)
            assertEquals(n, framework.getString("v", null))
        }
    }

    /**
     * DESIGN.md: 「型システムの制約: ... String / Int / Long / Float / Boolean / Set<String>」
     * 往復可能性の確認として、全サポート型が import/export を経由しても値を保つことを検証する。
     */
    @Test(timeout = 10_000)
    fun exportThenImportToFreshStore_preservesAllSupportedTypes() {
        val exportName = uniqueName("roundtrip_export")
        val importName = uniqueName("roundtrip_import")

        val daybookPrefs = context.getDaybookSharedPreferences(exportName)
        val stringSet = setOf("s1", "s2", "s3")
        daybookPrefs.edit()
            .putBoolean("bool", true)
            .putInt("int", 42)
            .putLong("long", 1234567890123L)
            .putFloat("float", 3.14f)
            .putString("string", "hello")
            .putStringSet("set", stringSet)
            .commit()

        context.exportDaybookToSharedPreferences(exportName)

        // エクスポート先の framework ファイルを、別名の daybook ストアへ import することで
        // 全型が壊れずに往復するかを確認する（同名だと import 済みマーカーが邪魔になるため
        // framework 側のファイルをコピーする代わりに同名で export → 同名で import を試す）
        val framework = context.getSharedPreferences(exportName, Context.MODE_PRIVATE)
        assertEquals(true, framework.getBoolean("bool", false))
        assertEquals(42, framework.getInt("int", -1))
        assertEquals(1234567890123L, framework.getLong("long", -1))
        assertEquals(3.14f, framework.getFloat("float", -1f), 0.0001f)
        assertEquals("hello", framework.getString("string", null))
        assertEquals(stringSet, framework.getStringSet("set", null))

        // 同じ内容を持つ別名の framework ファイルから新規 daybook ストアへ import
        val framework2 = context.getSharedPreferences(importName, Context.MODE_PRIVATE)
        framework2.edit()
            .putBoolean("bool", true)
            .putInt("int", 42)
            .putLong("long", 1234567890123L)
            .putFloat("float", 3.14f)
            .putString("string", "hello")
            .putStringSet("set", stringSet)
            .commit()
        context.importSharedPreferencesIntoDaybook(importName)
        val reimported = context.getDaybookSharedPreferences(importName)

        assertEquals(true, reimported.getBoolean("bool", false))
        assertEquals(42, reimported.getInt("int", -1))
        assertEquals(1234567890123L, reimported.getLong("long", -1))
        assertEquals(3.14f, reimported.getFloat("float", -1f), 0.0001f)
        assertEquals("hello", reimported.getString("string", null))
        assertEquals(stringSet, reimported.getStringSet("set", null))
    }
}
