package io.github.kr9ly.daybook

import android.content.Context
import android.content.SharedPreferences
import io.github.kr9ly.daybook.kv.KvStore
import io.github.kr9ly.daybook.prefs.DaybookSharedPreferences
import java.io.File

/**
 * Returns a daybook-backed [SharedPreferences] for the given [name].
 *
 * Drop-in replacement for [Context.getSharedPreferences]: the returned instance follows the
 * `SharedPreferences` contract (editor batching, change listeners, default values), while
 * persisting through daybook's append-only journal instead of the framework's XML file.
 * Editor commits are atomic on disk — a crash never leaves a partially applied edit.
 *
 * Like the framework API, the same [name] always returns the same instance within a process,
 * so listeners registered through one reference observe edits made through another.
 *
 * Data lives under `filesDir/daybook/` and is completely separate from the framework's
 * `shared_prefs/` storage; replacing the call site switches the data source.
 *
 * Set [multiProcess] to `true` when several processes of the app open the same [name]:
 * writes are then serialized with an inter-process lock and edits from other processes
 * become visible automatically (a working replacement for the deprecated and unreliable
 * `Context.MODE_MULTI_PROCESS`). All processes must agree on the flag for a given [name];
 * reopening the same name with a different value in the same process throws
 * [IllegalArgumentException]. Change listeners are only invoked for edits made in the
 * same process, matching the framework behavior.
 *
 * One intentional deviation from the framework: clearing via [SharedPreferences.Editor.clear]
 * always notifies listeners once with a `null` key (the API 30+ behavior), regardless of the
 * OS version the app runs on.
 *
 * @param name Preferences file name. Must be non-empty and must not contain `/`.
 * @param multiProcess Enable cross-process write serialization and change propagation.
 */
public fun Context.getDaybookSharedPreferences(
    name: String,
    multiProcess: Boolean = false,
): SharedPreferences = DaybookPreferencesCache.getOrCreate(applicationContext, name, multiProcess)

/**
 * Returns the daybook-backed [SharedPreferences] under the default name.
 *
 * Uses the same naming convention as `PreferenceManager.getDefaultSharedPreferences`
 * (`<packageName>_preferences`), so the logical store lines up one-to-one with the
 * framework's default preferences. See [getDaybookSharedPreferences] for the contract
 * and the [multiProcess] flag.
 */
public fun Context.getDefaultDaybookSharedPreferences(
    multiProcess: Boolean = false,
): SharedPreferences = getDaybookSharedPreferences("${packageName}_preferences", multiProcess)

/**
 * name → インスタンスのプロセス内キャッシュ。
 *
 * フレームワークの getSharedPreferences と同じく「同名は同一インスタンス」を保証する。
 * これはリスナーの可視性（別の取得口から登録したリスナーにも届く）と、
 * アダプタ内の commit 直列化（インスタンス単位のロック）の前提になる。
 */
internal object DaybookPreferencesCache {

    private class Entry(
        val prefs: SharedPreferences,
        val store: KvStore,
        val multiProcess: Boolean,
    )

    private val entries = HashMap<String, Entry>()

    fun getOrCreate(context: Context, name: String, multiProcess: Boolean): SharedPreferences {
        require(name.isNotEmpty()) { "name must not be empty" }
        require(!name.contains('/')) { "name must not contain '/': $name" }
        synchronized(entries) {
            entries[name]?.let { existing ->
                require(existing.multiProcess == multiProcess) {
                    "\"$name\" is already open with multiProcess=${existing.multiProcess}; " +
                        "all callers must use the same flag for the same name"
                }
                return existing.prefs
            }
            val store = KvStore.open(
                directory = File(context.filesDir, "daybook"),
                name = name,
                multiProcess = multiProcess,
            )
            val prefs = DaybookSharedPreferences(store)
            entries[name] = Entry(prefs, store, multiProcess)
            return prefs
        }
    }

    /** テスト専用: キャッシュを空にし、開いていたストアを閉じる。 */
    fun resetForTesting() {
        synchronized(entries) {
            entries.values.forEach { it.store.close() }
            entries.clear()
        }
    }
}
