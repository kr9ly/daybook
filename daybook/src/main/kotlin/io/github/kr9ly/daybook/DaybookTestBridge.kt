package io.github.kr9ly.daybook

import android.content.SharedPreferences
import io.github.kr9ly.daybook.kv.KvOperation
import io.github.kr9ly.daybook.kv.KvStore
import io.github.kr9ly.daybook.prefs.DaybookSharedPreferences

/**
 * Marks daybook API that is public only so daybook's own artifacts (such as `daybook-test`)
 * can reach across the module boundary. It is not part of the supported surface: no
 * compatibility guarantees, and it may change or disappear in any release.
 */
@RequiresOptIn(
    message = "Internal daybook API — reserved for daybook's own artifacts (daybook-test). " +
        "No compatibility guarantees.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
public annotation class DaybookInternalApi

/**
 * Entry point for `daybook-test`: builds a [SharedPreferences] whose adapter layer
 * (editor batching, notification computation, listener semantics, defensive copies)
 * is the real daybook implementation, backed by an in-memory store instead of a journal.
 */
@DaybookInternalApi
public object DaybookTestBridge {

    /**
     * Creates an in-memory [SharedPreferences] running the real daybook adapter.
     *
     * [delivery] replaces the main-thread handler for listener notifications; tests pass
     * an inline executor to make delivery synchronous and deterministic.
     *
     * [writeObserver] is invoked once per effective write batch, at the position where the
     * journal append would happen — before the state is applied and listeners are notified.
     * [clearRequested] reports whether the batch contains a clear; [changes] holds the
     * effective changes in edit order (`null` value = remove). Throwing an [java.io.IOException]
     * from the observer fails the write like a disk failure: `commit()` returns `false`,
     * `apply()` discards the edit, and the state stays untouched.
     */
    public fun createInMemorySharedPreferences(
        delivery: (Runnable) -> Unit,
        writeObserver: (clearRequested: Boolean, changes: Map<String, Any?>) -> Unit,
    ): SharedPreferences {
        val store = KvStore.openInMemory { op ->
            val singles = when (op) {
                is KvOperation.Single -> listOf(op)
                is KvOperation.Batch -> op.operations
            }
            var clearRequested = false
            val changes = LinkedHashMap<String, Any?>()
            singles.forEach { single ->
                when (single) {
                    is KvOperation.Put -> changes[single.key] = single.value
                    is KvOperation.Remove -> changes[single.key] = null
                    KvOperation.Clear -> clearRequested = true
                }
            }
            writeObserver(clearRequested, changes)
        }
        return DaybookSharedPreferences(store) { action -> delivery(action) }
    }
}
