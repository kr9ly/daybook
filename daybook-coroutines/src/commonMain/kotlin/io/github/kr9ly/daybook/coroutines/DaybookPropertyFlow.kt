package io.github.kr9ly.daybook.coroutines

import io.github.kr9ly.daybook.kv.Daybook
import io.github.kr9ly.daybook.kv.DaybookChangeListener
import io.github.kr9ly.daybook.kv.DaybookProperty
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * このプロパティを [Flow] として観測する。
 *
 * collect 時に現在値を発火し、以後はプロパティのキーが変わるたびに新しい値を発火する
 * （remove や clear でキーが消えたときも再読して発火する）。値は conflate される —
 * 遅い collector は中間の書き込みでなく最新状態を見る — うえ、連続する同値は落とされる
 * （[distinctUntilChanged]）。
 *
 * [DaybookChangeListener] に載っているため、変更コールバックは store の専用配送スレッドに
 * 届き、観測できるのは同一プロセス内の編集だけ（:daybook の SharedPreferences 向け
 * asFlow と同じ契約。配送スレッドがメインスレッドでない点だけが異なる）。
 */
public fun <T> DaybookProperty<T>.asFlow(): Flow<T> = callbackFlow {
    val listener = DaybookChangeListener { changedKey, _ ->
        // 値は listener の newValue でなく get() で再読する: map チェーンの decode を通し、
        // 宣言時デフォルト（削除で「不在」に戻ったとき）も一貫して適用するため
        if (changedKey == key) {
            trySend(get())
        }
    }
    daybook.addChangeListener(listener)
    // 初期値は register の後に読む（register 前の変更を取りこぼさない順序）
    trySend(get())
    awaitClose { daybook.removeChangeListener(listener) }
}.conflate().distinctUntilChanged()
