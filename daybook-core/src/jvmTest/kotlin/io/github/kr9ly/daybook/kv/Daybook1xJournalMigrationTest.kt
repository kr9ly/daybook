package io.github.kr9ly.daybook.kv

import io.github.kr9ly.daybook.io.IoException
import io.github.kr9ly.daybook.journal.platformDirectorySync
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertFailsWith

/**
 * 1.x ジャーナルの一回きり取り込み（[MigrationSource.Companion.daybook1xJournal]）の契約テスト。
 *
 * 入力の 1.x ジャーナル（version 1）は [V1JournalWriter] でバイト列から再現する。
 * 取り込み後のファイル配置（退避・マーカー・v2 世代）もここで検証する。
 */
class Daybook1xJournalMigrationTest {

    @get:Rule
    val folder = TemporaryFolder()

    @After
    fun tearDown() {
        DaybookRegistry.resetForTesting()
    }

    private fun dir(): String = folder.root.path

    private fun v1File(name: String = "daybook", generation: Long = 1): File =
        File(folder.root, "$name.$generation.journal")

    private fun setAsideFile(name: String = "daybook"): File =
        File(folder.root, "$name.journal.v1")

    private fun markerFile(name: String = "daybook", id: String = "daybook-1x"): File =
        File(folder.root, "$name.$id.migrated")

    // 名前ごとにスキーマを固定する（同名の再オープンはスキーマ同一性検査があるため）
    private val schemas = HashMap<String, DaybookSchema>()

    private fun schemaFor(name: String): DaybookSchema =
        schemas.getOrPut(name) { object : DaybookSchema(name) {} }

    private fun openWith1xMigration(name: String = "daybook"): Daybook =
        Daybook.open(dir(), schemaFor(name)) { migrations = listOf(MigrationSource.daybook1xJournal()) }

    // --- 取り込みの基本 ---

    @Test
    fun migratesAllSixValueTypes() {
        V1JournalWriter()
            .record(V1JournalWriter.put("string", "value"))
            .record(V1JournalWriter.put("int", 42))
            .record(V1JournalWriter.put("long", 1L shl 40))
            .record(V1JournalWriter.put("float", 1.5f))
            .record(V1JournalWriter.put("boolean", true))
            .record(V1JournalWriter.put("boolean-false", false))
            .record(V1JournalWriter.put("set", setOf("a", "b")))
            .writeTo(v1File())

        val daybook = openWith1xMigration()

        assertEquals("value", daybook.getString("string", null))
        assertEquals(42, daybook.getInt("int", 0))
        assertEquals(1L shl 40, daybook.getLong("long", 0))
        assertEquals(1.5f, daybook.getFloat("float", 0f), 0f)
        assertEquals(true, daybook.getBoolean("boolean", false))
        assertEquals(false, daybook.getBoolean("boolean-false", true))
        assertEquals(setOf("a", "b"), daybook.getStringSet("set", null))
    }

    @Test
    fun replaysRemoveClearBatchAndBoundary() {
        V1JournalWriter()
            .record(V1JournalWriter.put("gone-by-clear", "x"))
            .record(V1JournalWriter.clear())
            .record(V1JournalWriter.put("kept", "v1"))
            .record(V1JournalWriter.put("gone-by-remove", "x"))
            .record(V1JournalWriter.remove("gone-by-remove"))
            .record(V1JournalWriter.snapshotBoundary())
            .record(
                V1JournalWriter.batch(
                    V1JournalWriter.put("kept", "v2"),
                    V1JournalWriter.put("batched", "b"),
                    V1JournalWriter.remove("missing"),
                ),
            )
            .writeTo(v1File())

        val daybook = openWith1xMigration()

        assertEquals("v2", daybook.getString("kept", null))
        assertEquals("b", daybook.getString("batched", null))
        assertFalse(daybook.contains("gone-by-clear"))
        assertFalse(daybook.contains("gone-by-remove"))
        assertFalse(daybook.contains("missing"))
    }

    @Test
    fun batchContainingClear_replaysLikeV1() {
        V1JournalWriter()
            .record(V1JournalWriter.put("before", "x"))
            .record(V1JournalWriter.batch(V1JournalWriter.clear(), V1JournalWriter.put("after", "y")))
            .writeTo(v1File())

        val daybook = openWith1xMigration()
        assertEquals("y", daybook.getString("after", null))
        assertFalse(daybook.contains("before"))
    }

    @Test
    fun corruptedTail_isTruncatedSilently() {
        val corruptTails = listOf(
            byteArrayOf(0, 0, 0, 9, 1, 2, 3), // 長さ 9 を名乗る不完全レコード
            byteArrayOf(1, 2), // 長さフィールド自体が不完全
            byteArrayOf(-1, -1, -1, -1), // 負の長さ
            byteArrayOf(0x7F, 0, 0, 0), // MAX_PAYLOAD_SIZE 超えの長さ
            byteArrayOf(0, 0, 0, 1, 3, 0, 0, 0, 0), // CRC 不一致
        )
        corruptTails.forEachIndexed { i, tail ->
            val name = "tail$i"
            V1JournalWriter()
                .record(V1JournalWriter.put("valid", "kept"))
                .raw(tail)
                .writeTo(v1File(name))

            assertEquals("kept", openWith1xMigration(name).getString("valid", null))
        }
    }

    // --- ファイル配置（退避・マーカー・v2 世代） ---

    @Test
    fun migration_setsAsideV1JournalAndCreatesMarker() {
        val original = V1JournalWriter()
            .record(V1JournalWriter.put("key", "value"))
            .writeTo(v1File())
            .readBytes()

        openWith1xMigration()

        assertTrue(markerFile().exists())
        assertTrue(setAsideFile().exists())
        assertTrue(original.contentEquals(setAsideFile().readBytes()))
        // 世代ファイルは v2 で作り直されている（1.x の中身がそのまま残っていない）
        assertEquals(2, v1File().readBytes()[7].toInt())
    }

    @Test
    fun migration_deletesOlderV1Generations() {
        V1JournalWriter().record(V1JournalWriter.put("old", "gen1")).writeTo(v1File(generation = 1))
        V1JournalWriter().record(V1JournalWriter.put("new", "gen2")).writeTo(v1File(generation = 2))

        val daybook = openWith1xMigration()

        assertEquals("gen2", daybook.getString("new", null))
        assertFalse(daybook.contains("old"))
        // 旧世代の v1 ファイルは削除済み。世代 1 のパスに残っているのはエンジンが
        // 作り直した新規の v2 ジャーナル（1.x の中身ではない）
        assertEquals(2, v1File(generation = 1).readBytes()[7].toInt())
        // 退避されたのは最大世代（gen2）の方
        assertTrue(setAsideFile().readBytes()[7].toInt() == 1)
    }

    @Test
    fun migration_adoptsV1CompactionTemp() {
        // 1.x の compaction が rename 前に落ちた形: 世代ファイルなし・一時ファイルだけ
        V1JournalWriter()
            .record(V1JournalWriter.put("key", "from-temp"))
            .writeTo(File(folder.root, "daybook.2.journal.tmp"))

        assertEquals("from-temp", openWith1xMigration().getString("key", null))
    }

    // --- 冪等性 ---

    @Test
    fun migration_runsOnceAcrossReopen() {
        V1JournalWriter().record(V1JournalWriter.put("key", "from-1x")).writeTo(v1File())

        openWith1xMigration().edit { putString("key", "edited") }
        DaybookRegistry.resetForTesting() // プロセス再起動の代わり

        assertEquals("edited", openWith1xMigration().getString("key", null))
    }

    @Test
    fun migration_reRunsFromSetAside_whenMarkerIsMissing() {
        // 取り込み成功 → マーカー作成前にクラッシュ、の再現: 退避ファイルだけがある
        V1JournalWriter().record(V1JournalWriter.put("key", "from-1x")).writeTo(setAsideFile())

        assertEquals("from-1x", openWith1xMigration().getString("key", null))
        assertTrue(markerFile().exists())
    }

    @Test
    fun freshDirectory_marksDoneWithoutData() {
        val daybook = openWith1xMigration()

        assertFalse(daybook.contains("key"))
        assertTrue(markerFile().exists())
        assertFalse(setAsideFile().exists())
    }

    @Test
    fun existing2xStore_marksDoneWithoutTouchingData() {
        Daybook.open(dir(), schemaFor("daybook")).edit { putString("key", "v2-data") }
        DaybookRegistry.resetForTesting()

        val daybook = openWith1xMigration()

        assertEquals("v2-data", daybook.getString("key", null))
        assertTrue(markerFile().exists())
        assertFalse(setAsideFile().exists())
    }

    @Test
    fun headerOnlyRemnant_isTreatedAsEmpty() {
        // ヘッダ書き込み途中のクラッシュ相当（8 バイト未満）。エンジンが v2 として書き直す
        v1File().writeBytes(byteArrayOf(0x44, 0x42, 0x4B, 0x4A, 0, 0))

        val daybook = openWith1xMigration()

        assertFalse(daybook.contains("key"))
        assertTrue(markerFile().exists())
    }

    @Test
    fun migratedValues_overwriteOnlySameKeys() {
        // 退避ファイル再実行の経路で、既存 2.x データとの合成規則を見る
        V1JournalWriter()
            .record(V1JournalWriter.put("shared", "from-1x"))
            .record(V1JournalWriter.put("only-1x", "kept"))
            .writeTo(setAsideFile())
        Daybook.open(dir(), schemaFor("daybook")).edit {
            putString("shared", "from-2x")
            putString("only-2x", "kept")
        }
        DaybookRegistry.resetForTesting()

        val daybook = openWith1xMigration()

        assertEquals("from-1x", daybook.getString("shared", null))
        assertEquals("kept", daybook.getString("only-1x", null))
        assertEquals("kept", daybook.getString("only-2x", null))
    }

    // --- 1.x として読めない入力は例外（黙ってマーカーを作らない） ---

    private fun assertMigrationFails(name: String = "daybook") {
        assertFailsWith<IoException> { openWith1xMigration(name) }
        assertFalse(markerFile(name).exists())
    }

    @Test
    fun badMagic_throws() {
        v1File().writeBytes(byteArrayOf(1, 2, 3, 4, 0, 0, 0, 1))
        assertMigrationFails()
    }

    @Test
    fun corruptedSetAside_throws() {
        setAsideFile().writeBytes(byteArrayOf(1, 2, 3))
        assertMigrationFails()

        V1JournalWriter().writeTo(setAsideFile())
        setAsideFile().writeBytes(setAsideFile().readBytes().also { it[7] = 9 }) // version 書き換え
        assertMigrationFails()
    }

    @Test
    fun undecodablePayloads_throw() {
        val badPayloads = listOf(
            byteArrayOf(9), // unknown op tag
            byteArrayOf(1, 0, 0, 0, 1, 'k'.code.toByte(), 9), // unknown value type tag
            byteArrayOf(1, -1, -1, -1, -1), // negative string length
            byteArrayOf(1, 0, 0, 0, 1, 'k'.code.toByte(), 5, 2), // invalid boolean
            byteArrayOf(1, 0, 0, 0, 1, 'k'.code.toByte(), 6, -1, -1, -1, -1), // negative set count
            byteArrayOf(5, -1, -1, -1, -1), // negative batch count
            byteArrayOf(5, 0, 0, 0, 1, 4), // batch 内の boundary は単一操作でない
            byteArrayOf(2, 0, 0, 0, 4, 'k'.code.toByte()), // truncated payload
            byteArrayOf(3, 0), // trailing garbage
        )
        badPayloads.forEachIndexed { i, payload ->
            val name = "store$i"
            V1JournalWriter().record(payload).writeTo(v1File(name))
            assertMigrationFails(name)
        }
    }

    @Test
    fun setAsideRenameFailure_throws() {
        V1JournalWriter().record(V1JournalWriter.put("key", "value")).writeTo(v1File())
        folder.root.setWritable(false)
        try {
            assertFailsWith<IoException> { openWith1xMigration() }
        } finally {
            folder.root.setWritable(true)
        }
        assertFalse(markerFile().exists())
        // 元の 1.x ジャーナルは無傷（次のオープンで再試行できる）
        assertEquals("value", openWith1xMigration().getString("key", null))
    }

    // --- レジストリの実行契約（マーカー・null 再試行・重複 id・失敗時の非キャッシュ） ---

    private class FakeSource(
        override val id: String = "fake",
        private val results: MutableList<Map<String, Any>?>,
    ) : MigrationSource {
        var readCount = 0

        override fun read(environment: MigrationEnvironment): Map<String, Any>? {
            readCount++
            return results.removeAt(0)
        }
    }

    private fun openWith(vararg sources: MigrationSource): Daybook =
        Daybook.open(dir(), schemaFor("daybook")) { migrations = sources.toList() }

    @Test
    fun nullRead_isRetriedOnNextStoreCreation() {
        val source = FakeSource(results = mutableListOf(null, mapOf("key" to "late")))

        assertNull(openWith(source).getString("key", null))
        assertFalse(markerFile(id = "fake").exists())
        DaybookRegistry.resetForTesting()

        assertEquals("late", openWith(source).getString("key", null))
        assertTrue(markerFile(id = "fake").exists())
        assertEquals(2, source.readCount)
    }

    @Test
    fun marker_preventsFurtherReads() {
        val source = FakeSource(results = mutableListOf(emptyMap(), emptyMap()))

        openWith(source)
        DaybookRegistry.resetForTesting()
        openWith(source)

        assertEquals(1, source.readCount)
    }

    @Test
    fun cacheHit_ignoresMigrations() {
        Daybook.open(dir(), schemaFor("daybook"))
        val source = FakeSource(results = mutableListOf(mapOf("key" to "ignored")))

        assertNull(openWith(source).getString("key", null))
        assertEquals(0, source.readCount)
    }

    @Test
    fun duplicateIds_onlyFirstRuns() {
        val first = FakeSource(results = mutableListOf(mapOf("key" to "first")))
        val second = FakeSource(results = mutableListOf(mapOf("key" to "second")))

        assertEquals("first", openWith(first, second).getString("key", null))
        assertEquals(0, second.readCount)
    }

    @Test
    fun invalidSourceId_throws() {
        assertFailsWith<IllegalArgumentException> {
            openWith(FakeSource(id = "", results = mutableListOf(emptyMap())))
        }
        assertFailsWith<IllegalArgumentException> {
            openWith(FakeSource(id = "a/b", results = mutableListOf(emptyMap())))
        }
    }

    @Test
    fun failedMigration_leavesStoreUncachedAndUnmarked() {
        // 適用（writeBatch）の失敗: 対応外の値型
        val bad = object : MigrationSource {
            override val id: String = "bad"
            override fun read(environment: MigrationEnvironment): Map<String, Any> =
                mapOf("key" to Any())
        }
        assertFailsWith<IllegalArgumentException> { openWith(bad) }
        assertFalse(markerFile(id = "bad").exists())

        // 失敗したストアはキャッシュに載っていない（開き直せて、部分適用も残っていない）
        val recovered = openWith(FakeSource(results = mutableListOf(mapOf("key" to "ok"))))
        assertEquals("ok", recovered.getString("key", null))
    }

    @Test
    fun withStore_runsMigrationsOnTemporaryOpen() {
        V1JournalWriter().record(V1JournalWriter.put("key", "from-1x")).writeTo(v1File())

        val value = DaybookRegistry.withStore(
            dir(),
            "daybook",
            platformDirectorySync(),
            migrations = listOf(MigrationSource.daybook1xJournal()),
        ) { store -> store.get("key") }

        assertEquals("from-1x", value)
        assertTrue(markerFile().exists())
        // 一時オープンでも取り込みは永続化されている
        assertEquals("from-1x", openWith1xMigration().getString("key", null))
    }
}
