package io.github.kr9ly.daybook.journal

import java.io.Closeable
import java.io.File

/**
 * ジャーナルディレクトリの変化（他プロセスの追記による成長・世代切替）の検知。
 *
 * 通知は「変わったかもしれない」の一種類だけで、何がどう変わったかは受け手が
 * ジャーナル側を確認して判断する。イベントの種別や対象ファイル名に依存しないことで、
 * 検知機構のイベント合流・取りこぼしに頑健になる（確認して差がなければ何もしないだけ）。
 *
 * Android 実装は FileObserver（inotify。JVM で実行不可のためカバレッジ除外 +
 * Instrumentation テストで検証）。JVM テストは手動トリガの実装を注入する。
 */
internal fun interface JournalWatcherFactory {
    /**
     * [directory] の監視を開始し、変化のたび [onChange] を呼ぶ。
     * 返り値の [Closeable] で監視を止める。[onChange] は検知機構のスレッドから
     * 呼ばれうるため、呼び出し先で適切に同期する。
     */
    fun watch(directory: File, onChange: () -> Unit): Closeable
}
