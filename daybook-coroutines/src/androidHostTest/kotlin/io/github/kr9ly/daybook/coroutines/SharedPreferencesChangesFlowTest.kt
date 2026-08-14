package io.github.kr9ly.daybook.coroutines

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kr9ly.daybook.getDaybookSharedPreferences
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
 * [SharedPreferences.changesAsFlow] のテスト。
 * リスナー配送はメインスレッド、Robolectric のテストスレッド = メインスレッドなので
 * UnconfinedTestDispatcher と組み合わせると発火が同期的に観測できる。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SharedPreferencesChangesFlowTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // プロセス内キャッシュ（internal、このモジュールからはリセットできない）が
    // テストをまたいで生きるため、ストア名はテストごとに一意にする

    @Test
    fun startsSilent_thenEmitsChangedKeys() = runTest(UnconfinedTestDispatcher()) {
        val prefs = context.getDaybookSharedPreferences("changes-basic")
        val received = mutableListOf<String?>()
        val job = launch { prefs.changesAsFlow().toList(received) }

        assertEquals(emptyList<String?>(), received) // 初期発火なし
        prefs.edit().putInt("a", 1).commit()
        assertEquals(listOf<String?>("a"), received)
        prefs.edit().putInt("b", 2).commit()
        assertEquals(listOf<String?>("a", "b"), received)
        job.cancel()
    }

    @Test
    fun clearEmitsNull() = runTest(UnconfinedTestDispatcher()) {
        val prefs = context.getDaybookSharedPreferences("changes-clear")
        prefs.edit().putInt("a", 1).commit()
        val received = mutableListOf<String?>()
        val job = launch { prefs.changesAsFlow().toList(received) }

        prefs.edit().clear().commit()
        assertEquals(listOf<String?>(null), received)
        job.cancel()
    }

    @Test
    fun multipleKeysInOneCommit_arriveInReverseOrder() = runTest(UnconfinedTestDispatcher()) {
        // 変更キーの逆順通知は AOSP 準拠の互換層契約
        val prefs = context.getDaybookSharedPreferences("changes-batch")
        val received = mutableListOf<String?>()
        val job = launch { prefs.changesAsFlow().toList(received) }

        prefs.edit().putInt("a", 1).putInt("b", 2).commit()
        assertEquals(listOf<String?>("b", "a"), received)
        job.cancel()
    }

    @Test
    fun sameKeyChangedTwice_emitsTwice() = runTest(UnconfinedTestDispatcher()) {
        // イベント流: distinctUntilChanged しない
        val prefs = context.getDaybookSharedPreferences("changes-repeat")
        val received = mutableListOf<String?>()
        val job = launch { prefs.changesAsFlow().toList(received) }

        prefs.edit().putInt("a", 1).commit()
        prefs.edit().putInt("a", 2).commit()
        assertEquals(listOf<String?>("a", "a"), received)
        job.cancel()
    }

    @Test
    fun sameValueWrite_doesNotEmit() = runTest(UnconfinedTestDispatcher()) {
        // 同値 put 非通知は互換層契約。Flow はそれを素通しする
        val prefs = context.getDaybookSharedPreferences("changes-same-value")
        prefs.edit().putInt("a", 1).commit()
        val received = mutableListOf<String?>()
        val job = launch { prefs.changesAsFlow().toList(received) }

        prefs.edit().putInt("a", 1).commit()
        assertEquals(emptyList<String?>(), received)
        job.cancel()
    }

    @Test
    fun cancellation_unregistersListener() = runTest(UnconfinedTestDispatcher()) {
        val prefs = context.getDaybookSharedPreferences("changes-cancel")
        val received = mutableListOf<String?>()
        val job = launch { prefs.changesAsFlow().toList(received) }
        job.cancel()

        prefs.edit().putInt("a", 1).commit()
        assertEquals(emptyList<String?>(), received)
    }

    @Test
    fun worksAgainstFrameworkSharedPreferences() = runTest(UnconfinedTestDispatcher()) {
        // daybook 非依存: framework 実装の上でも同じに動く
        val prefs = context.getSharedPreferences("changes-framework", Context.MODE_PRIVATE)
        val received = mutableListOf<String?>()
        val job = launch { prefs.changesAsFlow().toList(received) }

        prefs.edit().putInt("a", 1).commit()
        assertEquals(listOf<String?>("a"), received)
        job.cancel()
    }
}
