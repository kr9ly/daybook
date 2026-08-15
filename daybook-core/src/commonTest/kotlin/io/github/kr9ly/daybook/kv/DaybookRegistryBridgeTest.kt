package io.github.kr9ly.daybook.kv

import io.github.kr9ly.daybook.io.FilePath
import io.github.kr9ly.daybook.io.IoException
import io.github.kr9ly.daybook.io.createTempDirectory
import io.github.kr9ly.daybook.journal.DirectorySync
import io.github.kr9ly.daybook.journal.JournalWatcherFactory
import io.github.kr9ly.daybook.journal.platformDirectorySync
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [DaybookRegistry] のプラットフォーム注入ブリッジ（:daybook 用）の契約テスト —
 * watcher / directory fsync の生成時注入、onCreate フック、withStore の直列化窓口。
 *
 * [Daybook.open] 経路（キャッシュ・不一致 fail-fast・パス正規化）は DaybookOpenTest が
 * 検証済みで、ここでは注入つきの入口だけを見る。
 */
class DaybookRegistryBridgeTest {

    private val folder = createTempDirectory()

    @AfterTest
    fun tearDown() {
        DaybookRegistry.resetForTesting()
    }

    private fun dir(): String = folder.path

    private object StoreSchema : DaybookSchema("store")

    /** watch 呼び出しを記録するだけの watcher（検知はしない）。 */
    private class RecordingWatcherFactory : JournalWatcherFactory {
        var watchCount = 0

        override fun watch(directory: FilePath, onChange: () -> Unit): AutoCloseable {
            watchCount++
            return AutoCloseable {}
        }
    }

    private class RecordingDirectorySync : DirectorySync {
        var syncCount = 0

        override fun sync(directory: FilePath) {
            syncCount++
            platformDirectorySync().sync(directory)
        }
    }

    // --- openDaybook（Daybook アダプタ + 注入） ---

    @Test
    fun openDaybook_multiProcessUsesInjectedWatcher() {
        val watcher = RecordingWatcherFactory()
        DaybookRegistry.openDaybook(
            dir(),
            StoreSchema,
            configure = { multiProcess = true },
            watcherFactory = watcher,
            directorySync = platformDirectorySync(),
        )
        assertEquals(1, watcher.watchCount)
    }

    @Test
    fun openDaybook_singleProcessDoesNotWatch() {
        val watcher = RecordingWatcherFactory()
        DaybookRegistry.openDaybook(
            dir(),
            StoreSchema,
            configure = {},
            watcherFactory = watcher,
            directorySync = platformDirectorySync(),
        )
        assertEquals(0, watcher.watchCount)
    }

    @Test
    fun openDaybook_sharesInstanceWithPlainOpen() {
        val injected = DaybookRegistry.openDaybook(
            dir(),
            StoreSchema,
            configure = {},
            watcherFactory = RecordingWatcherFactory(),
            directorySync = platformDirectorySync(),
        )
        assertSame(injected, Daybook.open(dir(), StoreSchema))
    }

    @Test
    fun openDaybook_injectedDirectorySyncIsWiredIn() {
        val sync = RecordingDirectorySync()
        DaybookRegistry.openDaybook(
            dir(),
            StoreSchema,
            configure = { durability = Durability.SYNC },
            watcherFactory = RecordingWatcherFactory(),
            directorySync = sync,
        )
        // SYNC のオープンはジャーナルファイル名の永続化にディレクトリ fsync を要求する。
        // ここで注入が呼ばれていれば結線されている
        assertTrue(sync.syncCount > 0)
    }

    // --- getOrOpenStore（SharedPreferences 互換 API + onCreate） ---

    @Test
    fun getOrOpenStore_sharesStoreWithDaybookApi() {
        val store = DaybookRegistry.getOrOpenStore(
            dir(),
            "store",
            multiProcess = false,
            watcherFactory = RecordingWatcherFactory(),
            directorySync = platformDirectorySync(),
            onCreate = {},
        )
        val daybook = Daybook.open(dir(), StoreSchema)
        store.put("from-store", "value")
        assertEquals("value", daybook.getString("from-store", null))
        daybook.edit { putString("from-daybook", "value") }
        assertEquals("value", store.get("from-daybook"))
    }

    @Test
    fun getOrOpenStore_onCreateRunsOnlyOnCreation() {
        var created = 0
        fun open() = DaybookRegistry.getOrOpenStore(
            dir(),
            "store",
            multiProcess = false,
            watcherFactory = RecordingWatcherFactory(),
            directorySync = platformDirectorySync(),
            onCreate = { created++ },
        )
        val first = open()
        val second = open()
        assertSame(first, second)
        assertEquals(1, created)
    }

    @Test
    fun getOrOpenStore_onCreateSkippedWhenDaybookApiCreatedFirst() {
        Daybook.open(dir(), StoreSchema)
        var created = 0
        DaybookRegistry.getOrOpenStore(
            dir(),
            "store",
            multiProcess = false,
            watcherFactory = RecordingWatcherFactory(),
            directorySync = platformDirectorySync(),
            onCreate = { created++ },
        )
        assertEquals(0, created)
    }

    @Test
    fun getOrOpenStore_onCreateFailureClosesStoreAndDoesNotCache() {
        assertFailsWith<IoException> {
            DaybookRegistry.getOrOpenStore(
                dir(),
                "store",
                multiProcess = false,
                watcherFactory = RecordingWatcherFactory(),
                directorySync = platformDirectorySync(),
                onCreate = { throw IoException("import failed") },
            )
        }
        // キャッシュに載っていないので、次のオープンは新規生成として onCreate をやり直す。
        // 失敗時にストアが閉じられていなければ、この再オープンが多重オープンになる
        var created = 0
        DaybookRegistry.getOrOpenStore(
            dir(),
            "store",
            multiProcess = false,
            watcherFactory = RecordingWatcherFactory(),
            directorySync = platformDirectorySync(),
            onCreate = { created++ },
        )
        assertEquals(1, created)
    }

    @Test
    fun getOrOpenStore_durabilityMismatchWithSyncDaybookThrows() {
        Daybook.open(dir(), StoreSchema) { durability = Durability.SYNC }
        assertFailsWith<IllegalArgumentException> {
            DaybookRegistry.getOrOpenStore(
                dir(),
                "store",
                multiProcess = false,
                watcherFactory = RecordingWatcherFactory(),
                directorySync = platformDirectorySync(),
                onCreate = {},
            )
        }
    }

    // --- スキーマの採用（SharedPreferences 互換 API が先行するケース） ---

    /** スキーマなしで生成されたストアに、最初のスキーマ付き open がスキーマを採用させる。 */
    @Test
    fun schemaAdoption_prefsApiFirstThenSchemaOpenSharesStore() {
        val store = DaybookRegistry.getOrOpenStore(
            dir(),
            "store",
            multiProcess = false,
            watcherFactory = RecordingWatcherFactory(),
            directorySync = platformDirectorySync(),
            onCreate = {},
        )
        val daybook = Daybook.open(dir(), StoreSchema)
        store.put("key", "value")
        assertEquals("value", daybook.getString("key", null))
        // 採用後は別スキーマでの open が同一性検査に落ちる
        val another = object : DaybookSchema("store") {}
        assertFailsWith<IllegalArgumentException> {
            Daybook.open(dir(), another)
        }
    }

    // --- withStore ---

    @Test
    fun withStore_usesCachedStoreWhenOpen() {
        val daybook = Daybook.open(dir(), StoreSchema)
        DaybookRegistry.withStore(dir(), "store", platformDirectorySync()) { store ->
            store.put("key", "value")
        }
        assertEquals("value", daybook.getString("key", null))
    }

    @Test
    fun withStore_opensAndClosesTemporarilyWhenNotCached() {
        DaybookRegistry.withStore(dir(), "store", platformDirectorySync()) { store ->
            store.put("key", "value")
        }
        // 一時ストアは閉じられている（キャッシュに残っていれば同じインスタンスが返り、
        // 閉じ忘れならこのオープンが多重オープンになる）
        assertEquals("value", Daybook.open(dir(), StoreSchema).getString("key", null))
    }

    @Test
    fun withStore_invalidNameThrows() {
        assertFailsWith<IllegalArgumentException> {
            DaybookRegistry.withStore(dir(), "", platformDirectorySync()) {}
        }
        assertFailsWith<IllegalArgumentException> {
            DaybookRegistry.withStore(dir(), "a/b", platformDirectorySync()) {}
        }
    }

    @Test
    fun withStore_propagatesBodyResultAndFailure() {
        assertEquals(42, DaybookRegistry.withStore(dir(), "store", platformDirectorySync()) { 42 })
        assertFalse(
            runCatching {
                DaybookRegistry.withStore<Unit>(dir(), "store", platformDirectorySync()) {
                    throw IoException("boom")
                }
            }.isSuccess,
        )
        // body の失敗でも一時ストアは閉じられている
        Daybook.open(dir(), StoreSchema)
    }
}
