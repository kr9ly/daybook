package io.github.kr9ly.daybook.journal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FileObserverJournalWatcherFactory の実機検証。
 *
 * FileObserver（inotify）は JVM / Robolectric で動かないため Kover の対象外にしており、
 * その代わりにここで実機の検知挙動を検証する。マルチプロセス機構が依存するのは
 * 「追記（MODIFY）と世代切替の rename（MOVED_TO）で onChange が呼ばれる」こと。
 */
@RunWith(AndroidJUnit4::class)
class FileObserverJournalWatcherTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun newDirectory(name: String): File =
        File(context.cacheDir, name).apply {
            deleteRecursively()
            check(mkdirs()) { "failed to create $this" }
        }

    private fun watchAndAwait(dir: File, timeoutSeconds: Long = 5, action: () -> Unit): Boolean {
        val latch = CountDownLatch(1)
        val watcher = FileObserverJournalWatcherFactory().watch(dir) { latch.countDown() }
        try {
            action()
            return latch.await(timeoutSeconds, TimeUnit.SECONDS)
        } finally {
            watcher.close()
        }
    }

    @Test
    fun appendToFile_triggersOnChange() {
        val dir = newDirectory("watcher-append")
        val file = File(dir, "store.1.journal").apply { writeBytes(byteArrayOf(1)) }
        assertTrue(watchAndAwait(dir) { file.appendBytes(byteArrayOf(2)) })
    }

    @Test
    fun renameIntoPlace_triggersOnChange() {
        val dir = newDirectory("watcher-rename")
        val temp = File(dir, "store.2.journal.tmp").apply { writeBytes(byteArrayOf(1)) }
        assertTrue(watchAndAwait(dir) { check(temp.renameTo(File(dir, "store.2.journal"))) })
    }

    @Test
    fun fileCreation_triggersOnChange() {
        val dir = newDirectory("watcher-create")
        assertTrue(watchAndAwait(dir) { File(dir, "store.1.journal").writeBytes(byteArrayOf(1)) })
    }

    @Test
    fun close_stopsWatching() {
        val dir = newDirectory("watcher-close")
        val file = File(dir, "store.1.journal").apply { writeBytes(byteArrayOf(1)) }
        val latch = CountDownLatch(1)
        val watcher = FileObserverJournalWatcherFactory().watch(dir) { latch.countDown() }
        watcher.close()
        file.appendBytes(byteArrayOf(2))
        assertFalse(latch.await(1, TimeUnit.SECONDS))
    }
}
