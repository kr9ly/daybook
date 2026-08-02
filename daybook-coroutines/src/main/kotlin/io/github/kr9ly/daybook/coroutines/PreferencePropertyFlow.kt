package io.github.kr9ly.daybook.coroutines

import android.content.SharedPreferences
import io.github.kr9ly.daybook.PreferenceProperty
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Observes this property as a [Flow].
 *
 * Emits the current value on collection, then the new value whenever the property's key
 * changes (a `clear()` re-reads as well). Values are conflated — a slow collector sees
 * the latest state, not every intermediate write — and equal consecutive values are
 * dropped ([distinctUntilChanged]).
 *
 * Backed by [SharedPreferences.OnSharedPreferenceChangeListener], so it works against any
 * `SharedPreferences` implementation and shares its contract: change callbacks arrive on
 * the main thread, and only edits made in the same process are observed.
 */
public fun <T> PreferenceProperty<T>.asFlow(): Flow<T> = callbackFlow {
    val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
        // changedKey == null は clear（API 30+ 挙動）。自キーが消えた可能性があるため再読する
        if (changedKey == null || changedKey == key) {
            trySend(get())
        }
    }
    preferences.registerOnSharedPreferenceChangeListener(listener)
    // 初期値は register の後に読む（register 前の変更を取りこぼさない順序）
    trySend(get())
    awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
}.conflate().distinctUntilChanged()
