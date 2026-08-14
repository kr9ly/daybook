package io.github.kr9ly.daybook.kv

import io.github.kr9ly.daybook.io.FilePath
import io.github.kr9ly.daybook.journal.FileObserverJournalWatcherFactory
import io.github.kr9ly.daybook.journal.SyncMode
import io.github.kr9ly.daybook.journal.defaultDirectorySync
import java.io.File

/**
 * java.io.File を受けるテスト用アダプタ（journal 側の TestFileAdapters と同趣旨）。
 *
 * 1.x の KvStore.open デフォルト（Android の DirectorySync / FileObserver watcher）を
 * ここで復元し、Instrumentation テストは実機のデフォルト構成をそのまま検証する。
 */
internal fun KvStore.Companion.open(
    directory: File,
    name: String = "daybook",
    syncMode: SyncMode = SyncMode.ASYNC,
    multiProcess: Boolean = false,
    compactionThreshold: Long = KvStore.DEFAULT_COMPACTION_THRESHOLD,
): KvStore = open(
    directory = FilePath(directory.path),
    name = name,
    syncMode = syncMode,
    multiProcess = multiProcess,
    compactionThreshold = compactionThreshold,
    directorySync = defaultDirectorySync(),
    watcherFactory = if (multiProcess) FileObserverJournalWatcherFactory() else null,
)
