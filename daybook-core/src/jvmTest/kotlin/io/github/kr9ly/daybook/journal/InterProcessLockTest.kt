package io.github.kr9ly.daybook.journal

import java.io.File
import java.nio.channels.ClosedChannelException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * FileInterProcessLock の JVM で検証できる範囲のテスト。
 *
 * FileLock は同一 JVM 内の重複取得が OverlappingFileLockException になるため、
 * プロセス間の相互排他そのものはここでは検証できない（Instrumentation テストの守備範囲）。
 * ここでは取得・解放・例外時の解放・クローズの契約を検証する。
 */
class InterProcessLockTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun lockFile(): File = File(tmp.root, "store.lock")

    @Test
    fun withLock_returnsBodyResult() {
        FileInterProcessLock(lockFile()).use { lock ->
            assertEquals("result", lock.withLock { "result" })
        }
    }

    @Test
    fun withLock_createsLockFileIfMissing() {
        FileInterProcessLock(lockFile()).use { lock ->
            lock.withLock {}
            assertTrue(lockFile().exists())
        }
    }

    @Test
    fun withLock_releasesAfterBody() {
        // 解放されていなければ、同一チャネルへの再取得は OverlappingFileLockException になる
        FileInterProcessLock(lockFile()).use { lock ->
            lock.withLock {}
            lock.withLock {}
        }
    }

    @Test
    fun withLock_releasesOnException() {
        FileInterProcessLock(lockFile()).use { lock ->
            assertThrows(IllegalStateException::class.java) {
                lock.withLock { throw IllegalStateException("boom") }
            }
            lock.withLock {} // 例外後も取得できる = 解放済み
        }
    }

    @Test
    fun withLock_worksAcrossInstancesSequentially() {
        // 別インスタンス（別チャネル）でも、解放後なら取得できる
        FileInterProcessLock(lockFile()).use { first ->
            first.withLock {}
            FileInterProcessLock(lockFile()).use { second ->
                second.withLock {}
            }
        }
    }

    @Test
    fun close_preventsFurtherLocking() {
        val lock = FileInterProcessLock(lockFile())
        lock.close()
        assertThrows(ClosedChannelException::class.java) {
            lock.withLock {}
        }
    }
}
