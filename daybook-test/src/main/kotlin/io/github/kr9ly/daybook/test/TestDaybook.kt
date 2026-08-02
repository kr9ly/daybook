package io.github.kr9ly.daybook.test

import android.content.SharedPreferences
import io.github.kr9ly.daybook.DaybookInternalApi
import io.github.kr9ly.daybook.DaybookTestBridge
import java.io.IOException

/**
 * An in-memory daybook world for application unit tests, running on the plain JVM.
 *
 * [getSharedPreferences] returns instances driven by the real daybook adapter stack:
 * editor batching, effective-change computation (same-value puts and absent-key removes
 * are dropped), listener semantics (weak references, reverse key order, `clear` = one
 * `null`-key callback), and defensive copies all behave exactly as in production.
 * Only the persistence layer is absent — nothing touches the file system, and no
 * Android runtime (Robolectric or a device) is needed.
 *
 * The typed property API and the Flow adapters work on top of these instances unchanged,
 * because they only depend on the `SharedPreferences` interface.
 *
 * One deliberate difference from production: listener notifications are delivered
 * synchronously on the committing thread, so when `commit()`/`apply()` returns,
 * listeners (and flows collected with an unconfined dispatcher) have already run.
 * This makes assertions deterministic without pumping a main looper.
 *
 * Each `TestDaybook` is an isolated world: create one per test (or per test class) and
 * throw it away — there is no global state and no reset to remember. Within one instance
 * the production contract holds: the same [name] returns the same object, and reopening
 * a name with a different `multiProcess` flag throws [IllegalArgumentException].
 * The flag has no other effect in-memory, where everything is one process anyway.
 *
 * @param packageName Package name used by [getDefaultSharedPreferences] to derive the
 *   default store name (`<packageName>_preferences`), mirroring the production API.
 */
public class TestDaybook(private val packageName: String = "test") {

    private class Entry {
        var prefs: SharedPreferences? = null
        var multiProcess: Boolean? = null
        val commits = mutableListOf<RecordedCommit>()
        var failNextWrite = false
    }

    private val lock = Any()
    private val entries = HashMap<String, Entry>()

    /**
     * Returns the in-memory [SharedPreferences] for [name], creating it on first access.
     *
     * Same-name calls return the same instance, so listeners registered through one
     * reference observe edits made through another — as in production.
     *
     * @param name Preferences name. Must be non-empty and must not contain `/`.
     * @param multiProcess Accepted for signature parity with the production API.
     *   Consistency across calls is enforced, but the flag changes nothing in-memory.
     */
    public fun getSharedPreferences(
        name: String,
        multiProcess: Boolean = false,
    ): SharedPreferences {
        synchronized(lock) {
            val entry = entryFor(name)
            entry.prefs?.let { existing ->
                require(entry.multiProcess == multiProcess) {
                    "\"$name\" is already open with multiProcess=${entry.multiProcess}; " +
                        "all callers must use the same flag for the same name"
                }
                return existing
            }
            val prefs = createPreferences(entry)
            entry.prefs = prefs
            entry.multiProcess = multiProcess
            return prefs
        }
    }

    /**
     * Returns the in-memory [SharedPreferences] under the default name
     * (`<packageName>_preferences`), mirroring `getDefaultDaybookSharedPreferences`.
     */
    public fun getDefaultSharedPreferences(multiProcess: Boolean = false): SharedPreferences =
        getSharedPreferences("${packageName}_preferences", multiProcess)

    /**
     * Returns the commits recorded so far for [name], oldest first.
     *
     * One [RecordedCommit] per effective write batch — the same granularity at which
     * production writes a journal record and guarantees atomicity. Edits that change
     * nothing (same-value puts, removes of absent keys, empty edits) never reach the
     * journal in production and are not recorded here either. Writes failed by
     * [failNextWrite] are not recorded.
     *
     * The returned list is a snapshot; later commits do not modify it.
     */
    public fun commits(name: String): List<RecordedCommit> {
        synchronized(lock) {
            return entryFor(name).commits.toList()
        }
    }

    /**
     * Makes the next effective write to [name] fail like a disk failure: `commit()`
     * returns `false`, `apply()` silently discards the edit, and in both cases the
     * state stays untouched and no listener fires. Later writes succeed again.
     *
     * Edits that change nothing do not consume the injection, because production
     * never reaches the disk for them.
     *
     * May be called before the preferences are first obtained.
     */
    public fun failNextWrite(name: String) {
        synchronized(lock) {
            entryFor(name).failNextWrite = true
        }
    }

    /** lock の下で呼ぶ。 */
    private fun entryFor(name: String): Entry {
        require(name.isNotEmpty()) { "name must not be empty" }
        require(!name.contains('/')) { "name must not contain '/': $name" }
        return entries.getOrPut(name) { Entry() }
    }

    @OptIn(DaybookInternalApi::class)
    private fun createPreferences(entry: Entry): SharedPreferences =
        DaybookTestBridge.createInMemorySharedPreferences(
            delivery = { it.run() },
            writeObserver = { clearRequested, changes ->
                synchronized(lock) {
                    if (entry.failNextWrite) {
                        entry.failNextWrite = false
                        throw IOException("write failure injected by TestDaybook.failNextWrite")
                    }
                    entry.commits += RecordedCommit(clearRequested, LinkedHashMap(changes))
                }
            },
        )
}

/**
 * One effective write batch, as it would have hit the journal in production.
 *
 * This is daybook's unit of atomicity: everything in one commit becomes visible together
 * or not at all. Asserting on the recorded commits therefore verifies not just what the
 * code under test wrote, but whether related keys were written atomically in one edit.
 */
public class RecordedCommit(
    /** Whether the edit requested `clear()`. Always written, even when the state was already empty. */
    public val clearRequested: Boolean,
    /** Effective changes in edit order. A `null` value is a remove. */
    public val changes: Map<String, Any?>,
) {
    override fun toString(): String = "RecordedCommit(clearRequested=$clearRequested, changes=$changes)"
}
