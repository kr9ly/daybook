package io.github.kr9ly.daybook.kv

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * [Daybook] の型付きエントリ 1 つ — スキーマで宣言したキー（[DaybookKey]）にデフォルトを
 * 与えて読み書き可能にしたもの。キー名・値型の出所は [DaybookSchema] の宣言 1 箇所に固定される。
 *
 * [property] で生成し、2 通りに使える:
 *
 * ```kotlin
 * object Settings : DaybookSchema("settings") {
 *     val darkMode = boolean("dark_mode")
 *     val fontScale = double("font_scale")
 * }
 *
 * class AppSettings(daybook: Daybook) {
 *     // プロパティデリゲートとして
 *     var darkMode by daybook.property(Settings.darkMode, default = false)
 *
 *     // プロパティオブジェクト自体も欲しいとき（asFlow() 等）は値として受ける
 *     val fontScalePref = daybook.property(Settings.fontScale, default = 1.0)
 *     var fontScale by fontScalePref
 * }
 * ```
 *
 * [set]（およびデリゲート代入）は 1 キーの [Daybook.edit] として書く。
 * 複数キーのアトミックな一括更新は素の [Daybook.edit] に落とす。
 *
 * :daybook の 1.x PreferenceProperty と同型で、依存先を SharedPreferences から
 * [Daybook] に差し替えたもの。map / catch / デリゲートの契約は同一。
 *
 * インスタンスは不変。読み書きのスレッド安全性は背後の [Daybook] の契約に従う。
 */
public class DaybookProperty<T> internal constructor(
    /** このプロパティが読み書きするストア。 */
    public val daybook: Daybook,
    /** このプロパティの格納キー。 */
    public val key: String,
    private val read: (Daybook) -> T,
    private val write: (DaybookEditor, T) -> Unit,
) : ReadWriteProperty<Any?, T> {

    /** 現在値を返す。不在なら宣言時に固定したデフォルト。 */
    public fun get(): T = read(daybook)

    /** [value] を 1 バッチとして書き込む。nullable なプロパティでは `null` がキーの削除。 */
    public fun set(value: T) {
        daybook.edit { write(this, value) }
    }

    override fun getValue(thisRef: Any?, property: KProperty<*>): T = get()

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        set(value)
    }

    /**
     * 境界での変換によって、このプロパティを別の値型に適合させる:
     * 読むたびに [decode]、書くたびに [encode] が走る。
     *
     * 返り値は同じキーの上の完全な [DaybookProperty] なので、デリゲートも `asFlow()` も
     * 無変更で効く。デフォルトは格納側の世界で宣言されたまま — `map` は純粋な値変換であり、
     * 「不在」を見ることはない。
     *
     * ```kotlin
     * var theme by daybook.string("theme", default = Theme.SYSTEM.name)
     *     .map(decode = Theme::valueOf, encode = Theme::name)
     * ```
     *
     * 変換の失敗（例: [decode] がもう理解できない格納値）はそのまま伝播する。読み取りを
     * 寛容にしたい場合は `map` の後ろに [catch] をチェーンしてフォールバックで回復する。
     * 書き込み時の [encode] の失敗も同様にそのまま伝播する — 呼び出し側のバグであり、
     * [catch] は意図的に読み取り経路だけを対象にする。
     */
    public fun <R> map(decode: (T) -> R, encode: (R) -> T): DaybookProperty<R> =
        DaybookProperty(
            daybook,
            key,
            { daybook -> decode(read(daybook)) },
            { editor, value -> write(editor, encode(value)) },
        )

    /**
     * 読み取りの失敗を [handler] で回復する。`Flow.catch` と同型: 対象は読み取り経路だけで、
     * 書き込みの失敗は呼び出し側のバグとしてそのまま伝播する。
     *
     * 「読み取りの失敗」は読み取り中に投げられたあらゆる例外を指し、上流の [map] の decode
     * 失敗に限らない: 既存キーを型違いのファクトリで読んだときの `ClassCastException` も
     * 回復される。型違いを大きな音で落としたい場合は、プロパティに `catch` を付けず、
     * decode の失敗は [map] の内側で処理すること。
     *
     * ```kotlin
     * var theme by daybook.string("theme", default = Theme.SYSTEM.name)
     *     .map(decode = Theme::valueOf, encode = Theme::name)
     *     .catch { Theme.SYSTEM }
     * ```
     */
    public fun catch(handler: (Exception) -> T): DaybookProperty<T> =
        DaybookProperty(
            daybook,
            key,
            { daybook ->
                try {
                    read(daybook)
                } catch (e: Exception) {
                    handler(e)
                }
            },
            write,
        )
}

/**
 * [key] の型付きプロパティ。不在なら [default]。
 *
 * [key] はこのストアのスキーマで宣言されたものであること: 別のスキーマのキーを渡すと
 * IllegalArgumentException（ストア束縛のランタイム検査。誤ったストアを静かに読み書き
 * しないための fail-fast）。
 *
 * string-set の [default] は生成時にコピーされるため、呼び出し側が渡した Set を後から
 * 変更しても不在キーの読み出し結果は変わらない。
 */
public fun <T : Any> Daybook.property(key: DaybookKey<T>, default: T): DaybookProperty<T> {
    checkSchema(key)
    val fixedDefault = key.fixDefault(default)
    return DaybookProperty(this, key.name, { key.readWithDefault(it, fixedDefault) }, key.write)
}

/** [key] の nullable string プロパティ: 不在は `null`、`null` の代入はキーの削除。スキーマ検査は default 付きの [property] と同じ。 */
public fun Daybook.property(key: StringKey): DaybookProperty<String?> {
    checkSchema(key)
    return DaybookProperty(this, key.name, key::readOrNull, key::writeNullable)
}

/** [key] の nullable string-set プロパティ: 不在は `null`、`null` の代入はキーの削除。スキーマ検査は default 付きの [property] と同じ。 */
public fun Daybook.property(key: StringSetKey): DaybookProperty<Set<String>?> {
    checkSchema(key)
    return DaybookProperty(this, key.name, key::readOrNull, key::writeNullable)
}

private fun Daybook.checkSchema(key: DaybookKey<*>) {
    require(key.schema === schema) {
        "key \"${key.name}\" belongs to schema \"${key.schema.storeName}\" but this store was " +
            "opened with schema \"${schema.storeName}\"; keys can only be used with their own schema"
    }
}
