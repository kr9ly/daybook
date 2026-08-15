package io.github.kr9ly.daybook.kv

/**
 * ストア宣言 — ストア名とキー一式を 1 箇所に固定する、daybook の宣言レイヤーの頂点。
 *
 * アプリは object としてスキーマを宣言し、open（[Daybook.Companion.open]、Android の
 * `Context.openDaybook`、テストの `TestDaybook.getDaybook`）・プロパティ生成（[property]）・
 * マイグレーションの宛先宣言のすべてが同じ宣言を参照する。ストア名とキー名の文字列が
 * 宣言の外に繰り返されることはなく、タイポは構造的に起きない。
 *
 * ```kotlin
 * object Settings : DaybookSchema(name = "settings") {
 *     val darkMode = boolean("dark_mode")
 *     val fontScale = double("font_scale")
 *     val userName = string("user_name")
 * }
 *
 * val daybook = Daybook.open(directory, Settings)
 * var darkMode by daybook.property(Settings.darkMode, default = false)
 * ```
 *
 * キーはスキーマ内のファクトリ（[boolean] / [int] / [long] / [float] / [double] /
 * [string] / [stringSet]）で宣言する。同じキー名の二重宣言は宣言時（オブジェクト初期化時）に
 * IllegalArgumentException で即座に失敗する。
 *
 * スキーマの同一性はオブジェクト同一性で判定される: 同じストアを別のスキーマオブジェクトで
 * 開き直すことはできない（open が IllegalArgumentException）。スキーマは object（または
 * プロセス内で 1 つのインスタンス）として宣言すること。
 *
 * スキーマはストア内容の制約ではない: 宣言されていないキーがストアに存在してもよい
 * （SharedPreferences 互換 API 経由の書き込みや全キー import の結果など）。宣言は「型付き API から
 * 見える面」を固定するだけで、検証や削除は行わない。
 *
 * @param name ストア名。空文字と `/` を含む名前は不可（IllegalArgumentException）。
 */
public abstract class DaybookSchema(name: String) {

    /**
     * ストア名。プロパティ名を name にしないのは、サブクラスの `val name = string("name")`
     * のようなキー宣言と衝突させないため。
     */
    public val storeName: String = name

    private val declaredKeys = HashSet<String>()

    init {
        require(name.isNotEmpty()) { "name must not be empty" }
        require(!name.contains('/')) { "name must not contain '/': $name" }
    }

    /** [key] の boolean キーを宣言する。不在時のデフォルトは利用側（[property]）で与える。 */
    protected fun boolean(key: String): DaybookKey<Boolean> = register(
        DaybookKey(
            this,
            key,
            readWithDefault = { daybook, default -> daybook.getBoolean(key, default) },
            write = { editor, value -> editor.putBoolean(key, value) },
        ),
    )

    /** [key] の int キーを宣言する。不在時のデフォルトは利用側（[property]）で与える。 */
    protected fun int(key: String): DaybookKey<Int> = register(
        DaybookKey(
            this,
            key,
            readWithDefault = { daybook, default -> daybook.getInt(key, default) },
            write = { editor, value -> editor.putInt(key, value) },
        ),
    )

    /** [key] の long キーを宣言する。不在時のデフォルトは利用側（[property]）で与える。 */
    protected fun long(key: String): DaybookKey<Long> = register(
        DaybookKey(
            this,
            key,
            readWithDefault = { daybook, default -> daybook.getLong(key, default) },
            write = { editor, value -> editor.putLong(key, value) },
        ),
    )

    /** [key] の float キーを宣言する。不在時のデフォルトは利用側（[property]）で与える。 */
    protected fun float(key: String): DaybookKey<Float> = register(
        DaybookKey(
            this,
            key,
            readWithDefault = { daybook, default -> daybook.getFloat(key, default) },
            write = { editor, value -> editor.putFloat(key, value) },
        ),
    )

    /** [key] の double キーを宣言する。不在時のデフォルトは利用側（[property]）で与える。 */
    protected fun double(key: String): DaybookKey<Double> = register(
        DaybookKey(
            this,
            key,
            readWithDefault = { daybook, default -> daybook.getDouble(key, default) },
            write = { editor, value -> editor.putDouble(key, value) },
        ),
    )

    /**
     * [key] の string キーを宣言する。
     * default 付きの [property] に加えて、default なしの nullable 版 property も作れる。
     */
    protected fun string(key: String): StringKey = register(StringKey(this, key))

    /**
     * [key] の string-set キーを宣言する。
     * default 付きの [property] に加えて、default なしの nullable 版 property も作れる。
     */
    protected fun stringSet(key: String): StringSetKey = register(StringSetKey(this, key))

    private fun <K : DaybookKey<*>> register(key: K): K {
        require(key.name.isNotEmpty()) { "key must not be empty (schema \"$storeName\")" }
        require(declaredKeys.add(key.name)) {
            "key \"${key.name}\" is declared twice in schema \"$storeName\""
        }
        return key
    }
}

/**
 * [DaybookSchema] 内で宣言された型付きキー — キー名・格納型・所属スキーマを運ぶ。
 *
 * 値の読み書きには [property] でプロパティ化して使う。キーは所属スキーマを知っているため、
 * 別のスキーマで開いたストアに誤って使うと property 生成時に即例外になる（ストア束縛の
 * ランタイム検査）。
 *
 * インスタンスは不変で、同一性はオブジェクト同一性。
 */
public open class DaybookKey<T : Any> internal constructor(
    /** このキーが属するスキーマ。 */
    public val schema: DaybookSchema,
    /** ストア内のキー名。 */
    public val name: String,
    internal val readWithDefault: (Daybook, T) -> T,
    internal val write: (DaybookEditor, T) -> Unit,
    /** property 生成時にデフォルト値を固定する（string-set の防御コピー用。他は恒等）。 */
    internal val fixDefault: (T) -> T = { it },
)

/** string キー。default なしの nullable 版 [property] を作れる点だけが [DaybookKey] と違う。 */
public class StringKey internal constructor(schema: DaybookSchema, name: String) :
    DaybookKey<String>(
        schema,
        name,
        readWithDefault = { daybook, default -> daybook.getString(name, null) ?: default },
        write = { editor, value -> editor.putString(name, value) },
    ) {

    internal fun readOrNull(daybook: Daybook): String? = daybook.getString(name, null)

    /** nullable 版 property の書き込み。`null` は putString の契約どおりキーの削除。 */
    internal fun writeNullable(editor: DaybookEditor, value: String?) {
        editor.putString(name, value)
    }
}

/** string-set キー。default なしの nullable 版 [property] を作れる点だけが [DaybookKey] と違う。 */
public class StringSetKey internal constructor(schema: DaybookSchema, name: String) :
    DaybookKey<Set<String>>(
        schema,
        name,
        readWithDefault = { daybook, default -> daybook.getStringSet(name, null) ?: default },
        write = { editor, value -> editor.putStringSet(name, value) },
        fixDefault = { it.toSet() },
    ) {

    internal fun readOrNull(daybook: Daybook): Set<String>? = daybook.getStringSet(name, null)

    /** nullable 版 property の書き込み。`null` は putStringSet の契約どおりキーの削除。 */
    internal fun writeNullable(editor: DaybookEditor, value: Set<String>?) {
        editor.putStringSet(name, value)
    }
}
