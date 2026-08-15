package io.github.kr9ly.daybook.kv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 2 プロセス相互読み書きの実機検証。
 *
 * JVM テストは InterProcessLock / JournalWatcherFactory に偽物を注入して複数プロセスを
 * 模しているため、実 FileLock（プロセス間排他）と実 FileObserver（プロセス跨ぎ検知）の
 * 結合はここでしか検証できない。ワーカーは [MultiProcessWorkerService]
 * （android:process 指定の別プロセス）で、Messenger の返信を同期点に使う。
 */
@RunWith(AndroidJUnit4::class)
class MultiProcessKvStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var directory: File
    private lateinit var store: KvStore
    private lateinit var worker: WorkerClient

    @Before
    fun setUp() {
        directory = File(context.filesDir, "multiproc-${System.nanoTime()}")
        worker = WorkerClient(context)
        worker.open(directory)
        store = KvStore.open(directory = directory, multiProcess = true)
    }

    @After
    fun tearDown() {
        store.close()
        worker.close()
        directory.deleteRecursively()
    }

    @Test
    fun workerReadsFresh_seesWriteFromThisProcess() {
        store.put("from-main", "hello")
        assertEquals("hello", worker.readFresh("from-main"))
    }

    @Test
    fun readFresh_seesWorkerWrite_beforeWatcherDelivery() {
        // 返信を受けた時点でワーカーの追記は完了しているが、
        // こちらの watcher にはまだ届いていないかもしれない。
        // readFresh はその遅れをプロセス間ロック下のキャッチアップで埋める契約
        worker.put("from-worker", "hi")
        assertEquals("hi", store.readFresh("from-worker"))
    }

    @Test
    fun watcher_deliversWorkerWrite_withoutExplicitRead() {
        val latch = CountDownLatch(1)
        var observed: Any? = null
        store.addListener { key, newValue ->
            if (key == "watched") {
                observed = newValue
                latch.countDown()
            }
        }
        worker.put("watched", "via-inotify")
        assertTrue("watcher did not deliver within 10s", latch.await(10, TimeUnit.SECONDS))
        assertEquals("via-inotify", observed)
    }

    @Test
    fun interleavedWrites_bothProcessesConvergeToSameState() {
        repeat(10) { i ->
            store.put("main/$i", "m$i")
            worker.put("worker/$i", "w$i")
        }
        repeat(10) { i ->
            assertEquals("m$i", store.readFresh("main/$i"))
            assertEquals("w$i", store.readFresh("worker/$i"))
            assertEquals("m$i", worker.readFresh("main/$i"))
            assertEquals("w$i", worker.readFresh("worker/$i"))
        }
    }

    @Test
    fun compactionInThisProcess_workerFollowsGenerationSwitch() {
        // ワーカーが旧世代を開いた状態で、こちらの store を小さい閾値で開き直して
        // compaction（世代切替）を起こす。ワーカーの次の読みが新世代へ追随すること
        store.close()
        store = KvStore.open(
            directory = directory,
            multiProcess = true,
            compactionThreshold = 256,
        )
        val big = "x".repeat(128)
        repeat(10) { i -> store.put("gen", "$big-$i") }
        assertEquals("$big-9", worker.readFresh("gen"))
        // 切替後も相互の書き込みが通ること
        worker.put("after-compaction", "ok")
        assertEquals("ok", store.readFresh("after-compaction"))
    }
}
