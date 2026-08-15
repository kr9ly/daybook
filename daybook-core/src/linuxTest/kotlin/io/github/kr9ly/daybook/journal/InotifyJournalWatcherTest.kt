package io.github.kr9ly.daybook.journal

import io.github.kr9ly.daybook.concurrent.Lock
import io.github.kr9ly.daybook.concurrent.waitUntil
import io.github.kr9ly.daybook.concurrent.withLock
import io.github.kr9ly.daybook.io.createEmptyFile
import io.github.kr9ly.daybook.io.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InotifyJournalWatcherTest {

    @Test
    fun watch_notifiesOnFileCreationAndModification() {
        val dir = createTempDirectory()
        val lock = Lock()
        var notified = 0
        val watch = platformJournalWatcherFactory().watch(dir) { lock.withLock { notified++ } }
        try {
            createEmptyFile(dir.resolve("created"))
            assertTrue(waitUntil { lock.withLock { notified } >= 1 })

            val before = lock.withLock { notified }
            FileSink(dir.resolve("created")).use { sink -> sink.write(byteArrayOf(1)) }
            assertTrue(waitUntil { lock.withLock { notified } > before })
        } finally {
            watch.close()
        }
    }

    @Test
    fun close_stopsNotifications_andIsIdempotent() {
        val dir = createTempDirectory()
        val lock = Lock()
        var notified = 0
        val watch = platformJournalWatcherFactory().watch(dir) { lock.withLock { notified++ } }
        createEmptyFile(dir.resolve("first"))
        assertTrue(waitUntil { lock.withLock { notified } >= 1 })

        watch.close()
        watch.close()

        // 停止が監視スレッドに届くまで猶予を置いてから変化を起こし、通知が来ないことを見る
        val settled = lock.withLock { notified }
        createEmptyFile(dir.resolve("second"))
        assertTrue(!waitUntil(timeoutMillis = 300) { lock.withLock { notified } > settled })
        assertEquals(settled, lock.withLock { notified })
    }
}
