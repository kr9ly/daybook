package io.github.kr9ly.daybook.journal

import android.os.FileObserver
import java.io.Closeable
import java.io.File

// このファイルは JVM ユニットテストで実行できないため Kover の対象外
// （FileObserver の inotify は Robolectric でも実質動かない。DESIGN.md のテスト戦略を参照）。
// 実機挙動は Instrumentation テストで検証する。

/** Android 用。FileObserver（inotify）でディレクトリを監視する。 */
internal class FileObserverJournalWatcherFactory : JournalWatcherFactory {
    override fun watch(directory: File, onChange: () -> Unit): Closeable {
        // File を取るコンストラクタは API 29+ のため String 版を使う（minSdk 21）
        @Suppress("DEPRECATION")
        val observer = object : FileObserver(
            directory.path,
            MODIFY or CREATE or MOVED_TO or DELETE,
        ) {
            override fun onEvent(event: Int, path: String?) {
                onChange()
            }
        }
        observer.startWatching()
        return Closeable { observer.stopWatching() }
    }
}
