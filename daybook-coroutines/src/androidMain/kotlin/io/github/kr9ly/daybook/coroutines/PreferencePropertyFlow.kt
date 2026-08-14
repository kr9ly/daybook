package io.github.kr9ly.daybook.coroutines

import android.content.SharedPreferences
import io.github.kr9ly.daybook.PreferenceProperty
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * このプロパティを [Flow] として観測する。
 *
 * collect 時に現在値を発火し、以後はプロパティのキーが変わるたびに新しい値を発火する
 * （`clear()` でも再読する）。値は conflate される — 遅い collector は中間の書き込みでなく
 * 最新状態を見る — うえ、連続する同値は落とされる（[distinctUntilChanged]）。
 *
 * [SharedPreferences.OnSharedPreferenceChangeListener] に載っているため、どの
 * SharedPreferences 実装の上でも動き、その契約を共有する: 変更コールバックは
 * メインスレッドに届き、観測できるのは同一プロセス内の編集だけ。
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
