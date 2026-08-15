package io.github.kr9ly.daybook.journal

import io.github.kr9ly.daybook.io.FilePath
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WatchServiceJournalWatcherFactoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun watch_firesOnDirectoryChanges() {
        val latch = CountDownLatch(1)
        val watch = WatchServiceJournalWatcherFactory.watch(FilePath(folder.root.path)) {
            latch.countDown()
        }
        File(folder.root, "a.journal").writeText("grow")
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        watch.close()
    }

    @Test
    fun close_stopsWatcherThread() {
        val watch = WatchServiceJournalWatcherFactory.watch(FilePath(folder.root.path)) {}
            as WatchServiceWatch
        watch.close()
        watch.thread.join(TimeUnit.SECONDS.toMillis(10))
        assertFalse(watch.thread.isAlive)
    }
}
