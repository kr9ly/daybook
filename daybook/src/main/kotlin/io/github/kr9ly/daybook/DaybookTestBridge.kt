package io.github.kr9ly.daybook

import android.content.SharedPreferences
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
 * 防御コピー）は本物の daybook 実装のまま、裏のストアを外から渡して
 * [SharedPreferences] の顔を組み立てる。
 *
 * ストアの生成（in-memory・書き込み観測・失敗注入）は daybook-test 側の責務。
 * 顔を組み立てる側と分離することで、同じストアに core の顔（Daybook）と
 * SharedPreferences の顔を同時にかぶせられる。
 */
@DaybookInternalApi
public object DaybookTestBridge {

    /**
     * [store] の上で本物の daybook アダプタとして動く [SharedPreferences] を返す。
     *
     * [delivery] はリスナー通知のメインスレッド Handler を置き換える。テストはインライン
     * 実行を渡すことで配送を同期・決定的にする。
     *
     * 書き込みの失敗（ストアからの [java.io.IOException]）はディスク障害と同じ形で現れる:
     * `commit()` は `false` を返し、`apply()` は編集を破棄し、状態は無傷のまま。
     */
    public fun wrapAsSharedPreferences(
        store: KvStore,
        delivery: (Runnable) -> Unit,
    ): SharedPreferences =
        DaybookSharedPreferences(store) { action -> delivery(Runnable { action() }) }
}
