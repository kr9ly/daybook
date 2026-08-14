package io.github.kr9ly.daybook.test

import io.github.kr9ly.daybook.boolean
import io.github.kr9ly.daybook.coroutines.asFlow
import io.github.kr9ly.daybook.coroutines.changesAsFlow
import io.github.kr9ly.daybook.int
import io.github.kr9ly.daybook.string
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 型安全 API と Flow アダプタが TestDaybook の上で無変更で動くことのテスト。
 * どちらも SharedPreferences インターフェースのみ依存なので、アプリは本番コードを
 * そのまま素の JVM でテストできる — このモジュールの提供価値そのものを検証する。
 * 同期配送のため、UnconfinedTestDispatcher と組み合わせると発火が同期的に観測できる。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestDaybookStackTest {

    private val daybook = TestDaybook()

    @Test
    fun typedProperties_workOnPlainJvm() {
        val prefs = daybook.getSharedPreferences("settings")
        val settings = object {
            var darkMode by prefs.boolean("dark_mode", default = false)
            var nickname by prefs.string("nickname")
        }

        settings.darkMode = true
        settings.nickname = "alice"
        assertEquals(true, prefs.getBoolean("dark_mode", false))
        assertEquals("alice", settings.nickname)

        settings.nickname = null // null 代入 = キー削除
        assertEquals(false, prefs.contains("nickname"))
    }

    @Test
    fun mappedProperty_withCatch_recoversOnPlainJvm() {
        val prefs = daybook.getSharedPreferences("settings")
        val level = prefs.string("level", default = "1")
            .map(decode = String::toInt, encode = Int::toString)
            .catch { -1 }

        level.set(5)
        assertEquals(5, level.get())

        prefs.edit().putString("level", "not-a-number").commit()
        assertEquals(-1, level.get())
    }

    @Test
    fun propertyAsFlow_emitsInitialValueThenChanges() = runTest(UnconfinedTestDispatcher()) {
        val prefs = daybook.getSharedPreferences("settings")
        val count = prefs.int("count", default = 0)
        val received = mutableListOf<Int>()
        val job = launch { count.asFlow().toList(received) }

        assertEquals(listOf(0), received)
        count.set(1)
        assertEquals(listOf(0, 1), received)
        job.cancel()
    }

    @Test
    fun changesAsFlow_emitsChangedKeys() = runTest(UnconfinedTestDispatcher()) {
        val prefs = daybook.getSharedPreferences("settings")
        val received = mutableListOf<String?>()
        val job = launch { prefs.changesAsFlow().toList(received) }

        prefs.edit().putInt("a", 1).commit()
        prefs.edit().clear().commit()
        assertEquals(listOf<String?>("a", null), received)
        job.cancel()
    }
}
