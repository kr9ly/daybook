package io.github.kr9ly.daybook.adversarial

import android.content.SharedPreferences
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.kr9ly.daybook.DaybookPreferencesCache
import io.github.kr9ly.daybook.getDaybookSharedPreferences
import io.github.kr9ly.daybook.int
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 敵対的テスト: getDaybookSharedPreferences が返す SharedPreferences に対する
 * スレッドを絡めた攻撃（並行性・再入）。
 *
 * 情報源はコンテキストファイル（README / DESIGN.md / 公開 API リファレンス）のみ。
 * 実装は一切参照していない。
 */
@RunWith(RobolectricTestRunner::class)
class AdversarialConcurrencyTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun tearDown() {
        DaybookPreferencesCache.resetForTesting()
    }

    private fun uniqueName(tag: String) = "adv_${tag}_${System.nanoTime()}"

    // ------------------------------------------------------------------
    // 1. インスタンスキャッシュの同一性
    // ------------------------------------------------------------------

    /**
     * 検証対象: 公開APIリファレンス「同名は常に同一インスタンスを返す
     * （フレームワークと同じく）」。複数スレッドが同時に初回生成を叩いても
     * 二重生成（=キャッシュ競合で別インスタンスが生き残る）が起きないか。
     */
    @Test(timeout = 20_000)
    fun concurrentGet_firstCreation_returnsSameInstanceAcrossAllThreads() {
        val name = uniqueName("same-instance")
        val threadCount = 32
        val barrier = CyclicBarrier(threadCount)
        val results = ConcurrentHashMap<Int, SharedPreferences>()
        val latch = CountDownLatch(threadCount)
        val pool = Executors.newFixedThreadPool(threadCount)
        try {
            for (i in 0 until threadCount) {
                pool.submit {
                    try {
                        barrier.await()
                        results[i] = context.getDaybookSharedPreferences(name)
                    } finally {
                        latch.countDown()
                    }
                }
            }
            assertTrue("threads did not finish in time", latch.await(15, TimeUnit.SECONDS))
        } finally {
            pool.shutdown()
        }

        assertEquals(threadCount, results.size)
        val distinctInstances = results.values.map { System.identityHashCode(it) }.distinct()
        assertEquals(
            "expected exactly one instance to survive concurrent first creation, got $distinctInstances",
            1,
            distinctInstances.size,
        )
    }

    /**
     * 検証対象: 公開APIリファレンス「同名を異なる multiProcess で再取得したときは
     * 黙殺せず IllegalArgumentException」。同時初回生成で multiProcess フラグが
     * 割れた場合でも、この契約が黙殺（両方成功して別インスタンスが共存）されないか。
     */
    @Test(timeout = 20_000)
    fun concurrentFirstCreation_withConflictingMultiProcessFlags_neverSilentlyCoexist() {
        val name = uniqueName("mp-flag-race")
        val threadCount = 16
        val barrier = CyclicBarrier(threadCount)
        val latch = CountDownLatch(threadCount)
        val pool = Executors.newFixedThreadPool(threadCount)
        val successes = ConcurrentHashMap<Int, SharedPreferences>()
        val failures = ConcurrentHashMap<Int, Throwable>()
        try {
            for (i in 0 until threadCount) {
                pool.submit {
                    try {
                        barrier.await()
                        val mp = i % 2 == 0
                        try {
                            successes[i] = context.getDaybookSharedPreferences(name, multiProcess = mp)
                        } catch (t: Throwable) {
                            failures[i] = t
                        }
                    } finally {
                        latch.countDown()
                    }
                }
            }
            assertTrue("threads did not finish in time", latch.await(15, TimeUnit.SECONDS))
        } finally {
            pool.shutdown()
        }

        // 黙殺されていれば mp=true 側と mp=false 側の両方が「成功」し、
        // 別インスタンスが同時に生き残ってしまう。それは絶対に起きてはいけない。
        val distinctInstances = successes.values.map { System.identityHashCode(it) }.distinct()
        assertTrue(
            "expected at most one instance to win the race, got ${distinctInstances.size} distinct instances",
            distinctInstances.size <= 1,
        )
        failures.values.forEach { t ->
            assertTrue(
                "losing threads must fail with IllegalArgumentException per contract, got $t",
                t is IllegalArgumentException,
            )
        }
        assertTrue("at least one thread should have succeeded", successes.isNotEmpty())
    }

    // ------------------------------------------------------------------
    // 2. Editor / apply / commit の並行性
    // ------------------------------------------------------------------

    /**
     * 検証対象: 公開APIリファレンス「Editor commits are atomic on disk」。
     * 多数スレッドが異なるキーへ同時に apply() しても、書き込みが失われない
     * （プロセス内書き込みロックでの直列化が機能している）ことを検証。
     */
    @Test(timeout = 20_000)
    fun concurrentApply_distinctKeysFromManyThreads_noLostWrites() {
        val name = uniqueName("apply-no-loss")
        val prefs = context.getDaybookSharedPreferences(name)
        val threadCount = 50
        val latch = CountDownLatch(threadCount)
        val pool = Executors.newFixedThreadPool(threadCount)
        try {
            for (i in 0 until threadCount) {
                pool.submit {
                    try {
                        prefs.edit().putInt("key_$i", i).apply()
                    } finally {
                        latch.countDown()
                    }
                }
            }
            assertTrue("threads did not finish in time", latch.await(15, TimeUnit.SECONDS))
        } finally {
            pool.shutdown()
        }

        for (i in 0 until threadCount) {
            assertEquals("key_$i lost under concurrent apply()", i, prefs.getInt("key_$i", -1))
        }
    }

    /**
     * 検証対象: 公開APIリファレンス「commit/apply は 1 バッチ = 1 ジャーナルレコード」
     * かつコアの書き込みロックで直列化。同一キーへの高頻度な同時 commit() で
     * 例外・破損（値が一度も書かれていない/複数値が混在した文字列になる等）が
     * 起きないか。
     */
    @Test(timeout = 20_000)
    fun concurrentCommit_sameKey_neverCorruptsAndAlwaysReportsSuccess() {
        val name = uniqueName("same-key-commit")
        val prefs = context.getDaybookSharedPreferences(name)
        val threadCount = 30
        val latch = CountDownLatch(threadCount)
        val pool = Executors.newFixedThreadPool(threadCount)
        val committedOk = AtomicInteger(0)
        val errors = ConcurrentHashMap<Int, Throwable>()
        try {
            for (i in 0 until threadCount) {
                pool.submit {
                    try {
                        val ok = prefs.edit().putString("shared_key", "value_$i").commit()
                        if (ok) committedOk.incrementAndGet()
                    } catch (t: Throwable) {
                        errors[i] = t
                    } finally {
                        latch.countDown()
                    }
                }
            }
            assertTrue("threads did not finish in time", latch.await(15, TimeUnit.SECONDS))
        } finally {
            pool.shutdown()
        }

        assertTrue("unexpected exceptions during concurrent commit: $errors", errors.isEmpty())
        assertEquals("every commit() should report success per doc (no partial batch)", threadCount, committedOk.get())
        val finalValue = prefs.getString("shared_key", null)
        assertNotNull("final value must not be null/corrupted", finalValue)
        assertTrue(
            "final value must be exactly one of the written values, got '$finalValue'",
            Regex("^value_\\d+$").matches(finalValue!!),
        )
    }

    /**
     * 検証対象: README「意図的な非互換: apply は非同期でなく同期書き込み」。
     * apply() の呼び出しが返った時点で、別スレッドから読んでも即座に見える
     * （フレームワークのような QueuedWork 経由の遅延反映ではない）ことを検証。
     */
    @Test(timeout = 20_000)
    fun applyIsSynchronous_writeImmediatelyVisibleToOtherThread() {
        val name = uniqueName("apply-sync")
        val prefs = context.getDaybookSharedPreferences(name)
        repeat(200) { i ->
            prefs.edit().putInt("v", i).apply()
            val seen = AtomicInteger(Int.MIN_VALUE)
            val t = Thread { seen.set(prefs.getInt("v", Int.MIN_VALUE)) }
            t.start()
            t.join(2_000)
            assertEquals("write at iteration $i not immediately visible to a fresh thread", i, seen.get())
        }
    }

    /**
     * 検証対象: README「読み出しは常に同期・メモリアクセスのみ」。
     * 大量の同時書き込みの最中に getAll() / getInt() を回しても、
     * 例外（ConcurrentModificationException 等）や崩壊が起きないか。
     */
    @Test(timeout = 20_000)
    fun readsDuringHeavyConcurrentWrites_neverThrow() {
        val name = uniqueName("read-write-race")
        val prefs = context.getDaybookSharedPreferences(name)
        val stop = AtomicBoolean(false)
        val errors = ConcurrentHashMap<Int, Throwable>()

        val writer = Thread {
            var i = 0
            try {
                while (!stop.get()) {
                    prefs.edit().putInt("counter", i).apply()
                    i++
                }
            } catch (t: Throwable) {
                errors[-1] = t
            }
        }
        val readers = (0 until 8).map { idx ->
            Thread {
                try {
                    val deadline = System.currentTimeMillis() + 3_000
                    while (System.currentTimeMillis() < deadline) {
                        prefs.getInt("counter", -999)
                        prefs.all
                    }
                } catch (t: Throwable) {
                    errors[idx] = t
                }
            }
        }

        writer.start()
        readers.forEach { it.start() }
        readers.forEach { it.join(8_000) }
        stop.set(true)
        writer.join(5_000)

        assertTrue("reader/writer threads threw: $errors", errors.isEmpty())
    }

    // ------------------------------------------------------------------
    // 3. リスナーの再入
    // ------------------------------------------------------------------

    /**
     * 検証対象: DESIGN.md「リスナー内で daybook を再操作してもデッドロックしない
     * ことを保証する（配送はロック外で行う）」。互換リスナー
     * (OnSharedPreferenceChangeListener) のコールバック内から同じ prefs に
     * 対して commit() する再入がデッドロック・例外なく完了するか。
     */
    @Test(timeout = 20_000)
    fun listenerCallback_reentrantCommit_doesNotDeadlockOrThrow() {
        val name = uniqueName("reentrant-write")
        val prefs = context.getDaybookSharedPreferences(name)
        val reentered = AtomicBoolean(false)
        val reentrantCommitOk = AtomicBoolean(false)
        val exceptionRef = AtomicReference<Throwable?>(null)

        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            if (key == "trigger" && reentered.compareAndSet(false, true)) {
                try {
                    reentrantCommitOk.set(sp.edit().putString("response", "handled").commit())
                } catch (t: Throwable) {
                    exceptionRef.set(t)
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        prefs.edit().putString("trigger", "go").apply()
        shadowOf(Looper.getMainLooper()).idle()

        exceptionRef.get()?.let { throw AssertionError("reentrant commit from listener threw", it) }
        assertTrue("listener was never invoked for 'trigger'", reentered.get())
        assertTrue("reentrant commit() inside listener callback did not report success", reentrantCommitOk.get())
        assertEquals("handled", prefs.getString("response", null))
    }

    /**
     * 検証対象: DESIGN.md 互換リスナー節「WeakHashMap 保持」+ 変更通知は
     * ロック外配送。ディスパッチ中にリスナーが自分自身を unregister する
     * （AOSP でも既知の危険地帯: 反復中のコレクション変更）ケースで
     * ConcurrentModificationException 等が飛ばないか。
     */
    @Test(timeout = 20_000)
    fun listenerUnregistersItself_duringDispatch_doesNotThrow() {
        val name = uniqueName("listener-unregister")
        val prefs = context.getDaybookSharedPreferences(name)
        val exceptionRef = AtomicReference<Throwable?>(null)
        val selfInvocations = AtomicInteger(0)
        val otherInvocations = AtomicInteger(0)

        lateinit var self: SharedPreferences.OnSharedPreferenceChangeListener
        self = SharedPreferences.OnSharedPreferenceChangeListener { sp, _ ->
            selfInvocations.incrementAndGet()
            try {
                sp.unregisterOnSharedPreferenceChangeListener(self)
            } catch (t: Throwable) {
                exceptionRef.set(t)
            }
        }
        val other = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            otherInvocations.incrementAndGet()
        }
        prefs.registerOnSharedPreferenceChangeListener(self)
        prefs.registerOnSharedPreferenceChangeListener(other)

        prefs.edit().putInt("k", 1).apply()
        shadowOf(Looper.getMainLooper()).idle()

        exceptionRef.get()?.let { throw AssertionError("unregister-during-dispatch threw", it) }
        assertEquals(1, selfInvocations.get())
        assertEquals(1, otherInvocations.get())

        // self should no longer receive notifications after unregistering itself.
        prefs.edit().putInt("k", 2).apply()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("self listener kept receiving events after unregistering itself", 1, selfInvocations.get())
        assertEquals(2, otherInvocations.get())
    }

    /**
     * 検証対象: 上と対の攻撃 — ディスパッチ中に新しいリスナーを register する
     * ケース（反復中のコレクション追加）で例外が飛ばないか。
     */
    @Test(timeout = 20_000)
    fun listenerRegistersNewListener_duringDispatch_doesNotThrow() {
        val name = uniqueName("listener-register-during-dispatch")
        val prefs = context.getDaybookSharedPreferences(name)
        val exceptionRef = AtomicReference<Throwable?>(null)
        val secondCalled = AtomicBoolean(false)

        val second = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> secondCalled.set(true) }
        val first = SharedPreferences.OnSharedPreferenceChangeListener { sp, _ ->
            try {
                sp.registerOnSharedPreferenceChangeListener(second)
            } catch (t: Throwable) {
                exceptionRef.set(t)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(first)
        prefs.edit().putInt("k", 1).apply()
        shadowOf(Looper.getMainLooper()).idle()

        exceptionRef.get()?.let { throw AssertionError("register-during-dispatch threw", it) }

        prefs.edit().putInt("k", 2).apply()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("newly registered listener never fired on a subsequent edit", secondCalled.get())
    }

    // ------------------------------------------------------------------
    // 4. 型安全層（PreferenceProperty）の並行性
    // ------------------------------------------------------------------

    /**
     * 検証対象: 公開APIリファレンス「PreferenceProperty.set は apply() で書く」。
     * SharedPreferences 経由と同じ直列化保証が型安全層越しにも効くかを、
     * 異なるキーへの多数同時 set() で確認する。
     */
    @Test(timeout = 20_000)
    fun preferenceProperty_concurrentSet_distinctKeys_noLostWrites() {
        val name = uniqueName("typed-concurrent")
        val prefs = context.getDaybookSharedPreferences(name)
        val threadCount = 40
        val props = (0 until threadCount).map { prefs.int("k_$it", default = -1) }
        val latch = CountDownLatch(threadCount)
        val pool = Executors.newFixedThreadPool(threadCount)
        try {
            for (i in 0 until threadCount) {
                pool.submit {
                    try {
                        props[i].set(i * 10)
                    } finally {
                        latch.countDown()
                    }
                }
            }
            assertTrue("threads did not finish in time", latch.await(15, TimeUnit.SECONDS))
        } finally {
            pool.shutdown()
        }

        for (i in 0 until threadCount) {
            assertEquals("PreferenceProperty key_$i lost under concurrent set()", i * 10, props[i].get())
        }
    }
}
