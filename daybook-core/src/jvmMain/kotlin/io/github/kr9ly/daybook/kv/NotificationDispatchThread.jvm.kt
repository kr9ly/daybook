package io.github.kr9ly.daybook.kv

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal actual class NotificationDispatchThread : ChangeNotificationDelivery {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "daybook-dispatch").apply { isDaemon = true }
    }

    actual override fun deliver(action: () -> Unit) {
        executor.execute(action)
    }

    actual fun close() {
        executor.shutdown()
    }
}
