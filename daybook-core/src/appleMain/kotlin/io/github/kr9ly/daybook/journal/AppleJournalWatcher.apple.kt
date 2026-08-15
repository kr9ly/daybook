@file:OptIn(ExperimentalForeignApi::class)

package io.github.kr9ly.daybook.journal

import io.github.kr9ly.daybook.concurrent.Lock
import io.github.kr9ly.daybook.concurrent.withLock
import io.github.kr9ly.daybook.io.FilePath
import io.github.kr9ly.daybook.io.IoException
import io.github.kr9ly.daybook.io.listDirectory
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.ptr
import platform.darwin.DISPATCH_VNODE_DELETE
import platform.darwin.DISPATCH_VNODE_EXTEND
import platform.darwin.DISPATCH_VNODE_RENAME
import platform.darwin.DISPATCH_VNODE_WRITE
import platform.darwin._dispatch_source_type_vnode
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_resume
import platform.darwin.dispatch_source_cancel
import platform.darwin.dispatch_source_create
import platform.darwin.dispatch_source_set_cancel_handler
import platform.darwin.dispatch_source_set_event_handler
import platform.darwin.dispatch_source_t
import platform.posix.O_EVTONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.open

internal actual fun platformJournalWatcherFactory(): JournalWatcherFactory =
    DispatchSourceJournalWatcherFactory

/**
 * dispatch source（DISPATCH_SOURCE_TYPE_VNODE）によるジャーナルディレクトリの検知実装。
 *
 * vnode 監視の実体は kqueue の EVFILT_VNODE と同じだが、Kotlin/Native の iOS platform lib は
 * sys/event.h（kqueue/kevent）を含まないため、自前 cinterop なしで使える dispatch source を採る。
 *
 * inotify と違い、vnode 監視はディレクトリのエントリ増減しか報せず、既存ファイルへの追記
 * （ジャーナル成長）は対象ファイル自身の vnode を監視しないと検知できない。
 * このためディレクトリに加えて直下の各ファイルを個別に監視し、イベントのたびに
 * ディレクトリを再走査して監視対象を追随させる（走査から登録までの隙間に起きた変化は、
 * その走査を起こしたイベントの通知自体がカバーする — 受け手は通知のたびにジャーナル側を確認する契約）。
 *
 * イベントの種別・対象は見ずに「変わったかもしれない」通知に畳む点は他プラットフォームと同じ。
 */
internal object DispatchSourceJournalWatcherFactory : JournalWatcherFactory {

    override fun watch(directory: FilePath, onChange: () -> Unit): AutoCloseable {
        val dirFd = open(directory.path, O_EVTONLY)
        if (dirFd < 0) throw IoException("cannot open for watch: ${directory.path} (errno=$errno)")
        return DispatchSourceWatch(directory, dirFd, onChange)
    }
}

private val vnodeEvents =
    (DISPATCH_VNODE_WRITE or DISPATCH_VNODE_EXTEND or DISPATCH_VNODE_DELETE or DISPATCH_VNODE_RENAME).convert<ULong>()

internal class DispatchSourceWatch(
    private val directory: FilePath,
    dirFd: Int,
    private val onChange: () -> Unit,
) : AutoCloseable {

    /** イベントハンドラの実行キュー。 */
    private val queue = dispatch_queue_create("io.github.kr9ly.daybook.journal.watch", null)

    /** [closed] と [fileSources] を守る。再走査（init スレッド / queue）と close を直列化する。 */
    private val lock = Lock()
    private var closed = false

    /** 監視中のファイル。[lock] の下でのみ触る。 */
    private val fileSources = mutableMapOf<String, dispatch_source_t>()

    private val dirSource: dispatch_source_t

    init {
        val source = createVnodeSource(dirFd) {
            rescanFiles()
            notifyChange()
        }
        if (source == null) {
            close(dirFd)
            throw IoException("dispatch_source_create failed: ${directory.path}")
        }
        dirSource = source
        // 監視開始時点で存在するファイル（開いたばかりのジャーナル等）の監視を、watch() が
        // 返る前にここで同期的に確立する。非同期に回すと「watch() 直後の追記」が登録前に
        // 起きたとき取りこぼす（シミュレータ CI の追記検知テストで実際に発生した競合）
        rescanFiles()
    }

    /** [fd] の vnode イベントで [handler] を呼ぶ source を作って開始する。失敗時は null（fd は閉じない）。 */
    private fun createVnodeSource(fd: Int, handler: () -> Unit): dispatch_source_t {
        val source = dispatch_source_create(_dispatch_source_type_vnode.ptr, fd.convert(), vnodeEvents, queue)
            ?: return null
        dispatch_source_set_event_handler(source, handler)
        // cancel 完了後はハンドラがもう走らないため、fd はここで閉じるのが安全
        dispatch_source_set_cancel_handler(source) { close(fd) }
        dispatch_resume(source)
        return source
    }

    /**
     * ディレクトリ直下を走査し、増えたファイルの監視を登録・消えたファイルの監視を外す。
     * close 済みなら何もしない（close 後に監視を増やしてリークさせない）。
     */
    private fun rescanFiles() {
        lock.withLock {
            if (closed) return
            val names = listDirectory(directory)?.toSet() ?: emptySet()
            val vanished = fileSources.keys.filter { it !in names }
            for (name in vanished) {
                fileSources.remove(name)?.let { dispatch_source_cancel(it) }
            }
            for (name in names) {
                if (name in fileSources) continue
                // 走査と open の間に消えた等の失敗は黙殺する。次のイベントの再走査で追随する
                val fd = open(directory.resolve(name).path, O_EVTONLY)
                if (fd < 0) continue
                val source = createVnodeSource(fd) { notifyChange() }
                if (source == null) {
                    close(fd)
                    continue
                }
                fileSources[name] = source
            }
        }
    }

    private fun notifyChange() {
        val stopped = lock.withLock { closed }
        if (!stopped) onChange()
    }

    /**
     * 監視を止める。以後の通知は closed フラグで抑止する。
     *
     * JVM actual の WatchServiceWatch と同じく、監視側との同期待ち（dispatch_sync）はしない
     * （KvStore.close が書き込みロックの内側から呼ぶため、イベントハンドラが onChange 経由で
     * 同じロックを待っていると dispatch_sync はデッドロックする）。
     * dispatch_source_cancel は非ブロッキングで、fd を閉じる cancel handler は queue 上で後から走る。
     */
    override fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            dispatch_source_cancel(dirSource)
            for (source in fileSources.values) {
                dispatch_source_cancel(source)
            }
            fileSources.clear()
        }
    }
}
