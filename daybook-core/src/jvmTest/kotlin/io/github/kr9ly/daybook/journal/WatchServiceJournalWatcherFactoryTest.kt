package io.github.kr9ly.daybook.journal

import io.github.kr9ly.daybook.io.createTempDirectory
import io.github.kr9ly.daybook.io.writeFileBytes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchServiceJournalWatcherFactoryTest {

    private val folder = createTempDirectory()

    @Test
    fun watch_firesOnDirectoryChanges() {
        val latch = CountDownLatch(1)
        val watch = WatchServiceJournalWatcherFactory.watch(folder) {
            latch.countDown()
        }
        writeFileBytes(folder.resolve("a.journal"), "grow".encodeToByteArray())
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        watch.close()
    }

    @Test
    fun close_stopsWatcherThread() {
        val watch = WatchServiceJournalWatcherFactory.watch(folder) {}
            as WatchServiceWatch
        watch.close()
        watch.thread.join(TimeUnit.SECONDS.toMillis(10))
        assertFalse(watch.thread.isAlive)
    }
}
