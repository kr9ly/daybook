package io.github.kr9ly.daybook.coroutines

import android.content.SharedPreferences
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/**
 * この SharedPreferences のキー変更を [Flow] として観測する。
 *
 * 変更通知のたびに変更されたキーを発火し、`clear()` でエントリが消えたときは `null` を
 * 発火する（API 30+ のコールバック契約）。collect 開始時は無音 — キー変更の流れに
 * 「現在の要素」は存在しないため、初期発火はない。
 *
 * これは状態流でなくイベント流: [PreferenceProperty.asFlow] と違い conflate も重複排除も
 * されず、バッファは無制限なので遅い collector でも変更を取りこぼさない。無制限バッファの
 * 裏返しとして、collector が停止したまま変更が流れ続けるとメモリに溜まっていくので、
 * 消費をやめるときは Flow をキャンセルすること。
 *
 * [SharedPreferences.OnSharedPreferenceChangeListener] に載っているため、どの
 * SharedPreferences 実装の上でも動き、その契約を共有する: 変更コールバックは
 * メインスレッドに届き、観測できるのは同一プロセス内の編集だけ。
 */
public fun SharedPreferences.changesAsFlow(): Flow<String?> = callbackFlow {
    val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
        trySend(changedKey)
    }
    registerOnSharedPreferenceChangeListener(listener)
    awaitClose { unregisterOnSharedPreferenceChangeListener(listener) }
}.buffer(Channel.UNLIMITED)
