package io.github.kr9ly.daybook

import android.content.SharedPreferences
import io.github.kr9ly.daybook.kv.KvOperation
import io.github.kr9ly.daybook.kv.KvStore
import io.github.kr9ly.daybook.prefs.DaybookSharedPreferences

/**
 * daybook 自身の成果物（daybook-test 等）がモジュール境界を越えるためだけに public に
 * している API のマーカー。サポート対象の表面ではない: 互換性保証はなく、どのリリースでも
 * 変更・削除されうる。
 */
@RequiresOptIn(
    message = "daybook の内部 API — daybook 自身の成果物（daybook-test）専用。互換性保証なし。",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
public annotation class DaybookInternalApi

/**
 * daybook-test の入口: アダプタ層（Editor バッチ・通知算出・リスナーセマンティクス・
 * 防御コピー）は本物の daybook 実装のまま、裏だけがジャーナルでなく in-memory ストアの
 * [SharedPreferences] を組み立てる。
 */
@DaybookInternalApi
public object DaybookTestBridge {

    /**
     * 本物の daybook アダプタで動く in-memory の [SharedPreferences] を生成する。
     *
     * [delivery] はリスナー通知のメインスレッド Handler を置き換える。テストはインライン
     * 実行を渡すことで配送を同期・決定的にする。
     *
     * [writeObserver] は実効的な書き込みバッチごとに 1 回、ジャーナル追記が起きる位置 —
     * 状態への適用とリスナー通知の前 — で呼ばれる。[clearRequested] はバッチに clear が
     * 含まれるか、[changes] は実効変更（編集順、値 `null` は remove）。observer から
     * [java.io.IOException] を投げるとディスク障害と同じ形で書き込みが失敗する:
     * `commit()` は `false` を返し、`apply()` は編集を破棄し、状態は無傷のまま。
     */
    public fun createInMemorySharedPreferences(
        delivery: (Runnable) -> Unit,
        writeObserver: (clearRequested: Boolean, changes: Map<String, Any?>) -> Unit,
    ): SharedPreferences {
        val store = KvStore.openInMemory { op ->
            val singles = when (op) {
                is KvOperation.Single -> listOf(op)
                is KvOperation.Batch -> op.operations
            }
            var clearRequested = false
            val changes = LinkedHashMap<String, Any?>()
            singles.forEach { single ->
                when (single) {
                    is KvOperation.Put -> changes[single.key] = single.value
                    is KvOperation.Remove -> changes[single.key] = null
                    KvOperation.Clear -> clearRequested = true
                }
            }
            writeObserver(clearRequested, changes)
        }
        return DaybookSharedPreferences(store) { action -> delivery(action) }
    }
}
