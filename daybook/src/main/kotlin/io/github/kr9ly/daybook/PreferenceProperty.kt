package io.github.kr9ly.daybook

import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * A single typed entry of a [SharedPreferences] — the key name, the value type, and the
 * default are fixed in one place, so neither the key string nor the `defValue` argument
 * is ever repeated at call sites.
 *
 * Created by the factory extensions ([boolean], [int], [long], [float], [string],
 * [stringSet]) and usable in two ways:
 *
 * ```kotlin
 * class Settings(prefs: SharedPreferences) {
 *     // as a property delegate
 *     var darkMode by prefs.boolean("dark_mode", default = false)
 *
 *     // as a value when you also need the property object itself (e.g. for asFlow())
 *     val fontScalePref = prefs.float("font_scale", default = 1.0f)
 *     var fontScale by fontScalePref
 * }
 * ```
 *
 * [set] (and delegated assignment) writes with `apply()`, following the framework idiom;
 * batch multiple keys atomically through the plain [SharedPreferences.edit] when needed.
 * Works against any `SharedPreferences` implementation — the framework one as well as
 * daybook's — so typed access can be adopted before or after migrating the backing store.
 */
public class PreferenceProperty<T> internal constructor(
    /** The preferences instance this property reads from and writes to. */
    public val preferences: SharedPreferences,
    /** The key this property is stored under. */
    public val key: String,
    private val read: (SharedPreferences) -> T,
    private val write: (SharedPreferences.Editor, T) -> Unit,
) : ReadWriteProperty<Any?, T> {

    /** Returns the current value, or the default fixed at declaration when absent. */
    public fun get(): T = read(preferences)

    /** Writes [value] with `apply()`. For nullable properties, `null` removes the key. */
    public fun set(value: T) {
        val editor = preferences.edit()
        write(editor, value)
        editor.apply()
    }

    override fun getValue(thisRef: Any?, property: KProperty<*>): T = get()

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        set(value)
    }
}

/** Typed boolean entry under [key], returning [default] when absent. */
public fun SharedPreferences.boolean(key: String, default: Boolean): PreferenceProperty<Boolean> =
    PreferenceProperty(this, key, { it.getBoolean(key, default) }, { e, v -> e.putBoolean(key, v) })

/** Typed int entry under [key], returning [default] when absent. */
public fun SharedPreferences.int(key: String, default: Int): PreferenceProperty<Int> =
    PreferenceProperty(this, key, { it.getInt(key, default) }, { e, v -> e.putInt(key, v) })

/** Typed long entry under [key], returning [default] when absent. */
public fun SharedPreferences.long(key: String, default: Long): PreferenceProperty<Long> =
    PreferenceProperty(this, key, { it.getLong(key, default) }, { e, v -> e.putLong(key, v) })

/** Typed float entry under [key], returning [default] when absent. */
public fun SharedPreferences.float(key: String, default: Float): PreferenceProperty<Float> =
    PreferenceProperty(this, key, { it.getFloat(key, default) }, { e, v -> e.putFloat(key, v) })

/** Typed string entry under [key], returning [default] when absent. */
public fun SharedPreferences.string(key: String, default: String): PreferenceProperty<String> =
    PreferenceProperty(this, key, { it.getString(key, null) ?: default }, { e, v -> e.putString(key, v) })

/** Nullable string entry under [key]: absent reads as `null`, setting `null` removes the key. */
public fun SharedPreferences.string(key: String): PreferenceProperty<String?> =
    PreferenceProperty(this, key, { it.getString(key, null) }, { e, v -> e.putString(key, v) })

/** Typed string-set entry under [key], returning [default] when absent. */
public fun SharedPreferences.stringSet(
    key: String,
    default: Set<String>,
): PreferenceProperty<Set<String>> =
    PreferenceProperty(
        this,
        key,
        { it.getStringSet(key, null) ?: default },
        { e, v -> e.putStringSet(key, v) },
    )

/** Nullable string-set entry under [key]: absent reads as `null`, setting `null` removes the key. */
public fun SharedPreferences.stringSet(key: String): PreferenceProperty<Set<String>?> =
    PreferenceProperty(this, key, { it.getStringSet(key, null) }, { e, v -> e.putStringSet(key, v) })
