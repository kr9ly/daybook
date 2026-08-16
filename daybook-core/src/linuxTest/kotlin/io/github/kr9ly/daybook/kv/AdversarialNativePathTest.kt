@file:OptIn(ExperimentalForeignApi::class)

package io.github.kr9ly.daybook.kv

import io.github.kr9ly.daybook.concurrent.waitUntil
import io.github.kr9ly.daybook.io.FilePath
import io.github.kr9ly.daybook.io.appendFileBytes
import io.github.kr9ly.daybook.io.createTempDirectory
import io.github.kr9ly.daybook.io.readFileOrEmpty
import io.github.kr9ly.daybook.io.writeFileBytes
import io.github.kr9ly.daybook.journal.JournalFormatException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import platform.posix.closedir
import platform.posix.opendir
import platform.posix.readdir
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * レーン 6: Native 経路（linuxX64）の敵対的テスト。
 *
 * 契約は README.md / DESIGN.md / docs/common-api.md / public-api-extract.md（KDoc 抽出）
 * のみを根拠とする。実装ソース（各種 Main ソースセット配下）は読んでいない。
 *
 * 攻撃対象の契約文（出典つき）:
 *
 * 1. docs/common-api.md「durability: SYNC は書き込みごとに fsync（遅いが電源断まで耐える）、
 *    ASYNC は OS のページキャッシュに任せる（プロセスクラッシュには耐える）」
 * 2. DESIGN.md「どちらのモードでもディスク上のアトミック性（クラッシュ時に部分適用された
 *    バッチが残らないこと）は変わらない」（Durability.kt KDoc と同一）
 * 3. docs/common-api.md / DaybookOpenOptions KDoc「オプションはストアのインスタンス生成時
 *    にだけ使われる。同じストアを再取得するときは同じ値を指定すること（不一致は
 *    IllegalArgumentException）」— durability と multiProcess の両方
 * 4. DaybookOpenOptions.multiProcess KDoc「有効にすると書き込みはプロセス間ロックで
 *    直列化され、他プロセスの編集は自動的に見えるようになる」「同じストアを開く全プロセスで
 *    このフラグを一致させること」
 * 5. DESIGN.md「プロセス間ロックのロック族はプラットフォームで異なる（… Native = flock）」
 * 6. Daybook KDoc「複数の書き込みを 1 つのアトミックなバッチとして適用する。ブロック内の
 *    操作は呼び出し順に記録され、ブロック完了時に 1 ジャーナルレコードとして書かれる」
 * 7. DESIGN.md「配送はストアごとの専用ディスパッチスレッドで書き込み順に直列。配送は
 *    ロック外で行い、リスナー内で daybook を再操作してもデッドロックしない」
 *   （DaybookChangeListener KDoc と同一趣旨）
 * 8. README.md「壊れない: ジャーナルは CRC つきで、クラッシュ・電源断は壊れたテールの
 *    切り捨てで復旧する」/ DESIGN.md「リプレイ時、CRC 不一致 or 長さ不整合を検出したら、
 *    そのエントリ以降（壊れたテール）を切り捨てて最後の正常状態に復旧する」
 * 9. DESIGN.md「ファイル先頭に [magic][version] ヘッダ。magic 不一致・未知バージョンは
 *    切り捨てで「復旧」せず例外にする — 別物ファイルを黙って壊さない」
 * 10. Daybook KDoc「ジャーナルは `<directory>/<name>.<世代番号>.journal` として保存される」
 *     （破損注入のためのファイル特定に使用。世代番号の具体値はドキュメントに明記がないため
 *     決め打ちせず、ディレクトリを走査して特定する）
 *
 * 攻撃数: 16
 */
class AdversarialNativePathTest {

    private object DurSyncSchema : DaybookSchema("dur_sync")
    private object DurAsyncSchema : DaybookSchema("dur_async")
    private object DurMismatchSchema : DaybookSchema("dur_mismatch")
    private object MpMismatchSchema : DaybookSchema("mp_mismatch")
    private object MpSoloSchema : DaybookSchema("mp_solo")
    private object MpLoopSchema : DaybookSchema("mp_loop")
    private object HugeValueSchema : DaybookSchema("huge_value")
    private object ManyKeysSchema : DaybookSchema("many_keys")
    private object BoundarySchema : DaybookSchema("boundary") {
        val i = int("i")
        val l = long("l")
        val f = float("f")
        val d = double("d")
    }
    private object AtomicitySchema : DaybookSchema("atomicity")
    private object ListenerOrderSchema : DaybookSchema("listener_order")
    private object ReentrantSchema : DaybookSchema("reentrant")
    private object TailGarbageSchema : DaybookSchema("tail_garbage")
    private object TruncateSchema : DaybookSchema("truncate")
    private object HeaderCorruptSchema : DaybookSchema("header_corrupt")
    private object MigrationsIgnoredSchema : DaybookSchema("migrations_ignored")

    @AfterTest
    fun resetRegistry() {
        DaybookRegistry.resetForTesting()
    }

    // --- 1: Durability オプションごとの永続化（open し直して読み戻し） ---

    @Test
    fun durability_SYNC_persistsAcrossReopen() {
        val dir = createTempDirectory()
        Daybook.open(dir.path, DurSyncSchema) { durability = Durability.SYNC }.edit {
            putString("key", "sync-value")
        }
        DaybookRegistry.resetForTesting()
        val reopened = Daybook.open(dir.path, DurSyncSchema) { durability = Durability.SYNC }
        assertEquals("sync-value", reopened.getString("key", null))
    }

    @Test
    fun durability_ASYNC_persistsAcrossReopen() {
        val dir = createTempDirectory()
        Daybook.open(dir.path, DurAsyncSchema) { durability = Durability.ASYNC }.edit {
            putString("key", "async-value")
        }
        DaybookRegistry.resetForTesting()
        val reopened = Daybook.open(dir.path, DurAsyncSchema) { durability = Durability.ASYNC }
        assertEquals("async-value", reopened.getString("key", null))
    }

    // --- 2: durability / multiProcess の不一致は fail-fast ---

    @Test
    fun reopen_withDifferentDurability_throwsIllegalArgumentException() {
        val dir = createTempDirectory()
        Daybook.open(dir.path, DurMismatchSchema) { durability = Durability.ASYNC }
        assertFailsWith<IllegalArgumentException> {
            Daybook.open(dir.path, DurMismatchSchema) { durability = Durability.SYNC }
        }
    }

    @Test
    fun reopen_withDifferentMultiProcess_throwsIllegalArgumentException() {
        val dir = createTempDirectory()
        Daybook.open(dir.path, MpMismatchSchema) { multiProcess = false }
        assertFailsWith<IllegalArgumentException> {
            Daybook.open(dir.path, MpMismatchSchema) { multiProcess = true }
        }
    }

    // --- 3: multiProcess の受理（flock の実経路が単一プロセスで組み合わさって動くか） ---

    @Test
    fun multiProcess_enabled_singleProcess_readWriteWorks() {
        val dir = createTempDirectory()
        val daybook = Daybook.open(dir.path, MpSoloSchema) { multiProcess = true }
        daybook.edit { putInt("count", 7) }
        assertEquals(7, daybook.getInt("count", -1))
    }

    @Test
    fun multiProcess_enabled_reopenLoop_doesNotLeakLocksOrFileHandles() {
        val dir = createTempDirectory()
        repeat(50) { i ->
            val daybook = Daybook.open(dir.path, MpLoopSchema) { multiProcess = true }
            daybook.edit { putInt("iteration", i) }
            assertEquals(i, daybook.getInt("iteration", -1))
            DaybookRegistry.resetForTesting()
        }
    }

    // --- 4: 巨大値・大量キー・compaction 閾値超え ---

    @Test
    fun hugeValue_crossingCompactionThreshold_survivesReopen() {
        val dir = createTempDirectory()
        val bigChunk = "x".repeat(600_000) // 3 件で ~1.8MiB、既定閾値 1MiB を超える
        val daybook = Daybook.open(dir.path, HugeValueSchema)
        daybook.edit {
            putString("chunk1", bigChunk)
            putString("chunk2", bigChunk)
            putString("chunk3", bigChunk)
        }
        DaybookRegistry.resetForTesting()
        val reopened = Daybook.open(dir.path, HugeValueSchema)
        assertEquals(bigChunk, reopened.getString("chunk1", null))
        assertEquals(bigChunk, reopened.getString("chunk2", null))
        assertEquals(bigChunk, reopened.getString("chunk3", null))
    }

    @Test
    fun manyKeys_5000_survivesReopen() {
        val dir = createTempDirectory()
        val daybook = Daybook.open(dir.path, ManyKeysSchema)
        daybook.edit {
            repeat(5_000) { i -> putInt("k$i", i) }
        }
        DaybookRegistry.resetForTesting()
        val reopened = Daybook.open(dir.path, ManyKeysSchema)
        assertEquals(5_000, reopened.keys.size)
        assertEquals(4_999, reopened.getInt("k4999", -1))
        assertEquals(0, reopened.getInt("k0", -1))
    }

    @Test
    fun boundaryNumericValues_roundTrip() {
        val dir = createTempDirectory()
        val daybook = Daybook.open(dir.path, BoundarySchema)
        daybook.edit {
            putInt("i", Int.MIN_VALUE)
            putLong("l", Long.MAX_VALUE)
            putFloat("f_nan", Float.NaN)
            putFloat("f_inf", Float.POSITIVE_INFINITY)
            putDouble("d_nan", Double.NaN)
            putDouble("d_neg_inf", Double.NEGATIVE_INFINITY)
        }
        DaybookRegistry.resetForTesting()
        val reopened = Daybook.open(dir.path, BoundarySchema)
        assertEquals(Int.MIN_VALUE, reopened.getInt("i", 0))
        assertEquals(Long.MAX_VALUE, reopened.getLong("l", 0))
        assertTrue(reopened.getFloat("f_nan", 0f).isNaN())
        assertEquals(Float.POSITIVE_INFINITY, reopened.getFloat("f_inf", 0f))
        assertTrue(reopened.getDouble("d_nan", 0.0).isNaN())
        assertEquals(Double.NEGATIVE_INFINITY, reopened.getDouble("d_neg_inf", 0.0))
    }

    // --- 5: edit アトミック性（ブロック完了前に投げた例外は何も適用しない） ---

    @Test
    fun edit_exceptionMidBlock_appliesNothing() {
        val dir = createTempDirectory()
        val daybook = Daybook.open(dir.path, AtomicitySchema)
        daybook.edit { putString("before", "seed") }

        val thrown = runCatching {
            daybook.edit {
                putString("before", "changed")
                putString("new_key", "value")
                throw RuntimeException("boom")
            }
        }
        assertTrue(thrown.isFailure)
        // ブロック完了時に 1 レコードとして書かれる契約どおりなら、例外で完了しなかった
        // ブロックの操作は一切反映されないはず
        assertEquals("seed", daybook.getString("before", null))
        assertFalse(daybook.contains("new_key"))

        DaybookRegistry.resetForTesting()
        val reopened = Daybook.open(dir.path, AtomicitySchema)
        assertEquals("seed", reopened.getString("before", null))
        assertFalse(reopened.contains("new_key"))
    }

    // --- 6: リスナー配送（操作ベース・呼び出し順・再入でデッドロックしない） ---

    @Test
    fun listener_receivesEachOperationInCallOrder() {
        val dir = createTempDirectory()
        val daybook = Daybook.open(dir.path, ListenerOrderSchema)
        val received = mutableListOf<Pair<String, Any?>>()
        daybook.addChangeListener { key, newValue -> received.add(key to newValue) }

        daybook.edit {
            putString("a", "1")
            putString("a", "1") // 同値 put も操作として通知される契約
            putInt("b", 2)
            remove("a")
        }

        assertTrue(
            waitUntil { received.size >= 4 },
            "配送が 4 件届くのを待ったがタイムアウト: 実際 = $received",
        )
        assertEquals(
            listOf<Pair<String, Any?>>("a" to "1", "a" to "1", "b" to 2, "a" to null),
            received,
        )
    }

    @Test
    fun listener_reentrantEditFromCallback_doesNotDeadlock() {
        val dir = createTempDirectory()
        val daybook = Daybook.open(dir.path, ReentrantSchema)
        var reentered = false
        daybook.addChangeListener { key, _ ->
            if (key == "trigger" && !reentered) {
                reentered = true
                daybook.edit { putString("from_listener", "done") }
            }
        }

        daybook.edit { putString("trigger", "go") }

        assertTrue(
            waitUntil { daybook.contains("from_listener") },
            "リスナー内からの再入 edit が完了しない（デッドロックの疑い）",
        )
        assertEquals("done", daybook.getString("from_listener", null))
    }

    // --- 7: ジャーナル破損からの復旧契約 ---

    @Test
    fun tailGarbageAppended_recoversToLastGoodState() {
        val dir = createTempDirectory()
        val daybook = Daybook.open(dir.path, TailGarbageSchema)
        daybook.edit { putString("good", "value") }
        val journalPath = requireSingleJournalFile(dir.path, TailGarbageSchema.storeName)
        DaybookRegistry.resetForTesting()

        appendFileBytes(FilePath(journalPath), ByteArray(37) { 0x7F })

        val reopened = Daybook.open(dir.path, TailGarbageSchema)
        assertEquals("value", reopened.getString("good", null))
    }

    @Test
    fun tailTruncatedMidRecord_recoversToLastGoodState() {
        val dir = createTempDirectory()
        val daybook = Daybook.open(dir.path, TruncateSchema)
        daybook.edit { putString("good", "value") }
        daybook.edit { putString("second", "will-be-cut") }
        val journalPath = requireSingleJournalFile(dir.path, TruncateSchema.storeName)
        DaybookRegistry.resetForTesting()

        val bytes = readFileOrEmpty(FilePath(journalPath))
        // 末尾の 5 バイトだけを切り落とす: 2 レコード目の CRC / length のどちらかを壊す
        writeFileBytes(FilePath(journalPath), bytes.copyOfRange(0, bytes.size - 5))

        val reopened = Daybook.open(dir.path, TruncateSchema)
        assertEquals("value", reopened.getString("good", null))
        // "second" は壊れたテールとして切り捨てられ復旧しない契約のはず
        assertNull(reopened.getString("second", null))
    }

    @Test
    fun headerMagicCorrupted_throwsJournalFormatException_notSilentRecovery() {
        val dir = createTempDirectory()
        Daybook.open(dir.path, HeaderCorruptSchema).edit { putString("good", "value") }
        val journalPath = requireSingleJournalFile(dir.path, HeaderCorruptSchema.storeName)
        DaybookRegistry.resetForTesting()

        val bytes = readFileOrEmpty(FilePath(journalPath)).copyOf()
        // magic の先頭バイトを破壊する
        bytes[0] = (bytes[0].toInt() xor 0xFF).toByte()
        writeFileBytes(FilePath(journalPath), bytes)

        assertFailsWith<JournalFormatException> {
            Daybook.open(dir.path, HeaderCorruptSchema)
        }
    }

    // --- 8: migrations はキャッシュヒット時に無視される契約（生成時のみ有効） ---

    @Test
    fun migrations_ignoredOnCacheHit_evenOnNative() {
        val dir = createTempDirectory()
        Daybook.open(dir.path, MigrationsIgnoredSchema)
        var invoked = false
        val source = object : MigrationSource {
            override val id: String = "should-not-run"
            override fun read(environment: MigrationEnvironment): Map<String, Any>? {
                invoked = true
                return emptyMap()
            }
        }
        // 同じストアを再取得（キャッシュヒット）。migrations はここでは無視される契約
        Daybook.open(dir.path, MigrationsIgnoredSchema) { migrations = listOf(source) }
        assertFalse(invoked, "キャッシュヒット時に migrations が実行された（契約違反の疑い）")
    }
}

/**
 * ディレクトリを走査して `.journal` で終わる唯一のファイルの絶対パスを返す。
 * ファイル名の世代番号の具体値はドキュメントに明記がないため決め打ちしない。
 */
@OptIn(ExperimentalForeignApi::class)
private fun requireSingleJournalFile(directory: String, storeNamePrefix: String): String {
    val dir = opendir(directory) ?: throw IllegalStateException("opendir failed: $directory")
    val candidates = mutableListOf<String>()
    try {
        while (true) {
            val entry = readdir(dir) ?: break
            val name = entry.pointed.d_name.toKString()
            if (name.startsWith(storeNamePrefix) && name.endsWith(".journal")) {
                candidates.add(name)
            }
        }
    } finally {
        closedir(dir)
    }
    check(candidates.size == 1) {
        "期待は 1 個の journal ファイルだが実際は $candidates（directory=$directory）"
    }
    return "$directory/${candidates.single()}"
}
