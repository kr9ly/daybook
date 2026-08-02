package io.github.kr9ly.daybook

import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * [SharedPreferences] の型付きエントリ 1 つ — キー名・値型・デフォルトを宣言 1 箇所に固定し、
 * キー文字列も defValue 引数も呼び出し側で繰り返させない。
 *
 * ファクトリ拡張（[boolean], [int], [long], [float], [string], [stringSet]）で生成し、
 * 2 通りに使える:
 *
 * ```kotlin
 * class Settings(prefs: SharedPreferences) {
 *     // プロパティデリゲートとして
 *     var darkMode by prefs.boolean("dark_mode", default = false)
 *
 *     // プロパティオブジェクト自体も欲しいとき（asFlow() 等）は値として受ける
 *     val fontScalePref = prefs.float("font_scale", default = 1.0f)
 *     var fontScale by fontScalePref
 * }
 * ```
 *
 * [set]（およびデリゲート代入）はフレームワークの慣用に合わせて `apply()` で書く。
 * 複数キーのアトミックな一括更新は素の [SharedPreferences.edit] に落とす。
 * SharedPreferences 実装なら何の上でも動く — フレームワーク実装でも daybook でも — ため、
 * 型付きアクセスはバッキングストアの移行前にも移行後にも導入できる。
 *
 * インスタンスは不変。読み書きのスレッド安全性は背後の [SharedPreferences] の契約に従う。
 */
public class PreferenceProperty<T> internal constructor(
    /** このプロパティが読み書きする prefs インスタンス。 */
    public val preferences: SharedPreferences,
    /** このプロパティの格納キー。 */
    public val key: String,
    private val read: (SharedPreferences) -> T,
    private val write: (SharedPreferences.Editor, T) -> Unit,
) : ReadWriteProperty<Any?, T> {

    /** 現在値を返す。不在なら宣言時に固定したデフォルト。 */
    public fun get(): T = read(preferences)

    /** [value] を `apply()` で書き込む。nullable なプロパティでは `null` がキーの削除。 */
    public fun set(value: T) {
        val editor = preferences.edit()
        write(editor, value)
        editor.apply()
    }

    override fun getValue(thisRef: Any?, property: KProperty<*>): T = get()

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        set(value)
    }

    /**
     * 境界での変換によって、このプロパティを別の値型に適合させる:
     * 読むたびに [decode]、書くたびに [encode] が走る。
     *
     * 返り値は同じキーの上の完全な [PreferenceProperty] なので、デリゲートも `asFlow()` も
     * 無変更で効く。デフォルトは格納側の世界で宣言されたまま — `map` は純粋な値変換であり、
     * 「不在」を見ることはない。
     *
     * ```kotlin
     * var theme by prefs.string("theme", default = Theme.SYSTEM.name)
     *     .map(decode = Theme::valueOf, encode = Theme::name)
     * ```
     *
     * 変換の失敗（例: [decode] がもう理解できない格納値）はそのまま伝播する。読み取りを
     * 寛容にしたい場合は `map` の後ろに [catch] をチェーンしてフォールバックで回復する。
     * 書き込み時の [encode] の失敗も同様にそのまま伝播する — 呼び出し側のバグであり、
     * [catch] は意図的に読み取り経路だけを対象にする。
     */
    public fun <R> map(decode: (T) -> R, encode: (R) -> T): PreferenceProperty<R> =
        PreferenceProperty(
            preferences,
            key,
            { preferences -> decode(read(preferences)) },
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
     * var theme by prefs.string("theme", default = Theme.SYSTEM.name)
     *     .map(decode = Theme::valueOf, encode = Theme::name)
     *     .catch { Theme.SYSTEM }
     * ```
     */
    public fun catch(handler: (Exception) -> T): PreferenceProperty<T> =
        PreferenceProperty(
            preferences,
            key,
            { preferences ->
                try {
                    read(preferences)
                } catch (e: Exception) {
                    handler(e)
                }
            },
            write,
        )
}

/** [key] の型付き boolean エントリ。不在なら [default]。 */
public fun SharedPreferences.boolean(key: String, default: Boolean): PreferenceProperty<Boolean> =
    PreferenceProperty(this, key, { it.getBoolean(key, default) }, { e, v -> e.putBoolean(key, v) })

/** [key] の型付き int エントリ。不在なら [default]。 */
public fun SharedPreferences.int(key: String, default: Int): PreferenceProperty<Int> =
    PreferenceProperty(this, key, { it.getInt(key, default) }, { e, v -> e.putInt(key, v) })

/** [key] の型付き long エントリ。不在なら [default]。 */
public fun SharedPreferences.long(key: String, default: Long): PreferenceProperty<Long> =
    PreferenceProperty(this, key, { it.getLong(key, default) }, { e, v -> e.putLong(key, v) })

/** [key] の型付き float エントリ。不在なら [default]。 */
public fun SharedPreferences.float(key: String, default: Float): PreferenceProperty<Float> =
    PreferenceProperty(this, key, { it.getFloat(key, default) }, { e, v -> e.putFloat(key, v) })

/** [key] の型付き string エントリ。不在なら [default]。 */
public fun SharedPreferences.string(key: String, default: String): PreferenceProperty<String> =
    PreferenceProperty(this, key, { it.getString(key, null) ?: default }, { e, v -> e.putString(key, v) })

/** [key] の nullable string エントリ: 不在は `null`、`null` の代入はキーの削除。 */
public fun SharedPreferences.string(key: String): PreferenceProperty<String?> =
    PreferenceProperty(this, key, { it.getString(key, null) }, { e, v -> e.putString(key, v) })

/**
 * [key] の型付き string-set エントリ。不在なら [default]。
 *
 * [default] は宣言時にコピーされるため、呼び出し側が渡した Set を後から変更しても
 * 不在キーの読み出し結果は変わらない。
 */
public fun SharedPreferences.stringSet(
    key: String,
    default: Set<String>,
): PreferenceProperty<Set<String>> {
    val fixedDefault = default.toSet()
    return PreferenceProperty(
        this,
        key,
        { it.getStringSet(key, null) ?: fixedDefault },
        { e, v -> e.putStringSet(key, v) },
    )
}

/** [key] の nullable string-set エントリ: 不在は `null`、`null` の代入はキーの削除。 */
public fun SharedPreferences.stringSet(key: String): PreferenceProperty<Set<String>?> =
    PreferenceProperty(this, key, { it.getStringSet(key, null) }, { e, v -> e.putStringSet(key, v) })
