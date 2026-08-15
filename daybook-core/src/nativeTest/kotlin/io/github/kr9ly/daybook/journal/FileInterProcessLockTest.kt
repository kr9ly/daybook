package io.github.kr9ly.daybook.journal

import io.github.kr9ly.daybook.concurrent.startDetachedThread
import io.github.kr9ly.daybook.concurrent.waitUntil
import io.github.kr9ly.daybook.io.createTempDirectory
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileInterProcessLockTest {

    @Test
    fun withLock_returnsBodyResult_andIsReacquirable() {
        val dir = createTempDirectory()
        FileInterProcessLock(dir.resolve("lock")).use { lock ->
            assertEquals(42, lock.withLock { 42 })
            assertEquals("again", lock.withLock { "again" })
        }
    }

    @Test
    fun withLock_excludesOtherHandleWhileHeld() {
        // flock は open file description 単位のため、同一プロセス内でも別 open どうしで排他が観測できる
        val dir = createTempDirectory()
        val file = dir.resolve("lock")
        val holder = Holder()

        FileInterProcessLock(file).use { first ->
            first.withLock {
                startDetachedThread {
                    FileInterProcessLock(file).use { second ->
                        second.withLock { holder.acquired = true }
                    }
                    holder.finished = true
                }
                // 反対側のスレッドが起動してロック待ちに入るまで猶予を置いても取得できない
                assertTrue(!waitUntil(timeoutMillis = 300) { holder.acquired })
            }
        }
        assertTrue(waitUntil { holder.finished })
        assertTrue(holder.acquired)
    }

    private class Holder {
        @Volatile var acquired = false

        @Volatile var finished = false
    }
}
