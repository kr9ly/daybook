package io.github.kr9ly.daybook.kv

import platform.Foundation.NSArray
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSUserDefaults

/**
 * マイグレーション元の NSUserDefaults スイートの宣言 — suiteName の定数と、
 * そのスイート内の型付き元キー（[UserDefaultsSourceKey]）のファクトリ。
 *
 * ```kotlin
 * object Legacy {
 *     val standard = UserDefaultsSuite.standard
 *     val group = UserDefaultsSuite.named("group.com.example.shared")
 *     val oldTheme = standard.string("theme")
 * }
 * ```
 *
 * NSUserDefaults は数値がすべて NSNumber に潰れ、書き手の宣言型を実行時に復元できない。
 * 元キーの期待型の宣言がこの曖昧性を消す: 数値系の期待型（boolean / int / long / float /
 * double）は格納値が NSNumber であれば期待型のアクセサで読む。NSNumber でない値
 * （文字列など）は型不一致で、挙動は [NSUserDefaultsMigrationSource] の [MigrationMode] が
 * 決める。
 *
 * 全キーの暗黙 import は提供しない: NSUserDefaults の全キー列挙（dictionaryRepresentation）
 * には NSGlobalDomain 等システム由来のキーが混入するため、iOS では明示列挙が唯一の安全な形。
 */
public class UserDefaultsSuite private constructor(
    /** スイート名。標準スイート（standardUserDefaults）は null。 */
    public val suiteName: String?,
) {

    public companion object {

        /** 標準スイート（`NSUserDefaults.standardUserDefaults`）。 */
        public val standard: UserDefaultsSuite = UserDefaultsSuite(null)

        /** [suiteName] のスイート（App Group 等）。 */
        public fun named(suiteName: String): UserDefaultsSuite {
            require(suiteName.isNotEmpty()) { "suiteName must not be empty" }
            return UserDefaultsSuite(suiteName)
        }
    }

    /** [key] の boolean 元キーを宣言する。 */
    public fun boolean(key: String): UserDefaultsSourceKey<Boolean> =
        UserDefaultsSourceKey(this, key, "boolean") { (it as? NSNumber)?.boolValue }

    /** [key] の int 元キーを宣言する。 */
    public fun int(key: String): UserDefaultsSourceKey<Int> =
        UserDefaultsSourceKey(this, key, "int") { (it as? NSNumber)?.intValue }

    /** [key] の long 元キーを宣言する。 */
    public fun long(key: String): UserDefaultsSourceKey<Long> =
        UserDefaultsSourceKey(this, key, "long") { (it as? NSNumber)?.longLongValue }

    /** [key] の float 元キーを宣言する。 */
    public fun float(key: String): UserDefaultsSourceKey<Float> =
        UserDefaultsSourceKey(this, key, "float") { (it as? NSNumber)?.floatValue }

    /** [key] の double 元キーを宣言する。 */
    public fun double(key: String): UserDefaultsSourceKey<Double> =
        UserDefaultsSourceKey(this, key, "double") { (it as? NSNumber)?.doubleValue }

    /** [key] の string 元キーを宣言する。 */
    public fun string(key: String): UserDefaultsSourceKey<String> =
        UserDefaultsSourceKey(this, key, "string") { value ->
            when (value) {
                is String -> value
                is NSString -> value.toString()
                else -> null
            }
        }

    /**
     * [key] の string-set 元キーを宣言する。元の格納形は文字列の配列（NSArray of NSString）で、
     * 重複は取り込み時に落ちる。
     */
    public fun stringSet(key: String): UserDefaultsSourceKey<Set<String>> =
        UserDefaultsSourceKey(this, key, "string array") { value ->
            val elements = when (value) {
                is List<*> -> value
                is NSArray -> List(value.count.toInt()) { index -> value.objectAtIndex(index.toULong()) }
                else -> null
            }
            val strings = elements?.map {
                when (it) {
                    is String -> it
                    is NSString -> it.toString()
                    else -> null
                }
            }
            if (strings == null || strings.any { it == null }) {
                null
            } else {
                strings.filterNotNull().toSet()
            }
        }
}

/**
 * [UserDefaultsSuite] 内の型付き元キー — 元キー名と期待型を運ぶ。
 *
 * [NSUserDefaultsMigrationBuilder.migrate] のシグネチャで宛先（[DaybookKey]）と型が
 * 突き合わされるため、期待型と宛先の格納型の不一致はコンパイルエラーになる。
 */
public class UserDefaultsSourceKey<T : Any> internal constructor(
    /** このキーが属する元スイート。 */
    public val suite: UserDefaultsSuite,
    /** 元キー名。 */
    public val key: String,
    internal val typeName: String,
    internal val cast: (Any) -> T?,
)

/**
 * [MigrationMode.LENIENT] でスキップされたエントリの内容。
 * [NSUserDefaultsMigrationBuilder.onSkipped] に渡される。
 *
 * @property suiteName スキップが起きた元スイート名（標準スイートは null）。
 * @property key 元キー名。
 * @property value 実際に格納されていた値。
 * @property expectedType 宣言していた期待型の名前。
 */
public class UserDefaultsMigrationSkip internal constructor(
    public val suiteName: String?,
    public val key: String,
    public val value: Any,
    public val expectedType: String,
)

/**
 * NSUserDefaults から daybook ストアへの一回きりの取り込みを宣言する。
 *
 * 1 つのソースがこのストアへの移行宣言の全体を持つ: 複数のスイートからの集約も 1 つの
 * ソース内に宣言し、全スイート分が 1 バッチ・1 マーカーでアトミックに取り込まれる。
 *
 * ```kotlin
 * val source = NSUserDefaultsMigrationSource(
 *     available = { /* UIApplication.sharedApplication.protectedDataAvailable 等 */ true },
 * ) {
 *     migrate(Legacy.oldTheme, into = Settings.theme)
 *     migrate(Legacy.group.boolean("shared_flag"), into = Settings.sharedFlag)
 * }
 * val daybook = Daybook.open(directory, Settings) { migrations = listOf(source) }
 * ```
 *
 * 実行契約（初回生成時のみ・冪等マーカー・失敗時の伝播）は [MigrationSource] を参照。
 * モードの意味論（宣言の矛盾は常に即例外・ソースデータの問題だけがモードの対象・
 * 元キーの欠損は正常系スキップ）は [MigrationMode] を参照。
 *
 * prewarming ガード: iOS はデバイス初回アンロック前のプロセス起動（prewarming）で
 * NSUserDefaults が空を返すことがある。[available] が false を返すと今回は何も取り込まず
 * マーカーも作らないため、次のストア生成時に再試行される。判定は取り込み全体に対して
 * 1 回だけ行う（スイートごとの部分読みはしない）。core は Foundation にしか依存しないため、
 * UIApplication.protectedDataAvailable 等の判定は利用側から注入する。
 *
 * @param mode ソースデータの問題に対する挙動。既定は [MigrationMode.STRICT]。
 * @param id 冪等マーカーの識別子（[MigrationSource.id]）。同じストアに複数のソースを
 *   指定する場合だけ変える。
 * @param available ソースが読める状態かの判定。false で今回スキップ（次回再試行）。
 * @param configure 移行宣言のブロック。
 */
@Suppress("ktlint:standard:function-naming") // コンストラクタ風ファクトリ（Kotlin の慣例）
public fun NSUserDefaultsMigrationSource(
    mode: MigrationMode = MigrationMode.STRICT,
    id: String = "nsuserdefaults",
    available: () -> Boolean = { true },
    configure: NSUserDefaultsMigrationBuilder.() -> Unit,
): MigrationSource {
    val builder = NSUserDefaultsMigrationBuilder().apply(configure)
    return NSUserDefaultsMigrationSourceImpl(mode, id, available, builder.entries.toList(), builder.onSkipped)
}

/** [NSUserDefaultsMigrationSource] の宣言ブロックのレシーバ。 */
public class NSUserDefaultsMigrationBuilder internal constructor() {

    internal val entries = mutableListOf<Entry>()
    private val sourceKeys = HashSet<Pair<String?, String>>()
    private val targetKeys = HashSet<String>()

    /**
     * [MigrationMode.LENIENT] でエントリがスキップされたときに呼ばれる。既定は何もしない。
     * [MigrationMode.STRICT] では呼ばれない（例外で落ちる）。
     */
    public var onSkipped: (UserDefaultsMigrationSkip) -> Unit = {}

    internal class Entry(val source: UserDefaultsSourceKey<*>, val target: DaybookKey<*>)

    /**
     * [source] の値を [into] のキーへ取り込む。型はシグネチャで突き合う（同型のみ。
     * 値変換や型の拡張はスコープ外）。
     *
     * 同じ元キーの重複宣言・同じ宛先への重複宣言は、宣言の矛盾として即例外。
     */
    public fun <T : Any> migrate(source: UserDefaultsSourceKey<T>, into: DaybookKey<T>) {
        require(sourceKeys.add(source.suite.suiteName to source.key)) {
            "source key \"${source.key}\" in suite ${suiteLabel(source.suite.suiteName)} is declared twice"
        }
        require(targetKeys.add(into.name)) {
            "two entries migrate into the same key \"${into.name}\""
        }
        entries += Entry(source, into)
    }
}

private fun suiteLabel(suiteName: String?): String =
    if (suiteName == null) "standard" else "\"$suiteName\""

/**
 * 宣言済みビルダーの実行体。読み取りは全スイート分を 1 つの Map に集約して返し、
 * レジストリ側が 1 バッチ・1 マーカーで適用する。
 */
@OptIn(io.github.kr9ly.daybook.internal.DaybookInternalApi::class)
private class NSUserDefaultsMigrationSourceImpl(
    private val mode: MigrationMode,
    override val id: String,
    private val available: () -> Boolean,
    private val entries: List<NSUserDefaultsMigrationBuilder.Entry>,
    private val onSkipped: (UserDefaultsMigrationSkip) -> Unit,
) : SchemaTargetedMigrationSource {

    override val targets: List<DaybookKey<*>> = entries.map { it.target }

    override fun read(environment: MigrationEnvironment): Map<String, Any>? {
        if (!available()) return null // prewarming: 次のストア生成時に再試行
        val defaultsCache = HashMap<String?, NSUserDefaults>()

        fun defaultsOf(suite: UserDefaultsSuite): NSUserDefaults =
            defaultsCache.getOrPut(suite.suiteName) {
                suite.suiteName?.let { NSUserDefaults(suiteName = it) } ?: NSUserDefaults.standardUserDefaults
            }

        val result = LinkedHashMap<String, Any>()
        entries.forEach { entry ->
            val source = entry.source
            val value = defaultsOf(source.suite).objectForKey(source.key)
                ?: return@forEach // 欠損は正常系スキップ
            val cast = source.cast(value)
            if (cast == null) {
                when (mode) {
                    MigrationMode.STRICT -> throw MigrationException(
                        "\"${source.key}\" in suite ${suiteLabel(source.suite.suiteName)} holds " +
                            "${value::class.simpleName} ($value) but the migration expects ${source.typeName}",
                    )

                    MigrationMode.LENIENT -> onSkipped(
                        UserDefaultsMigrationSkip(source.suite.suiteName, source.key, value, source.typeName),
                    )
                }
            } else {
                result[entry.target.name] = cast
            }
        }
        return result
    }
}
