package io.github.kr9ly.daybook.adversarial

import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import io.github.kr9ly.daybook.DaybookPreferencesCache
import io.github.kr9ly.daybook.PreferenceProperty
import io.github.kr9ly.daybook.boolean
import io.github.kr9ly.daybook.getDaybookSharedPreferences
import io.github.kr9ly.daybook.int
import io.github.kr9ly.daybook.string
import io.github.kr9ly.daybook.stringSet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

/**
 * PreferenceProperty<T> レーンの敵対的テスト。
 *
 * 情報源: README.md / DESIGN.md / 公開 API リファレンス (KDoc) のみ。実装は読んでいない。
 * 対象: PreferenceProperty のファクトリ8つ、get/set、デリゲート、map(decode, encode)、catch(handler)。
 */
@RunWith(RobolectricTestRunner::class)
class AdversarialTypedApiTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private var seq = AtomicInteger(0)

    private fun freshFrameworkPrefs(): SharedPreferences {
        val name = "adversarial_framework_${seq.incrementAndGet()}"
        return context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
    }

    private fun freshDaybookPrefs(): SharedPreferences {
        val name = "adversarial_daybook_${seq.incrementAndGet()}"
        return context.getDaybookSharedPreferences(name)
    }

    @After
    fun tearDown() {
        DaybookPreferencesCache.resetForTesting()
    }

    // ------------------------------------------------------------------
    // 契約: 「default あり = non-null、default なし = nullable」「nullable への null 代入は削除」
    // ------------------------------------------------------------------

    @Test
    fun `absent key returns declared default - both backends agree`() {
        for (prefs in listOf(freshFrameworkPrefs(), freshDaybookPrefs())) {
            val prop = prefs.boolean("flag", default = true)
            assertTrue("framework=${prefs === context}", prop.get())
        }
    }

    @Test
    fun `nullable string absent reads as null - both backends`() {
        for (prefs in listOf(freshFrameworkPrefs(), freshDaybookPrefs())) {
            val prop = prefs.string("nickname")
            assertNull(prop.get())
        }
    }

    @Test
    fun `nullable string set null removes the key - contains becomes false`() {
        for (prefs in listOf(freshFrameworkPrefs(), freshDaybookPrefs())) {
            val prop = prefs.string("nickname")
            prop.set("Alice")
            assertTrue(prefs.contains("nickname"))
            assertEquals("Alice", prop.get())

            prop.set(null)
            assertFalse("key must be removed by null-set", prefs.contains("nickname"))
            assertNull(prop.get())
        }
    }

    @Test
    fun `nullable stringSet set null removes the key`() {
        for (prefs in listOf(freshFrameworkPrefs(), freshDaybookPrefs())) {
            val prop = prefs.stringSet("tags")
            prop.set(setOf("a", "b"))
            assertTrue(prefs.contains("tags"))

            prop.set(null)
            assertFalse(prefs.contains("tags"))
            assertNull(prop.get())
        }
    }

    // ------------------------------------------------------------------
    // 契約: default は「格納側の世界で宣言」「map は不在時の挙動に関与しない純粋な値変換」
    // README の例そのもの: prefs.string("theme", default = Theme.SYSTEM.name).map(...)
    // 疑うべき点: 「関与しない」の意味は「decode は absent のときも呼ばれず default がそのまま
    // R として返る」なのか、「default はストア側の値として get() の入力になり、その後 decode を
    // 通る」なのか、KDoc の言い回しからは実は一意に決まらない。README の実コード例で確認する。
    // ------------------------------------------------------------------

    private enum class Theme { LIGHT, DARK, SYSTEM }

    @Test
    fun `map on absent key - decode is applied to the stored-side default (README example)`() {
        for (prefs in listOf(freshFrameworkPrefs(), freshDaybookPrefs())) {
            val themeProp = prefs.string("theme", default = Theme.SYSTEM.name)
                .map(decode = Theme::valueOf, encode = Theme::name)

            // absent -> underlying default "SYSTEM" -> decode("SYSTEM") -> Theme.SYSTEM
            assertEquals(Theme.SYSTEM, themeProp.get())
        }
    }

    @Test
    fun `map encode is applied on write - underlying storage holds the encoded representation`() {
        for (prefs in listOf(freshFrameworkPrefs(), freshDaybookPrefs())) {
            val themeProp = prefs.string("theme", default = Theme.SYSTEM.name)
                .map(decode = Theme::valueOf, encode = Theme::name)

            themeProp.set(Theme.DARK)
            // Verify at the raw SharedPreferences level, not just via the typed property.
            assertEquals("DARK", prefs.getString("theme", null))
            assertEquals(Theme.DARK, themeProp.get())
        }
    }

    // ------------------------------------------------------------------
    // 契約: 「decode の失敗はポリシーを持たずそのまま伝播」
    // ------------------------------------------------------------------

    @Test
    fun `map decode failure propagates as-is without catch`() {
        for (prefs in listOf(freshFrameworkPrefs(), freshDaybookPrefs())) {
            // Write a raw value that Theme.valueOf cannot parse, bypassing the typed layer.
            prefs.edit().putString("theme", "NOT_A_THEME").commit()

            val themeProp = prefs.string("theme", default = Theme.SYSTEM.name)
                .map(decode = Theme::valueOf, encode = Theme::name)

            assertThrows(IllegalArgumentException::class.java) {
                themeProp.get()
            }
        }
    }

    // ------------------------------------------------------------------
    // 契約: 「catch は読み取り経路（上流の map の decode を含む）だけを包み、
    // 書き込み（encode）の失敗は呼び出し側のバグとしてそのまま投げる」
    // ------------------------------------------------------------------

    @Test
    fun `catch recovers a decode failure of the upstream map`() {
        for (prefs in listOf(freshFrameworkPrefs(), freshDaybookPrefs())) {
            prefs.edit().putString("theme", "GARBAGE").commit()

            val themeProp = prefs.string("theme", default = Theme.SYSTEM.name)
                .map(decode = Theme::valueOf, encode = Theme::name)
                .catch { Theme.SYSTEM }

            assertEquals(Theme.SYSTEM, themeProp.get())
        }
    }

    @Test
    fun `catch does not intercept encode write failures`() {
        for (prefs in listOf(freshFrameworkPrefs(), freshDaybookPrefs())) {
            val boomProp = prefs.string("boom", default = "x")
                .map(
                    decode = { it },
                    encode = { _: String -> throw IllegalStateException("encode boom") },
                )
                .catch { "fallback" }

            // Documented: write failures are caller bugs and propagate as-is, catch must not
            // swallow them.
            assertThrows(IllegalStateException::class.java) {
                boomProp.set("anything")
            }
        }
    }

    @Test
    fun `catch ordered before map does not protect the outer map decode`() {
        // Attack on composition order: catch() is documented as covering "the read path
        // (including upstream map decoding)". If catch is attached BEFORE map in the chain
        // (i.e. catch on the string property, then map on top), does it still protect the
        // later map's decode? The KDoc example only shows catch AFTER map; this probes the
        // other order, which the docs are silent about.
        for (prefs in listOf(freshFrameworkPrefs(), freshDaybookPrefs())) {
            prefs.edit().putString("theme", "GARBAGE").commit()

            val stringPropWithCatch = prefs.string("theme", default = Theme.SYSTEM.name)
                .catch { "SYSTEM" } // this catch only ever sees the (never-failing) raw string read
            val themeProp = stringPropWithCatch.map(decode = Theme::valueOf, encode = Theme::name)

            var caught = false
            try {
                themeProp.get()
            } catch (e: IllegalArgumentException) {
                caught = true
            }
            // If this fails, catch() placed before map() unexpectedly protects the later
            // map's decode too (i.e. catch coverage is not chain-position-scoped as documented).
            assertTrue(
                "expected decode failure of the *outer* map to propagate because the earlier " +
                    "catch() only wraps the read below it, not maps attached afterwards",
                caught,
            )
        }
    }

    // ------------------------------------------------------------------
    // 契約: 「独自インターフェースを公開しない」「framework でも daybook でも同じに動く」
    // -> 型不一致アクセス（同一キーに異なるファクトリでアクセス）でも両バックエンドが揃うべき
    // ------------------------------------------------------------------

    @Test
    fun `accessing the same key with an incompatible type throws consistently on both backends`() {
        for (prefs in listOf(freshFrameworkPrefs(), freshDaybookPrefs())) {
            val intProp = prefs.int("count", default = 0)
            intProp.set(42)

            val boolProp = prefs.boolean("count", default = false)
            assertThrows(ClassCastException::class.java) {
                boolProp.get()
            }
        }
    }

    @Test
    fun `catch recovers a plain type-mismatch ClassCastException on the property itself`() {
        // KDoc: catch は「読み取り中に投げられた例外すべて」を回復する — map の decode
        // 失敗に限らず、別ファクトリで書かれた既存キーの素の ClassCastException も対象。
        // （当初この広さが KDoc から読み取れず契約違反として報告された。挙動は意図どおりで、
        // KDoc 側に明示の一文が追加された）
        for (prefs in listOf(freshFrameworkPrefs(), freshDaybookPrefs())) {
            val intProp = prefs.int("shared_key", default = 0)
            intProp.set(1)

            val boolPropWithCatch = prefs.boolean("shared_key", default = false)
                .catch { true }

            assertEquals(true, boolPropWithCatch.get())
        }
    }

    // ------------------------------------------------------------------
    // 契約: 「デリゲートファクトリの返り値自体がキーオブジェクトを兼ねる」「.key」「.preferences」
    // ------------------------------------------------------------------

    @Test
    fun `key and preferences fields match construction site`() {
        val prefs = freshDaybookPrefs()
        val prop = prefs.int("answer", default = 42)
        assertEquals("answer", prop.key)
        assertSame(prefs, prop.preferences)
    }

    @Test
    fun `map result exposes the same key as the underlying property, not a synthetic one`() {
        val prefs = freshDaybookPrefs()
        val mapped: PreferenceProperty<Theme> = prefs.string("theme", default = Theme.SYSTEM.name)
            .map(decode = Theme::valueOf, encode = Theme::name)
        assertEquals("theme", mapped.key)
    }

    // ------------------------------------------------------------------
    // 契約: 「プロパティ名からのキー導出はしない」「キー名の二重記述は起きない」
    // -> 同じキーに異なるデフォルトを持つ複数の PreferenceProperty を作っても独立して動くはず
    // ------------------------------------------------------------------

    @Test
    fun `two independent PreferenceProperty instances over the same key each honor their own default`() {
        for (prefs in listOf(freshFrameworkPrefs(), freshDaybookPrefs())) {
            val propA = prefs.int("shared", default = 1)
            val propB = prefs.int("shared", default = 2)

            assertEquals(1, propA.get())
            assertEquals(2, propB.get())

            propA.set(99)
            assertEquals(99, propA.get())
            assertEquals(99, propB.get())
        }
    }

    // ------------------------------------------------------------------
    // 契約: get/set と delegate は同じ経路（デリゲートは get()/set() の薄いラッパーのはず）
    // ------------------------------------------------------------------

    private class Holder(prefs: SharedPreferences) {
        var count by prefs.int("count", default = 0)
    }

    @Test
    fun `direct get-set and delegate assignment observe each other`() {
        for (prefs in listOf(freshFrameworkPrefs(), freshDaybookPrefs())) {
            val prop = prefs.int("count", default = 0)
            val holder = Holder(prefs)

            prop.set(7)
            assertEquals(7, holder.count)

            holder.count = 10
            assertEquals(10, prop.get())
        }
    }

    // ------------------------------------------------------------------
    // 攻撃: stringSet の返り値が内部の可変コレクションへの直接参照だと、呼び出し側の
    // ミューテーションがストアの内部状態を set() を経由せず書き換えてしまう（アリアシング）。
    // ------------------------------------------------------------------

    @Test
    fun `mutating the Set returned by get() does not corrupt subsequent reads - daybook backend`() {
        // daybook の意図的な非互換（DESIGN.md）: getStringSet は防御コピーを返すため、
        // 返り値の変更が以後の読み出しに漏れない。framework バックエンドは AOSP の
        // 既知の罠（内部 Set の生参照を返す）をそのまま持つので、この検証は daybook 限定。
        // この敵対的テストが生参照の漏れを検出したことを受けて防御コピー化された
        val prop = freshDaybookPrefs().stringSet("tags", default = setOf("a", "b"))
        prop.set(setOf("x", "y"))

        val got = prop.get()
        try {
            (got as? MutableSet<String>)?.add("MUTATED")
        } catch (e: UnsupportedOperationException) {
            // イミュータブルでも契約は満たされる
            return
        }

        val second = prop.get()
        assertFalse(
            "get() returned a live reference into internal storage — mutating " +
                "the returned Set changed what subsequent get() calls see, without ever " +
                "calling set(). second read = $second",
            second.contains("MUTATED"),
        )
    }

    @Test
    fun `mutating a caller-supplied default Set after declaration must not affect later reads`() {
        // Attack: default is "fixed at declaration" per the KDoc. If the implementation keeps
        // a reference to the caller's mutable Set instead of copying it, mutating that set
        // after declaration would silently change the "fixed" default.
        for (prefs in listOf(freshFrameworkPrefs(), freshDaybookPrefs())) {
            val mutableDefault = mutableSetOf("a", "b")
            val prop = prefs.stringSet("tags2", default = mutableDefault)

            mutableDefault.add("c")

            // KDoc「The default is copied at declaration」: ファクトリ呼び出し時に一度だけ
            // コピーされるため、宣言後に呼び出し側の Set を変更しても不在時の値は変わらない。
            // （当初は参照のまま保持されており、この敵対的テストの検出を受けて修正された）
            val value = prop.get() // key still absent -> should read the *original* default
            assertFalse(
                "default Set was captured by reference, not copied at declaration " +
                    "time. get() = $value reflects a post-declaration mutation of the caller's set.",
                value.contains("c"),
            )
        }
    }

    // ------------------------------------------------------------------
    // 境界値: 空文字列キー・巨大文字列値・マルチバイトキー
    // PreferenceProperty のキーには Context.getDaybookSharedPreferences(name) のような
    // 「非空・'/' を含まない」制約は明記されていない（それは name の制約であって key の制約ではない）。
    // ------------------------------------------------------------------

    @Test
    fun `empty string key is accepted the same way by both backends`() {
        for (prefs in listOf(freshFrameworkPrefs(), freshDaybookPrefs())) {
            val prop = prefs.string("", default = "default-for-empty-key")
            assertEquals("default-for-empty-key", prop.get())
            prop.set("value")
            assertEquals("value", prop.get())
        }
    }

    @Test
    fun `multi-byte unicode key round-trips correctly`() {
        for (prefs in listOf(freshFrameworkPrefs(), freshDaybookPrefs())) {
            val prop = prefs.string("設定_キー_🔑", default = "d")
            prop.set("値")
            assertEquals("値", prop.get())
            assertTrue(prefs.contains("設定_キー_🔑"))
        }
    }

    @Test
    fun `large string value round-trips (no silent truncation)`() {
        for (prefs in listOf(freshFrameworkPrefs(), freshDaybookPrefs())) {
            val big = "x".repeat(200_000)
            val prop = prefs.string("big", default = "")
            prop.set(big)
            assertEquals(big.length, prop.get().length)
            assertEquals(big, prop.get())
        }
    }

    // ------------------------------------------------------------------
    // 契約: 「複数キーのアトミックな一括更新は従来の Editor に落ちる」
    // -> PreferenceProperty.set() は 1 キー = 1 apply()。同じ prefs 上で Editor の
    // put/commit と PreferenceProperty.set() が食い違わないかを確認する。
    // ------------------------------------------------------------------

    @Test
    fun `PreferenceProperty set() and Editor edits on the same prefs observe each other`() {
        for (prefs in listOf(freshFrameworkPrefs(), freshDaybookPrefs())) {
            val prop = prefs.int("n", default = 0)
            prop.set(1)

            prefs.edit().putInt("n", 2).commit()
            assertEquals(2, prop.get())

            prop.set(3)
            assertEquals(3, prefs.getInt("n", -1))
        }
    }

    // ------------------------------------------------------------------
    // map のチェーン: R が nullable なとき、null 変換の扱い
    // 元プロパティが non-null (default あり) の場合、map(decode: T -> R?) で R が
    // nullable になったとしても、README の例からは「削除」セマンティクスは
    // *元の* nullable string/stringSet ファクトリにしか結び付いていない。
    // map 経由で作った nullable の set(null) がどう振る舞うかは KDoc が沈黙している。
    // ------------------------------------------------------------------

    @Test
    fun `map to a nullable R over a non-null (defaulted) base - set(null) behavior is unspecified but must not corrupt state`() {
        for (prefs in listOf(freshFrameworkPrefs(), freshDaybookPrefs())) {
            val base = prefs.string("code", default = "0")
            val mapped: PreferenceProperty<Int?> = base.map(
                decode = { it.toIntOrNull() },
                encode = { n: Int? -> n?.toString() ?: "0" },
            )

            mapped.set(5)
            assertEquals(5, mapped.get())

            // The docs never define what set(null) means for a map-derived nullable property
            // (map's nullability comes from decode's return type, not from the "no default"
            // factory contract). At minimum this must not throw and must not desync base vs
            // mapped views.
            try {
                mapped.set(null)
            } catch (e: Exception) {
                fail(
                    "FAILING: PreferenceProperty<Int?> derived via map() threw ${e::class.simpleName} " +
                        "on set(null): ${e.message}. Docs are silent on this case but an " +
                        "unhandled crash for a value the type system allows (T? is a legal R) " +
                        "is still a contract gap worth flagging.",
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // 契約: PreferenceProperty は SharedPreferences インターフェースだけに依存する
    // -> framework prefs と daybook prefs とで、同一シナリオの最終状態が一致するはず。
    // ------------------------------------------------------------------

    @Test
    fun `identical scenario across framework and daybook backends yields identical observable state`() {
        val results = mutableListOf<Triple<String, Boolean, Int>>()
        for ((label, prefs) in listOf("framework" to freshFrameworkPrefs(), "daybook" to freshDaybookPrefs())) {
            val flag = prefs.boolean("flag", default = false)
            val count = prefs.int("count", default = 0)

            flag.set(true)
            count.set(1)
            count.set(count.get() + 1)

            results += Triple(label, flag.get(), count.get())
        }
        val (fw, db) = results
        assertEquals("flag mismatch between backends", fw.second, db.second)
        assertEquals("count mismatch between backends", fw.third, db.third)
    }
}
