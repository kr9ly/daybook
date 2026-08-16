package io.github.kr9ly.daybook.kv

import io.github.kr9ly.daybook.io.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 敵対的テスト: MigrationSource / MigrationMode / SchemaTargetedMigrationSource の共通契約
 * （レーン 3: マイグレーション、daybook-core 部分）。
 *
 * 情報源は README.md / DESIGN.md / API.md / docs 配下の各ガイド / 公開 API 抽出（KDoc）のみ。
 * 実装ソース（各モジュールの Main ソースセット）は読んでいない。攻撃対象は [MigrationSource] の KDoc（同一キーへの
 * 複数ソース写像・宣言の矛盾）、[SchemaTargetedMigrationSource] の KDoc（宛先のスキーマ所属検査）、
 * [DaybookSchema] の KDoc（「スキーマはストア内容の制約ではない」）、[Daybook.edit] の KDoc
 * （型検査はブロック完了時）、[DaybookOpenOptions.migrations] の KDoc（同一 id の重複は最初の
 * 1 つだけ実行）、[MigrationEnvironment] の KDoc（directory は絶対・正規化済みパス）。
 */
class AdversarialMigrationSourceTest {

    @AfterTest
    fun tearDown() {
        DaybookRegistry.resetForTesting()
    }

    private object Schema : DaybookSchema("store") {
        val a = string("a")
        val flag = boolean("flag")
    }

    private class MapSource(
        override val id: String,
        private val values: Map<String, Any>?,
    ) : MigrationSource {
        var readCount = 0

        override fun read(environment: MigrationEnvironment): Map<String, Any>? {
            readCount++
            return values
        }
    }

    private class TypedSource(
        override val id: String,
        override val targets: List<DaybookKey<*>>,
        private val values: Map<String, Any>?,
    ) : SchemaTargetedMigrationSource {
        var readCount = 0

        override fun read(environment: MigrationEnvironment): Map<String, Any>? {
            readCount++
            return values
        }
    }

    /**
     * 攻撃 1: [MigrationSource] の KDoc は「同一キーへの複数ソース写像・宣言の矛盾」を
     * 攻撃面として挙げるが、実際に禁止されるのは 1 つのソース内（例: SharedPreferencesMigrationSource
     * の migrate DSL）の宣言時の矛盾であり、migrations リストに並んだ「別々の」ソースが
     * 同じキーへ写像すること自体は、どの契約文にも禁止が書かれていない。
     *
     * 2 つの独立ソース（id が異なる）が同じキー "a" へ別の値を書くとき、何が起きるかは
     * ドキュメント上未定義。実行順（migrations リスト順）で後勝ちになるのか、
     * 前勝ちになるのか、あるいは例外になるのかを確認する。
     */
    @Test
    fun crossSourceKeyCollision_isUndocumented_probeActualPrecedence() {
        val first = MapSource("first", mapOf("a" to "from-first"))
        val second = MapSource("second", mapOf("a" to "from-second"))

        val daybook = Daybook.open(createTempDirectory().path, Schema) {
            migrations = listOf(first, second)
        }

        assertEquals(1, first.readCount)
        assertEquals(1, second.readCount)
        // 後勝ち（リスト順に上書き）であることを期待値として明示する。異なる結果が出た場合は発見。
        assertEquals("from-second", daybook.getString("a", null))
    }

    /**
     * 攻撃 2: [SchemaTargetedMigrationSource] の KDoc は「この契約を実装するソースの targets が
     * 開こうとしているスキーマに属するかを検査する（属さないキーへの取り込みは宣言ミスとして
     * 即例外）」と言う。「即」がいつを指すかは曖昧 — [MigrationSource.read] が呼ばれて null
     * （まだ読めない）を返した場合でも、targets の検査自体は independent に走るのか、
     * それとも読み取りが成立して初めて検査されるのか。
     *
     * read() が null を返す（今回は取り込まない）場合でも、他スキーマの targets 宣言が
     * 即例外になるかを確認する。
     */
    @Test
    fun schemaTargetValidation_evenWhenReadReturnsNull() {
        val other = object : DaybookSchema("other") {
            val foreign = string("foreign")
        }
        val source = TypedSource("typed-null", listOf(other.foreign), values = null)

        assertFailsWith<IllegalArgumentException> {
            Daybook.open(createTempDirectory().path, Schema) {
                migrations = listOf(source)
            }
        }
    }

    /**
     * 攻撃 3: [DaybookSchema] の KDoc は「スキーマはストア内容の制約ではない: 宣言されていない
     * キーがストアに存在してもよい」「宣言は型付き API から見える面を固定するだけで、検証や
     * 削除は行わない」と言う。[SchemaTargetedMigrationSource.targets] の検査は「targets に
     * 挙げたキーがスキーマに属するか」だけを見るはずで、read() が実際に返す Map の中身
     * （targets に挙げていないキーを含む）までは検証しないはず。
     *
     * targets = [Schema.a] だけを宣言しつつ、read() は targets にないキー "undeclared" も
     * 一緒に返す。ドキュメントどおりなら両方とも黙って書き込まれる（矛盾があっても検出されない）。
     */
    @Test
    fun schemaTargetValidation_doesNotConstrainActuallyWrittenKeys() {
        val source = TypedSource(
            "typed-extra",
            targets = listOf(Schema.a),
            values = mapOf("a" to "declared", "undeclared" to "sneaked-in"),
        )

        val daybook = Daybook.open(createTempDirectory().path, Schema) {
            migrations = listOf(source)
        }

        assertEquals("declared", daybook.getString("a", null))
        assertTrue(
            daybook.contains("undeclared"),
            "targets の宣言外キーへの書き込みが検証なしで通ることの確認（DaybookSchema の " +
                "「検証や削除は行わない」契約どおりなら true になるはず）",
        )
    }

    /**
     * 攻撃 4: [Daybook.edit] の KDoc は「値の型検査はブロック完了時に行われ、違反は
     * IllegalArgumentException」と言う一方、[DaybookSchema] の KDoc は「宣言は検証や削除は
     * 行わない」と言う。つまりマイグレーション経由の書き込みが、宣言されたキーの型
     * （schema.flag は boolean）と異なる型の値（String）を書いても、edit 自体は 7 種の
     * 対応型の 1 つである限り成功するはずで、型不一致はキーの「格納型」対「property の
     * 読み出し型」の不一致として、後から property.get() で初めて ClassCastException になる
     * と予想される。
     *
     * schema.flag（boolean 宣言）へ String 値を取り込ませ、open は成功するか、
     * 成功した場合 property 経由の読み出しで ClassCastException になるかを確認する。
     */
    @Test
    fun migrationWritesTypeMismatchedValueForSchemaKey_openSucceeds_thenTypedPropertyThrows() {
        val source = TypedSource(
            "typed-mismatch",
            targets = listOf(Schema.flag),
            values = mapOf("flag" to "not-a-boolean"),
        )

        val daybook = Daybook.open(createTempDirectory().path, Schema) {
            migrations = listOf(source)
        }

        // open 自体は成功する（スキーマは内容を検証しないため、KDoc の予想どおりなら true）
        assertTrue(daybook.contains("flag"))

        val prop = daybook.property(Schema.flag, default = false)
        assertFailsWith<ClassCastException> { prop.get() }
    }

    /**
     * 攻撃 5: [DaybookOpenOptions.migrations] の KDoc は「同じ MigrationSource.id を重複指定
     * した場合は最初の 1 つだけが実行される」と言うが、例示・既存挙動は同一クラスのソース
     * （FakeSource 系）でしか確認されていない。id が同じでも「実装クラスが異なる」2 つの
     * ソースでも同じ規則が適用されるかを確認する（規則は id の値だけを見るのか、
     * インスタンス identity や型も絡むのか）。
     */
    @Test
    fun duplicateId_acrossDifferentSourceImplementations_onlyFirstRuns() {
        val first = MapSource("dup", mapOf("a" to "first-impl"))
        val second = TypedSource("dup", targets = listOf(Schema.a), values = mapOf("a" to "second-impl"))

        val daybook = Daybook.open(createTempDirectory().path, Schema) {
            migrations = listOf(first, second)
        }

        assertEquals(1, first.readCount)
        assertEquals(0, second.readCount)
        assertEquals("first-impl", daybook.getString("a", null))
    }

    /**
     * 攻撃 6: [Daybook.Companion.open] の KDoc は「directory は絶対パスに正規化して同定される」
     * と言い、[MigrationEnvironment] の KDoc は「directory ストアのディレクトリ（絶対・正規化済み
     * パス）」と言う。冗長なセグメント（`.` や中間ディレクトリ経由）を含む directory を渡した
     * とき、MigrationSource.read に渡される environment.directory が実際に正規化されているかを
     * 確認する。
     */
    @Test
    fun migrationEnvironment_directory_isNormalized() {
        val base = createTempDirectory()
        val messyDir = base.resolve("sub").path.let { "$it/../sub/./" }

        var observedDirectory: String? = null
        val source = object : MigrationSource {
            override val id: String = "probe"

            override fun read(environment: MigrationEnvironment): Map<String, Any>? {
                observedDirectory = environment.directory
                return emptyMap()
            }
        }

        Daybook.open(messyDir, Schema) { migrations = listOf(source) }

        val dir = observedDirectory
        assertTrue(dir != null, "environment.directory が観測できていること: $dir")
        assertTrue(
            dir!!.let { !it.contains("/../") && !it.contains("/./") && !it.endsWith("/.") },
            "正規化済みなら '..' や './' のセグメントを含まないはず。実際の値: $dir",
        )
    }
}
