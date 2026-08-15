package io.github.kr9ly.daybook.kv

import io.github.kr9ly.daybook.concurrent.Lock
import io.github.kr9ly.daybook.concurrent.waitUntil
import io.github.kr9ly.daybook.concurrent.withLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NotificationDispatchThreadTest {

    @Test
    fun deliver_runsActionsInOrder_offCallerStack() {
        val dispatch = NotificationDispatchThread()
        val lock = Lock()
        val delivered = mutableListOf<Int>()
        repeat(100) { index ->
            dispatch.deliver { lock.withLock { delivered.add(index) } }
        }
        assertTrue(waitUntil { lock.withLock { delivered.size } == 100 })
        assertEquals((0 until 100).toList(), lock.withLock { delivered.toList() })
        dispatch.close()
    }

    @Test
    fun close_drainsEnqueuedActions() {
        val dispatch = NotificationDispatchThread()
        val lock = Lock()
        var count = 0
        repeat(50) {
            dispatch.deliver { lock.withLock { count++ } }
        }
        dispatch.close()
        assertTrue(waitUntil { lock.withLock { count } == 50 })
    }

    @Test
    fun deliver_afterClose_isRejected() {
        val dispatch = NotificationDispatchThread()
        dispatch.close()
        assertFailsWith<IllegalStateException> { dispatch.deliver { } }
    }

    @Test
    fun deliver_continuesAfterActionFailure() {
        val dispatch = NotificationDispatchThread()
        val lock = Lock()
        var reached = false
        dispatch.deliver { throw IllegalStateException("listener failure") }
        dispatch.deliver { lock.withLock { reached = true } }
        assertTrue(waitUntil { lock.withLock { reached } })
        dispatch.close()
    }
}
