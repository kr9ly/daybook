package io.github.kr9ly.daybook.coroutines

import io.github.kr9ly.daybook.kv.Daybook
import io.github.kr9ly.daybook.kv.DaybookChangeListener
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/**
 * このストアのキー変更を [Flow] として観測する。
 *
 * 通知は操作ベース: ジャーナルに書かれた操作のキーがそのまま流れ、同じ値の put や
 * 不在キーの remove も発火する。clear は消えた各キーが個別に流れる（:daybook の
 * SharedPreferences 向け changesAsFlow が「実効変更のみ・clear は null」だったのとは
 * 異なる、core の顔の契約）。collect 開始時は無音 — キー変更の流れに「現在の要素」は
 * 存在しないため、初期発火はない。
 *
 * これはイベント流: [DaybookProperty.asFlow] と違い conflate も重複排除もされず、
 * バッファは無制限なので遅い collector でも変更を取りこぼさない。無制限バッファの
 * 裏返しとして、collector が停止したまま変更が流れ続けるとメモリに溜まっていくので、
 * 消費をやめるときは Flow をキャンセルすること。
 */
public fun Daybook.changesAsFlow(): Flow<String> = callbackFlow {
    val listener = DaybookChangeListener { key, _ ->
        trySend(key)
    }
    addChangeListener(listener)
    awaitClose { removeChangeListener(listener) }
}.buffer(Channel.UNLIMITED)
