package io.github.kr9ly.daybook.journal

import io.github.kr9ly.daybook.io.FilePath
import java.nio.file.ClosedWatchServiceException
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import kotlin.concurrent.thread

internal actual fun platformJournalWatcherFactory(): JournalWatcherFactory =
    WatchServiceJournalWatcherFactory

/**
 * WatchService によるジャーナルディレクトリの検知実装。
 *
 * イベントの種別・対象ファイル名は見ずに「変わったかもしれない」通知に畳む
 * （[JournalWatcherFactory] の契約）。監視は watch ごとの専用デーモンスレッドで行い、
 * close で WatchService を閉じるとスレッドは take の例外で抜ける。
 * ディレクトリ自体が消された場合はイベントが来なくなるだけで、スレッドは close まで残る
 * （ストアのディレクトリ削除は運用上の破壊操作で、検知層が面倒を見る対象ではない）。
 *
 * macOS の WatchService はポーリング実装のため、検知が秒オーダーになる。
 */
internal object WatchServiceJournalWatcherFactory : JournalWatcherFactory {

    override fun watch(directory: FilePath, onChange: () -> Unit): AutoCloseable {
        val path = Paths.get(directory.path)
        val service = path.fileSystem.newWatchService()
        path.register(
            service,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_DELETE,
        )
        val watcherThread = thread(name = "daybook-watcher", isDaemon = true) {
            try {
                while (true) {
                    val key = service.take()
                    key.pollEvents()
                    onChange()
                    key.reset()
                }
            } catch (_: ClosedWatchServiceException) {
                // close() による停止
            }
        }
        return WatchServiceWatch(service, watcherThread)
    }
}

internal class WatchServiceWatch(
    private val service: WatchService,
    /** テストが終了を待ち合わせるための監視スレッド参照。 */
    internal val thread: Thread,
) : AutoCloseable {

    /**
     * KvStore.close は書き込みロックの内側から watcher を閉じる。監視スレッドがちょうど
     * onChange で同じロックを待っていてもよいように、ここではスレッドを join しない
     * （join するとデッドロック）。スレッドは take の例外で自然に終了する。
     */
    override fun close() {
        service.close()
    }
}
