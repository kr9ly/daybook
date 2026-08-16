package io.github.kr9ly.daybook.kv

import io.github.kr9ly.daybook.concurrent.Lock
import io.github.kr9ly.daybook.concurrent.waitUntil
import io.github.kr9ly.daybook.concurrent.withLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 敵対的テスト — レーン 1: 共通 API 契約（Daybook / DaybookEditor / DaybookProperty /
 * DaybookChangeListener / keys）。
 *
 * 2.0 リリース前の敵対的テストで書かれ、裁定済みの契約をアサートする形に転換して資産化した
 * （裁定 2026-08-16: リスナー登録の Set 意味論・keys の読み取り専用契約）。
 */
class AdversarialCommonApiTest {

    private object PlainSchema : DaybookSchema("test")

    private fun open(): Daybook = KvStore.openInMemory().asDaybook(PlainSchema)

    private class Events(private val expectedCount: Int) {
        private val lock = Lock()
        private val list = mutableListOf<Pair<String, Any?>>()

        val listener = DaybookChangeListener { key, newValue ->
            lock.withLock { list.add(key to newValue) }
        }

        fun await(): List<Pair<String, Any?>> {
            assertTrue(waitUntil { lock.withLock { list.size } >= expectedCount })
            return lock.withLock { list.toList() }
        }

        fun snapshot(): List<Pair<String, Any?>> = lock.withLock { list.toList() }
    }

    // --- 攻撃 1: edit のアトミック性 — ブロック内でユーザー例外が投げられた場合 ---
    //
    // 契約(common-api.md): 「edit ブロック内の操作は呼び出し順に適用される。クラッシュ時は
    // 全操作が残るか全操作が消えるかの二択」「ブロック完了時に1ジャーナルレコードとして書かれる」
    // (Daybook.kt KDoc): 「ブロックに渡る DaybookEditor はブロック内でだけ有効」
    //
    // 「ブロック完了時に書かれる」は非完了（ユーザー例外での早期脱出）なら何も書かれないことを
    // 示唆する。ここではプロセスクラッシュではなく、ブロック内で通常の例外を投げるケースを攻撃する。
    @Test
    fun edit_userExceptionMidBlock_discardsAllOperations() {
        val daybook = open()
        daybook.edit { putString("pre", "existing") }

        class Boom : RuntimeException("boom")
        assertFailsWith<Boom> {
            daybook.edit {
                putString("a", "1")
                putInt("b", 2)
                throw Boom()
            }
        }

        // 契約の素直な読み: ブロックが完了しなかったので何も書かれていないはず。
        assertFalse(daybook.contains("a"))
        assertFalse(daybook.contains("b"))
        assertEquals("existing", daybook.getString("pre", null))
    }

    // 上と対をなす攻撃: 例外を投げた edit の "前" の操作は生き残るか（部分適用の疑い）。
    @Test
    fun edit_userExceptionMidBlock_earlierOperationsInSameBlockNotPartiallyVisible() {
        val daybook = open()
        class Boom : RuntimeException("boom")
        assertFailsWith<Boom> {
            daybook.edit {
                putString("first", "written-before-throw")
                throw Boom()
            }
        }
        assertFalse(
            daybook.contains("first"),
            "edit ブロックの先頭操作だけが部分適用されていないこと",
        )
    }

    // --- 攻撃 2: 型検査のタイミング — 境界値で IllegalArgumentException を誘発する ---
    //
    // 契約(Daybook.kt KDoc): 「値の型検査はブロック完了時に行われ、違反は IllegalArgumentException」
    // 「型」は7種の格納型で固定だが、どの値が「違反」なのかはドキュメント上どこにも定義がない。
    // NaN・Infinity・空文字列・空集合・巨大値で誘発できるかを総当たりする。
    @Test
    fun edit_boundaryValues_doNotTriggerDocumentedTypeViolation() {
        val daybook = open()
        // すべて「型としては正しい」境界値。IllegalArgumentException が飛ぶなら
        // ドキュメントに書かれていない暗黙の値域制約が存在するという発見になる。
        daybook.edit {
            putDouble("nan", Double.NaN)
            putDouble("posInf", Double.POSITIVE_INFINITY)
            putDouble("negInf", Double.NEGATIVE_INFINITY)
            putFloat("fNan", Float.NaN)
            putString("empty", "")
            putStringSet("emptySet", emptySet())
            putInt("minInt", Int.MIN_VALUE)
            putLong("maxLong", Long.MAX_VALUE)
        }
        assertTrue(daybook.getDouble("nan", 0.0).isNaN())
        assertEquals(Double.POSITIVE_INFINITY, daybook.getDouble("posInf", 0.0))
        assertEquals("", daybook.getString("empty", null))
        assertEquals(emptySet(), daybook.getStringSet("emptySet", null))
        assertEquals(Int.MIN_VALUE, daybook.getInt("minInt", 0))
        assertEquals(Long.MAX_VALUE, daybook.getLong("maxLong", 0L))
    }

    // 同一キーへ型の異なる put を別バッチで繰り返す（型は「格納値」に紐づき、キーには紐づかない
    // というのが common-api.md の読み — スキーマは「型付き API から見える面を固定するだけ」）。
    @Test
    fun edit_keyCanChangeStoredTypeAcrossBatches() {
        val daybook = open()
        daybook.edit { putBoolean("k", true) }
        assertTrue(daybook.getBoolean("k", false))
        daybook.edit { putString("k", "now a string") }
        assertEquals("now a string", daybook.getString("k", null))
        assertFailsWith<ClassCastException> { daybook.getBoolean("k", false) }
    }

    // --- 攻撃 3: 操作ベース通知 — 同値 put / 不在キーの remove も通知されるか ---
    //
    // 契約(common-api.md): 「通知は操作ベース: 同じ値の put や不在キーの remove もジャーナルに
    // 書かれ、そのまま通知される」
    @Test
    fun listener_sameValuePut_stillNotifies() {
        val daybook = open()
        val events = Events(expectedCount = 2)
        daybook.addChangeListener(events.listener)
        daybook.edit { putInt("k", 1) }
        daybook.edit { putInt("k", 1) } // 同値
        assertEquals(
            listOf<Pair<String, Any?>>("k" to 1, "k" to 1),
            events.await(),
        )
    }

    @Test
    fun listener_removeOfAbsentKey_stillNotifies() {
        val daybook = open()
        val events = Events(expectedCount = 1)
        daybook.addChangeListener(events.listener)
        daybook.edit { remove("never-existed") } // 存在しないキーの remove
        assertEquals(
            listOf<Pair<String, Any?>>("never-existed" to null),
            events.await(),
        )
    }

    // clear は「消えた各キーへの (key, null) として届く」— 何もない状態での clear は
    // 「消えたキー」が 0 件なので通知が 0 件になるはず（何も操作しないブロックとは違うが、
    // 実質的に効果のない clear がどう扱われるかは契約に明記がない = 未定義挙動の疑い）。
    @Test
    fun listener_clearOnEmptyStore_notificationCountIsUndocumented() {
        val daybook = open()
        val events = Events(expectedCount = 1)
        daybook.addChangeListener(events.listener)
        // clear のあとに 1 件別の put を積んで、少なくとも 1 件は届くようにしてから
        // clear 由来の通知が実際に 0 件かどうかを観察する。
        daybook.edit {
            clear()
            putInt("marker", 1)
        }
        val received = events.await()
        // marker は必ず含まれる。clear 由来の (key,null) が 0 件であることを確認する。
        assertEquals(listOf<Pair<String, Any?>>("marker" to 1), received)
    }

    // --- 攻撃 4: keys のスナップショット意味論 ---
    //
    // 契約(Daybook.kt KDoc): 「取得時点の状態で固定され、以後の書き込みの影響を受けない」
    // Set<String> という戻り値の型だけでは、呼び出し側による書き換えからの保護までは保証されない
    // （不変コレクションかどうかは契約に明記されていない）。
    @Test
    fun keys_snapshotIsNotMutableFromCallerSide() {
        val daybook = open()
        daybook.edit { putInt("a", 1) }
        val snapshot = daybook.keys
        // 契約: 返るセットは読み取り専用として扱う。変更操作は例外になることがあり、
        // 例外にならないプラットフォームでもストアには反映されない。
        if (snapshot is MutableSet<*>) {
            @Suppress("UNCHECKED_CAST")
            val mutable = snapshot as MutableSet<String>
            try {
                mutable.add("injected")
            } catch (_: UnsupportedOperationException) {
                // 不変コレクション実装（JVM 等）はここに来る。契約どおり
            }
            // 変更が例外にならなかった場合でも、以後の daybook.keys 取得は汚染されない
            assertFalse(daybook.keys.contains("injected"))
        }
        assertEquals(setOf("a"), daybook.keys)
    }

    // --- 攻撃 5: property のデフォルト値取得に副作用がないか ---
    //
    // 契約: getter はキー不在で default を返す。property.get() がデフォルトを返すだけで
    // ストアに書き込みを行わない（get に副作用がない）ことは明記されていないが暗黙の前提。
    @Test
    fun property_get_hasNoWriteSideEffect() {
        val daybook = open2(IntKeyHolder)
        val property = daybook.property(IntKeyHolder.key, default = 42)
        assertEquals(42, property.get())
        assertFalse(daybook.contains(property.key))
    }

    private object IntKeyHolder : DaybookSchema("side-effect-probe") {
        val key = int("side_effect_probe")
    }

    // --- 攻撃 6: リスナーの二重登録 ---
    //
    // 契約: 「登録済みのリスナーを重ねて登録しても 1 登録のまま（SharedPreferences のリスナーと
    // 同じ Set 意味論。removeChangeListener 1 回で完全に解除）」
    @Test
    fun listener_doubleRegistration_isSingleRegistration() {
        val daybook = open()
        val events = Events(expectedCount = 1)
        daybook.addChangeListener(events.listener)
        daybook.addChangeListener(events.listener) // 同一インスタンスの重複登録は 1 登録のまま
        daybook.edit { putInt("k", 1) }
        assertTrue(waitUntil { events.snapshot().isNotEmpty() })
        // 重複登録による二重配送が起きないこと
        assertFalse(waitUntil(timeoutMillis = 700) { events.snapshot().size > 1 })

        daybook.removeChangeListener(events.listener) // 1 回の remove で完全に解除
        daybook.edit { putInt("k2", 2) }
        val settled = waitUntil(timeoutMillis = 1500) { events.snapshot().size > 1 }
        assertFalse(settled, "1 回の removeChangeListener で二重登録された同一リスナーの通知が完全に止まること")
    }

    // --- 攻撃 7: リスナー内からの再入（store の再操作） ---
    //
    // 契約(common-api.md): 「配送はストアごとの専用スレッドで書き込み順に直列。ロック外で配送
    // されるため、リスナー内から store を再操作してもデッドロックしない」
    @Test
    fun listener_reentrantEditFromWithinCallback_doesNotDeadlock() {
        val daybook = open()
        val reentrantDone = Lock()
        var reentrantFired = false
        val listener = DaybookChangeListener { key, _ ->
            if (key == "trigger" && !reentrantFired) {
                reentrantFired = true
                // リスナー内から同じ daybook を再操作する
                daybook.edit { putInt("from-listener", 99) }
            }
        }
        daybook.addChangeListener(listener)
        daybook.edit { putString("trigger", "go") }

        assertTrue(
            waitUntil { daybook.contains("from-listener") },
            "リスナー内からの再入編集がデッドロックせず完了すること",
        )
        assertEquals(99, daybook.getInt("from-listener", -1))
    }

    // --- 攻撃 8: map / catch の合成 — 多段チェーンと encode 失敗の非捕捉 ---
    //
    // 契約(DaybookProperty.kt KDoc): 「catch の対象は読み取り経路だけ。書き込みの失敗は
    // 呼び出し側のバグとしてそのまま伝播する」。map を二段重ねた場合の contract も検証する。
    private object MapSchema : DaybookSchema("map-schema") {
        val raw = string("raw")
    }

    @Test
    fun map_chainedTwice_bothDirectionsApply() {
        val daybook = open2(MapSchema)
        val property = daybook.property(MapSchema.raw, default = "0")
            .map(decode = { it.toInt() }, encode = { it.toString() })
            .map(decode = { it * 2 }, encode = { (it / 2) })
        assertEquals(0, property.get())
        property.set(10)
        assertEquals(10, property.get())
        assertEquals("5", daybook.getString("raw", null))
    }

    @Test
    fun catch_afterChainedMap_stillOnlyGuardsRead() {
        val daybook = open2(MapSchema)
        daybook.edit { putString("raw", "not-a-number") }
        val property = daybook.property(MapSchema.raw, default = "0")
            .map(decode = { it.toInt() }, encode = { it.toString() })
            .catch { -1 }
        assertEquals(-1, property.get())
        // catch は書き込み経路を保護しない: encode がそのまま伝播することを確認する
        // （ここでは encode 自体は例外を投げないため、素直に書き込めることの確認）
        property.set(7)
        assertEquals("7", daybook.getString("raw", null))
    }

    private fun open2(schema: DaybookSchema): Daybook = KvStore.openInMemory().asDaybook(schema)

    // --- 攻撃 9: contains / remove の境界 ---
    @Test
    fun contains_afterPutThenRemoveInSameBlock_isFalse() {
        val daybook = open()
        daybook.edit {
            putInt("k", 1)
            remove("k")
        }
        assertFalse(daybook.contains("k"))
        assertFalse(daybook.keys.contains("k"))
    }

    @Test
    fun remove_thenPut_sameBlock_survivesAsPresent() {
        val daybook = open()
        daybook.edit {
            remove("k") // 存在しないキーの remove
            putInt("k", 1)
        }
        assertTrue(daybook.contains("k"))
        assertEquals(1, daybook.getInt("k", -1))
    }

    // --- 攻撃 10: 巨大値 ---
    @Test
    fun edit_veryLargeStringAndStringSet_roundTrip() {
        val daybook = open()
        val huge = "x".repeat(2_000_000) // 2,000,000 文字
        val hugeSet = (1..20_000).map { "item-$it" }.toSet()
        daybook.edit {
            putString("huge", huge)
            putStringSet("hugeSet", hugeSet)
        }
        assertEquals(huge.length, daybook.getString("huge", null)?.length)
        assertEquals(hugeSet.size, daybook.getStringSet("hugeSet", null)?.size)
    }
}
