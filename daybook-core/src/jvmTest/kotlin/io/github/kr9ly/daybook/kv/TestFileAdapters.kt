package io.github.kr9ly.daybook.kv

import io.github.kr9ly.daybook.io.FilePath
import io.github.kr9ly.daybook.io.toFilePath
import io.github.kr9ly.daybook.journal.DirectorySync
import io.github.kr9ly.daybook.journal.FileInterProcessLock
import io.github.kr9ly.daybook.journal.FileSink
import io.github.kr9ly.daybook.journal.InterProcessLock
import io.github.kr9ly.daybook.journal.JournalSink
import io.github.kr9ly.daybook.journal.JournalWatcherFactory
import io.github.kr9ly.daybook.journal.SyncMode
import io.github.kr9ly.daybook.journal.platformDirectorySync
import java.io.File

/** java.io.File を受けるテスト用アダプタ（journal 側の TestFileAdapters と同趣旨）。 */
internal fun KvStore.Companion.open(
    directory: File,
    name: String = "daybook",
    syncMode: SyncMode = SyncMode.ASYNC,
    multiProcess: Boolean = false,
    compactionThreshold: Long = KvStore.DEFAULT_COMPACTION_THRESHOLD,
    sinkFactory: (FilePath) -> JournalSink = ::FileSink,
    directorySync: DirectorySync = platformDirectorySync(),
    compactionHook: (CompactionPhase) -> Unit = {},
    lockFactory: (FilePath) -> InterProcessLock = ::FileInterProcessLock,
    watcherFactory: JournalWatcherFactory? = null,
): KvStore = open(
    directory.toFilePath(), name, syncMode, multiProcess, compactionThreshold,
    sinkFactory, directorySync, compactionHook, lockFactory, watcherFactory,
)
