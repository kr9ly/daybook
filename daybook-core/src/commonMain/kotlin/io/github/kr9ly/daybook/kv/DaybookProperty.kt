package io.github.kr9ly.daybook.kv

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * [Daybook] の型付きエントリ 1 つ — キー名・値型・デフォルトを宣言 1 箇所に固定し、
 * キー文字列も default 引数も呼び出し側で繰り返させない。
 *
 * ファクトリ拡張（[boolean], [int], [long], [float], [double], [string], [stringSet]）で
 * 生成し、2 通りに使える:
 *
 * ```kotlin
 * class Settings(daybook: Daybook) {
 *     // プロパティデリゲートとして
 *     var darkMode by daybook.boolean("dark_mode", default = false)
 *
 *     // プロパティオブジェクト自体も欲しいとき（asFlow() 等）は値として受ける
 *     val fontScalePref = daybook.double("font_scale", default = 1.0)
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

/** [key] の型付き boolean エントリ。不在なら [default]。 */
public fun Daybook.boolean(key: String, default: Boolean): DaybookProperty<Boolean> =
    DaybookProperty(this, key, { it.getBoolean(key, default) }, { e, v -> e.putBoolean(key, v) })

/** [key] の型付き int エントリ。不在なら [default]。 */
public fun Daybook.int(key: String, default: Int): DaybookProperty<Int> =
    DaybookProperty(this, key, { it.getInt(key, default) }, { e, v -> e.putInt(key, v) })

/** [key] の型付き long エントリ。不在なら [default]。 */
public fun Daybook.long(key: String, default: Long): DaybookProperty<Long> =
    DaybookProperty(this, key, { it.getLong(key, default) }, { e, v -> e.putLong(key, v) })

/** [key] の型付き float エントリ。不在なら [default]。 */
public fun Daybook.float(key: String, default: Float): DaybookProperty<Float> =
    DaybookProperty(this, key, { it.getFloat(key, default) }, { e, v -> e.putFloat(key, v) })

/** [key] の型付き double エントリ。不在なら [default]。 */
public fun Daybook.double(key: String, default: Double): DaybookProperty<Double> =
    DaybookProperty(this, key, { it.getDouble(key, default) }, { e, v -> e.putDouble(key, v) })

/** [key] の型付き string エントリ。不在なら [default]。 */
public fun Daybook.string(key: String, default: String): DaybookProperty<String> =
    DaybookProperty(this, key, { it.getString(key, null) ?: default }, { e, v -> e.putString(key, v) })

/** [key] の nullable string エントリ: 不在は `null`、`null` の代入はキーの削除。 */
public fun Daybook.string(key: String): DaybookProperty<String?> =
    DaybookProperty(this, key, { it.getString(key, null) }, { e, v -> e.putString(key, v) })

/**
 * [key] の型付き string-set エントリ。不在なら [default]。
 *
 * [default] は宣言時にコピーされるため、呼び出し側が渡した Set を後から変更しても
 * 不在キーの読み出し結果は変わらない。
 */
public fun Daybook.stringSet(
    key: String,
    default: Set<String>,
): DaybookProperty<Set<String>> {
    val fixedDefault = default.toSet()
    return DaybookProperty(
        this,
        key,
        { it.getStringSet(key, null) ?: fixedDefault },
        { e, v -> e.putStringSet(key, v) },
    )
}

/** [key] の nullable string-set エントリ: 不在は `null`、`null` の代入はキーの削除。 */
public fun Daybook.stringSet(key: String): DaybookProperty<Set<String>?> =
    DaybookProperty(this, key, { it.getStringSet(key, null) }, { e, v -> e.putStringSet(key, v) })
