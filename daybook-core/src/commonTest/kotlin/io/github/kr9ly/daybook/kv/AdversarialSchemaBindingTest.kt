package io.github.kr9ly.daybook.kv

import io.github.kr9ly.daybook.io.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 敵対的テスト — レーン 2: スキーマとストア束縛。
 *
 * 契約は以下のドキュメントのみを根拠にする（実装ソースは読んでいない）:
 * - docs/common-api.md (スキーマ宣言 / ストアを開く / 型安全プロパティ節)
 * - daybook-core の DaybookSchema.kt / DaybookProperty.kt / Daybook.kt / DaybookOpenOptions.kt の
 *   公開 KDoc（public-api-extract.md 経由）
 */
class AdversarialSchemaBindingTest {

    @AfterTest
    fun tearDown() {
        DaybookRegistry.resetForTesting()
    }

    // --- DaybookSchema: 宣言時検証 ---

    // 契約: 「同じキー名の二重宣言は宣言時に IllegalArgumentException」
    // 攻撃: 二重宣言の型が String と StringSet（DaybookKey のサブクラスが違う）でも
    // 検査が効くか。boolean/int の同型二重宣言は既存テストで確認済みのため、
    // ここでは異なるキークラス（StringKey / StringSetKey）同士の衝突を狙う。
    @Test
    fun duplicateKeyName_acrossDifferentKeyClasses_failsAtDeclaration() {
        assertFailsWith<IllegalArgumentException> {
            object : DaybookSchema("dup-cross-type") {
                val a = string("tags")
                val b = stringSet("tags")
            }
        }
    }

    @Test
    fun schemaIsNotAContentConstraint_undeclaredKeySurvivesInKeysAndContains() {
        // 契約(docs/common-api.md): 「スキーマはストア内容の制約ではない。宣言されていない
        // キーがストアに存在してもよい」
        val schema = object : DaybookSchema("no-constraint") {
            val declared = string("declared")
        }
        val daybook = Daybook.open(createTempDirectory().path, schema)
        daybook.edit {
            putString("declared", "d")
            putInt("undeclared", 1) // スキーマに一切宣言のないキー
        }
        assertTrue(daybook.contains("undeclared"))
        assertTrue("undeclared" in daybook.keys)
        assertEquals(1, daybook.getInt("undeclared", 0))
    }

    // --- DaybookKey: ストア束縛のランタイム検査 ---

    @Test
    fun property_withKeyFromDifferentSchemaObject_evenWithSameNameAndType_throws() {
        // 契約(DaybookProperty.kt): 「[key] はこのストアのスキーマで宣言されたものであること:
        // 別のスキーマのキーを渡すと IllegalArgumentException」
        // 攻撃: キー名・型が完全に同一の別スキーマオブジェクトでもすり抜けないか
        // （文字列比較でなくオブジェクト同一性で束縛検査していることの確認）。
        val schemaA = object : DaybookSchema("bind-a") {
            val value = int("value")
        }
        val schemaB = object : DaybookSchema("bind-b") {
            val value = int("value") // 同名・同型キーだが別スキーマ・別オブジェクト
        }
        val daybookA = Daybook.open(createTempDirectory().path, schemaA)
        assertFailsWith<IllegalArgumentException> {
            daybookA.property(schemaB.value, default = 0)
        }
    }

    @Test
    fun property_nullableStringKeyFromDifferentSchema_alsoThrows() {
        // string() は StringKey（nullable 版 property を持つ別クラス）。checkSchema が
        // DaybookKey<*> 全般に効くかを nullable 版のオーバーロードでも確認する。
        val schemaA = object : DaybookSchema("bind-a-str") {
            val name = string("name")
        }
        val schemaB = object : DaybookSchema("bind-b-str") {
            val name = string("name")
        }
        val daybookA = Daybook.open(createTempDirectory().path, schemaA)
        assertFailsWith<IllegalArgumentException> {
            daybookA.property(schemaB.name)
        }
    }

    @Test
    fun property_nullableStringSetKeyFromDifferentSchema_alsoThrows() {
        val schemaA = object : DaybookSchema("bind-a-set") {
            val tags = stringSet("tags")
        }
        val schemaB = object : DaybookSchema("bind-b-set") {
            val tags = stringSet("tags")
        }
        val daybookA = Daybook.open(createTempDirectory().path, schemaA)
        assertFailsWith<IllegalArgumentException> {
            daybookA.property(schemaB.tags)
        }
    }

    // --- Daybook.open: スキーマ同一性・パス同定 ---

    @Test
    fun open_structurallyIdenticalSchemaObject_isStillRejected() {
        // 契約: 「スキーマの同一性はオブジェクト同一性で検査される」
        // 攻撃: 既存の DaybookOpenTest.open_differentSchemaObjectThrows はキー無しの
        // スキーマだったため、キー宣言まで完全に一致する「実質的に同じ」スキーマでも
        // オブジェクト同一性だけで弾かれるかを追加確認する。
        val dir = createTempDirectory().path
        val schema1 = object : DaybookSchema("structurally-same") {
            val a = int("a")
            val b = string("b")
        }
        val schema2 = object : DaybookSchema("structurally-same") {
            val a = int("a")
            val b = string("b")
        }
        Daybook.open(dir, schema1)
        assertFailsWith<IllegalArgumentException> {
            Daybook.open(dir, schema2)
        }
    }

    @Test
    fun open_migrationsListMismatch_onReopen_isNotRejected() {
        // 契約(DaybookOpenOptions.kt): 「migrations は再取得時の一致は要求されない。
        // 生成時の挙動だけを表し、キャッシュヒット時は黙って無視される」
        // 攻撃: durability / multiProcess の不一致は例外になるのに対し、migrations の
        // リストの中身が完全に違っても再取得が成功することを確認する
        // （実装が migrations もオプション一致検査に誤って含めていないか）。
        val schema = object : DaybookSchema("migrations-mismatch") {}
        val dir = createTempDirectory().path
        val first = Daybook.open(dir, schema) {
            migrations = emptyList()
        }
        val marker = object : MigrationSource {
            override val id = "should-be-ignored-on-cache-hit"
            override fun read(environment: MigrationEnvironment): Map<String, Any>? {
                throw AssertionError("キャッシュヒット時に read が呼ばれてはいけない")
            }
        }
        val second = Daybook.open(dir, schema) {
            migrations = listOf(marker)
        }
        assertSame(first, second) // 同一インスタンス（例外にならない）
    }

    @Test
    fun open_directoryWithTrailingSlash_normalizesToSameStore() {
        // 契約: 「directory は絶対パスに正規化して同定される」。".." を含むケースは
        // 既存テストで確認済み。ここでは末尾スラッシュという別の表記ゆれを攻撃する。
        val schema = object : DaybookSchema("trailing-slash") {}
        val dir = createTempDirectory().path
        val first = Daybook.open(dir, schema)
        val second = Daybook.open("$dir/", schema)
        assertSame(first, second)
    }

    @Test
    fun open_directoryWithRedundantCurrentDirSegment_normalizesToSameStore() {
        // "dir/./" のような冗長な "." セグメントが正規化ですり抜けないかの攻撃。
        val schema = object : DaybookSchema("dot-segment") {}
        val dir = createTempDirectory().path
        val first = Daybook.open(dir, schema)
        val second = Daybook.open("$dir/./", schema)
        assertSame(first, second)
    }
}
