package io.github.kr9ly.daybook

import io.github.kr9ly.daybook.kv.KvStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException

/**
 * [DaybookTestBridge] のテスト。
 * 渡したストアの上で顔が動くこと・delivery の配線・ストア由来の書き込み失敗の現れ方を
 * SharedPreferences 契約の側から検証する。書き込み観測の変換（RecordedCommit）は
 * daybook-test 側の責務になったため、ここでは扱わない。
 * Looper に触れない配送を注入するため Robolectric は不要（素の JVM で走る）。
 */
@OptIn(DaybookInternalApi::class)
class DaybookTestBridgeTest {

    private var failWrites = false
    private val store = KvStore.openInMemory(
        delivery = { it() },
        writeHook = { if (failWrites) throw IOException("injected") },
    )
    private val prefs = DaybookTestBridge.wrapAsSharedPreferences(store) { it.run() }

    @Test
    fun prefsFace_operatesOnGivenStore() {
        prefs.edit().putString("key", "value").commit()
        assertEquals("value", store.get("key"))
        store.put("other", 1)
        assertEquals(1, prefs.getInt("other", 0))
    }

    @Test
    fun delivery_receivesListenerNotificationsSynchronously() {
        val delivered = mutableListOf<String?>()
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            delivered += key
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)

        prefs.edit().putInt("a", 1).commit()
        assertEquals(listOf<String?>("a"), delivered)
    }

    @Test
    fun storeWriteFailure_failsCommitAndLeavesStateUntouched() {
        failWrites = true
        assertFalse(prefs.edit().putInt("a", 1).commit())
        assertFalse(prefs.contains("a"))
        failWrites = false
        // 以後の書き込みは成功する
        prefs.edit().putInt("a", 2).commit()
        assertEquals(2, prefs.getInt("a", 0))
    }
}
