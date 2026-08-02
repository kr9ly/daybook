package io.github.kr9ly.daybook.coroutines

import android.content.SharedPreferences
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/**
 * Observes key changes of this `SharedPreferences` as a [Flow].
 *
 * Emits the changed key on every change notification, and `null` when `clear()` removed
 * entries (the API 30+ callback contract). Collection starts silent — there is no initial
 * emission, because a key-change stream has no "current" element.
 *
 * This is an event stream, not a state stream: unlike [PreferenceProperty.asFlow] it is
 * neither conflated nor deduplicated, and the buffer is unbounded so a slow collector
 * never drops a change. The flip side of the unbounded buffer: a collector that stays
 * suspended while changes keep flowing accumulates them in memory, so cancel the flow
 * when you stop consuming.
 *
 * Backed by [SharedPreferences.OnSharedPreferenceChangeListener], so it works against any
 * `SharedPreferences` implementation and shares its contract: change callbacks arrive on
 * the main thread, and only edits made in the same process are observed.
 */
public fun SharedPreferences.changesAsFlow(): Flow<String?> = callbackFlow {
    val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
        trySend(changedKey)
    }
    registerOnSharedPreferenceChangeListener(listener)
    awaitClose { unregisterOnSharedPreferenceChangeListener(listener) }
}.buffer(Channel.UNLIMITED)
