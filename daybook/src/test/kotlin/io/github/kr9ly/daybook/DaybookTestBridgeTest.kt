package io.github.kr9ly.daybook

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DaybookTestBridge] のテスト。
 * KvOperation から公開シグネチャ（clearRequested + changes）への変換と、
 * delivery / writeObserver の配線を SharedPreferences 契約の側から検証する。
 * Looper に触れない配送を注入するため Robolectric は不要（素の JVM で走る）。
 */
@OptIn(DaybookInternalApi::class)
class DaybookTestBridgeTest {

    private class Observed(val clearRequested: Boolean, val changes: Map<String, Any?>)

    private val observed = mutableListOf<Observed>()
    private var observer: (Boolean, Map<String, Any?>) -> Unit = { clear, changes ->
        observed += Observed(clear, changes)
    }

    private val prefs = DaybookTestBridge.createInMemorySharedPreferences(
        delivery = { it.run() },
        writeObserver = { clear, changes -> observer(clear, changes) },
    )

    @Test
    fun singlePut_observedAsOneChange() {
        prefs.edit().putString("key", "value").commit()
        val single = observed.single()
        assertFalse(single.clearRequested)
        assertEquals(mapOf<String, Any?>("key" to "value"), single.changes)
    }

    @Test
    fun singleRemove_observedAsNullValue() {
        prefs.edit().putString("key", "value").commit()
        prefs.edit().remove("key").commit()
        assertEquals(mapOf<String, Any?>("key" to null), observed.last().changes)
    }

    @Test
    fun clearOnly_observedAsClearRequestedWithoutChanges() {
        prefs.edit().clear().commit()
        val single = observed.single()
        assertTrue(single.clearRequested)
        assertEquals(emptyMap<String, Any?>(), single.changes)
    }

    @Test
    fun batch_observedInEditOrder() {
        prefs.edit().clear().putInt("a", 1).putInt("b", 2).remove("absent-put").putString("c", "v")
            .commit()
        val single = observed.single()
        assertTrue(single.clearRequested)
        // 不在キーの remove は実効変更でないため現れない
        assertEquals(mapOf<String, Any?>("a" to 1, "b" to 2, "c" to "v"), single.changes)
        assertEquals(listOf("a", "b", "c"), single.changes.keys.toList())
    }

    @Test
    fun delivery_receivesListenerNotifications() {
        val delivered = mutableListOf<String?>()
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            delivered += key
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)

        prefs.edit().putInt("a", 1).commit()
        assertEquals(listOf<String?>("a"), delivered)
    }

    @Test
    fun observerThrowingIOException_failsCommitAndLeavesStateUntouched() {
        observer = { _, _ -> throw IOException("injected") }
        assertFalse(prefs.edit().putInt("a", 1).commit())
        assertFalse(prefs.contains("a"))
    }
}
