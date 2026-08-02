package io.github.kr9ly.daybook.kv

import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kr9ly.daybook.ProcessKillTest
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * プロセスキル耐性の実機検証（flaky 前提の隔離スイート。実行方法は [ProcessKillTest] を参照）。
 *
 * ワーカープロセスを SIGKILL で落とし、生き残った側から見た不変条件を検証する:
 * 確認済み（ACK 済み）の書き込みが読めること、死んだプロセスが握っていた FileLock で
 * 後続の読み書きがブロックされないこと（カーネルによる自動解放）、書き込み中キルの
 * ジャーナルを開き直しても一貫した状態に復旧すること。
 */
@ProcessKillTest
@RunWith(AndroidJUnit4::class)
class ProcessKillResilienceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var directory: File
    private lateinit var store: KvStore
    private lateinit var worker: WorkerClient

    @Before
    fun setUp() {
        directory = File(context.filesDir, "killtest-${System.nanoTime()}")
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
    fun killAfterAcknowledgedWrite_dataSurvives_andLockDoesNotLeak() {
        worker.put("acked", "v")
        killWorkerAndAwaitGone()
        // readFresh はプロセス間ロックを取る。死んだワーカーの FileLock が
        // カーネルに解放されていなければここで進めない
        assertEquals("v", store.readFresh("acked"))
        store.put("after-kill", "w")
        assertEquals("w", store.readFresh("after-kill"))
    }

    @Test
    fun killDuringWriteLoop_survivorKeepsWorking_andReopenRecovers() {
        worker.startWriteLoop("count")
        // ループが実際に書き始めたのを確認してから、書き込みの真っ最中に殺す
        awaitCondition("write loop did not start") { store.readFresh("count") != null }
        killWorkerAndAwaitGone()

        // 生き残った store は読み書きとも動き続けること
        assertNotNull(store.readFresh("count"))
        store.put("after-kill", "ok")
        assertEquals("ok", store.readFresh("after-kill"))

        // 書き込み中キルのジャーナル（不完全テールがありうる）を開き直しても復旧すること
        store.close()
        store = KvStore.open(directory = directory, multiProcess = true)
        assertEquals("ok", store.readFresh("after-kill"))
        val count = store.readFresh("count")
        assertNotNull(count)
        assertNotNull("count should be a decimal string: $count", (count as String).toIntOrNull())
    }

    private fun killWorkerAndAwaitGone() {
        val pid = worker.pid
        check(pid > 0) { "worker pid unknown" }
        Process.killProcess(pid)
        awaitCondition("worker process $pid did not die") { !File("/proc/$pid").exists() }
    }

    private fun awaitCondition(message: String, timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { message }
            SystemClock.sleep(50)
        }
    }
}
