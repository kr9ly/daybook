@file:OptIn(ExperimentalForeignApi::class)

package io.github.kr9ly.daybook.journal

import io.github.kr9ly.daybook.concurrent.startDetachedThread
import io.github.kr9ly.daybook.io.FilePath
import io.github.kr9ly.daybook.io.IoException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.refTo
import kotlinx.cinterop.usePinned
import platform.linux.IN_CREATE
import platform.linux.IN_DELETE
import platform.linux.IN_MODIFY
import platform.linux.IN_MOVED_FROM
import platform.linux.IN_MOVED_TO
import platform.linux.inotify_add_watch
import platform.linux.inotify_init
import platform.posix.EINTR
import platform.posix.POLLIN
import platform.posix.close
import platform.posix.errno
import platform.posix.pipe
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.read
import platform.posix.write

internal actual fun platformJournalWatcherFactory(): JournalWatcherFactory =
    InotifyJournalWatcherFactory

/**
 * inotify(7) によるジャーナルディレクトリの検知実装（linuxX64 検証ターゲット用）。
 *
 * JVM actual（WatchService）と同じく、イベントの種別・対象ファイル名は見ずに
 * 「変わったかもしれない」通知に畳む（[JournalWatcherFactory] の契約）。
 * 監視は watch ごとの専用スレッドで行い、inotify fd と停止用パイプを poll で同時に待つ。
 * close はパイプへの 1 バイト書き込みで、read ブロック中のスレッドを確実に起こす。
 */
internal object InotifyJournalWatcherFactory : JournalWatcherFactory {

    override fun watch(directory: FilePath, onChange: () -> Unit): AutoCloseable {
        val inotifyFd = inotify_init()
        if (inotifyFd < 0) throw IoException("inotify_init failed (errno=$errno)")
        val mask = (IN_CREATE or IN_MODIFY or IN_DELETE or IN_MOVED_TO or IN_MOVED_FROM).convert<UInt>()
        if (inotify_add_watch(inotifyFd, directory.path, mask) < 0) {
            val watchErrno = errno
            close(inotifyFd)
            throw IoException("inotify_add_watch failed: ${directory.path} (errno=$watchErrno)")
        }
        val stopPipe = IntArray(2)
        if (pipe(stopPipe.refTo(0)) != 0) {
            val pipeErrno = errno
            close(inotifyFd)
            throw IoException("pipe failed (errno=$pipeErrno)")
        }
        startDetachedThread { watchLoop(inotifyFd, stopPipe[0], onChange) }
        return InotifyWatch(stopPipe[1])
    }

    private fun watchLoop(inotifyFd: Int, stopFd: Int, onChange: () -> Unit) {
        memScoped {
            val fds = allocArray<pollfd>(2)
            fds[0].fd = inotifyFd
            fds[0].events = POLLIN.toShort()
            fds[1].fd = stopFd
            fds[1].events = POLLIN.toShort()
            val buffer = ByteArray(4096)
            while (true) {
                val rc = poll(fds, 2.convert(), -1)
                if (rc < 0) {
                    if (errno == EINTR) continue
                    break
                }
                if (fds[1].revents.toInt() and POLLIN != 0) break
                if (fds[0].revents.toInt() and POLLIN != 0) {
                    // イベント内容は見ないため、溜まっている分をひとまとめに読み捨てて 1 回通知する
                    val n = buffer.usePinned { read(inotifyFd, it.addressOf(0), buffer.size.convert()) }
                    if (n <= 0L) break
                    onChange()
                }
            }
        }
        close(inotifyFd)
        close(stopFd)
    }
}

/**
 * 停止用パイプの書き込み側。close で 1 バイト書いて監視スレッドを起こし、終了させる。
 *
 * JVM actual の WatchServiceWatch と同じく、スレッドの join はしない
 * （KvStore.close が書き込みロックの内側から呼ぶため、join するとデッドロックしうる）。
 */
internal class InotifyWatch(private val stopWriteFd: Int) : AutoCloseable {

    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        val wake = byteArrayOf(1)
        wake.usePinned { write(stopWriteFd, it.addressOf(0), 1.convert()) }
        close(stopWriteFd)
    }
}
