package io.github.kr9ly.daybook.journal

import android.os.FileObserver
import io.github.kr9ly.daybook.io.FilePath

// このファイルは JVM ユニットテストで実行できないため Kover の対象外
// （FileObserver の inotify は Robolectric でも実質動かない。DESIGN.md のテスト戦略を参照）。
// 実機挙動は Instrumentation テストで検証する。

/** Android 用。FileObserver（inotify）でディレクトリを監視する。 */
internal class FileObserverJournalWatcherFactory : JournalWatcherFactory {
    override fun watch(directory: FilePath, onChange: () -> Unit): AutoCloseable {
        // File を取るコンストラクタは API 29+ のため String 版を使う（minSdk 21）
        @Suppress("DEPRECATION")
        val observer = object : FileObserver(
            directory.path,
            MODIFY or CREATE or MOVED_TO or DELETE,
        ) {
            override fun onEvent(event: Int, path: String?) {
                // onEvent にはマスク外のイベントも届く（stopWatching 時の IN_IGNORED 等）。
                // マスク対象だけに絞らないと close 後にもコールバックが発火する
                if (event and (MODIFY or CREATE or MOVED_TO or DELETE) != 0) {
                    onChange()
                }
            }
        }
        observer.startWatching()
        return AutoCloseable { observer.stopWatching() }
    }
}
