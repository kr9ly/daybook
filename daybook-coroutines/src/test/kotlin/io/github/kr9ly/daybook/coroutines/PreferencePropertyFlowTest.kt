package io.github.kr9ly.daybook.coroutines

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kr9ly.daybook.getDaybookSharedPreferences
import io.github.kr9ly.daybook.int
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [PreferenceProperty.asFlow] のテスト。
 * リスナー配送はメインスレッド、Robolectric のテストスレッド = メインスレッドなので
 * UnconfinedTestDispatcher と組み合わせると発火が同期的に観測できる。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PreferencePropertyFlowTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // プロセス内キャッシュ（internal、このモジュールからはリセットできない）が
    // テストをまたいで生きるため、ストア名はテストごとに一意にする

    @Test
    fun emitsInitialValueThenChanges() = runTest(UnconfinedTestDispatcher()) {
        val prefs = context.getDaybookSharedPreferences("flow-initial")
        val count = prefs.int("count", default = 0)
        val received = mutableListOf<Int>()
        val job = launch { count.asFlow().toList(received) }

        assertEquals(listOf(0), received) // collect 時に現在値（不在ならデフォルト）
        count.set(1)
        assertEquals(listOf(0, 1), received)
        count.set(2)
        assertEquals(listOf(0, 1, 2), received)
        job.cancel()
    }

    @Test
    fun ignoresChangesToOtherKeys() = runTest(UnconfinedTestDispatcher()) {
        val prefs = context.getDaybookSharedPreferences("flow-other-keys")
        val count = prefs.int("count", default = 0)
        val received = mutableListOf<Int>()
        val job = launch { count.asFlow().toList(received) }

        prefs.edit().putInt("other", 9).commit()
        assertEquals(listOf(0), received)
        job.cancel()
    }

    @Test
    fun clearReReadsAsDefault() = runTest(UnconfinedTestDispatcher()) {
        val prefs = context.getDaybookSharedPreferences("flow-clear")
        val count = prefs.int("count", default = 0)
        count.set(5)
        val received = mutableListOf<Int>()
        val job = launch { count.asFlow().toList(received) }

        assertEquals(listOf(5), received)
        prefs.edit().clear().commit() // key = null の通知 → 再読
        assertEquals(listOf(5, 0), received)
        job.cancel()
    }

    @Test
    fun equalConsecutiveValues_areDropped() = runTest(UnconfinedTestDispatcher()) {
        val prefs = context.getDaybookSharedPreferences("flow-distinct")
        val count = prefs.int("count", default = 0)
        val received = mutableListOf<Int>()
        val job = launch { count.asFlow().toList(received) }

        // 値がデフォルトのまま clear → 再読値は変わらないので発火しない（distinctUntilChanged）
        prefs.edit().clear().commit()
        assertEquals(listOf(0), received)
        job.cancel()
    }

    @Test
    fun cancellation_unregistersListener() = runTest(UnconfinedTestDispatcher()) {
        val prefs = context.getDaybookSharedPreferences("flow-cancel")
        val count = prefs.int("count", default = 0)
        val received = mutableListOf<Int>()
        val job = launch { count.asFlow().toList(received) }
        job.cancel()

        count.set(1)
        assertEquals(listOf(0), received)
    }

    @Test
    fun worksAgainstFrameworkSharedPreferences() = runTest(UnconfinedTestDispatcher()) {
        // daybook 非依存: framework 実装の上でも同じに動く
        val prefs = context.getSharedPreferences("framework", Context.MODE_PRIVATE)
        val count = prefs.int("count", default = 0)
        val received = mutableListOf<Int>()
        val job = launch { count.asFlow().toList(received) }

        assertEquals(listOf(0), received)
        count.set(1)
        assertEquals(listOf(0, 1), received)
        job.cancel()
    }
}
