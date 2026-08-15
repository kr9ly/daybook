package io.github.kr9ly.daybook.journal

import io.github.kr9ly.daybook.internal.DaybookInternalApi
import io.github.kr9ly.daybook.io.FilePath

/**
 * ジャーナルディレクトリの変化（他プロセスの追記による成長・世代切替）の検知。
 *
 * 通知は「変わったかもしれない」の一種類だけで、何がどう変わったかは受け手が
 * ジャーナル側を確認して判断する。イベントの種別や対象ファイル名に依存しないことで、
 * 検知機構のイベント合流・取りこぼしに頑健になる（確認して差がなければ何もしないだけ）。
 *
 * Android 実装は :daybook 側の FileObserver（inotify。JVM で実行不可のためカバレッジ除外 +
 * Instrumentation テストで検証）。JVM テストは手動トリガの実装を注入する。
 */
@DaybookInternalApi
public fun interface JournalWatcherFactory {
    /**
     * [directory] の監視を開始し、変化のたび [onChange] を呼ぶ。
     * 返り値の [AutoCloseable] で監視を止める。[onChange] は検知機構のスレッドから
     * 呼ばれうるため、呼び出し先で適切に同期する。
     */
    public fun watch(directory: FilePath, onChange: () -> Unit): AutoCloseable
}

/**
 * プラットフォーム既定の検知実装。公開 open API（Daybook.open）の multiProcess が内部で結線する。
 *
 * JVM actual は WatchService（macOS ではポーリング実装のため検知が秒オーダー）。
 * Android の SharedPreferences 互換 API（:daybook）は従来どおり FileObserver 実装を明示注入する。
 */
internal expect fun platformJournalWatcherFactory(): JournalWatcherFactory
