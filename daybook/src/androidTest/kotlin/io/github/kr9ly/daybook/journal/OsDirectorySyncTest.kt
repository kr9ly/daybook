package io.github.kr9ly.daybook.journal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kr9ly.daybook.kv.KvStore
import io.github.kr9ly.daybook.kv.open
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

/**
 * OsDirectorySync の実機検証。
 *
 * android.system.Os は JVM ユニットテストで実行できないため Kover の対象外にしており、
 * その代わりにここで Android ランタイム上の実挙動を検証する。
 * fsync が「電源断まで耐えた」ことのテストは原理的にできないため、守備範囲は
 * 「実機のディレクトリ fd に対して正しく発行・完走できる」まで。
 */
@RunWith(AndroidJUnit4::class)
class OsDirectorySyncTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun newDirectory(name: String): File =
        File(context.cacheDir, name).apply {
            deleteRecursively()
            check(mkdirs()) { "failed to create $this" }
        }

    @Test
    fun sync_completesOnRealDirectory() {
        val dir = newDirectory("os-directory-sync")
        File(dir, "entry").writeBytes(byteArrayOf(1))
        OsDirectorySync().sync(dir) // 例外なく完走すること
    }

    @Test
    fun sync_missingDirectory_throwsNormalizedIOException() {
        val missing = File(context.cacheDir, "does-not-exist")
        missing.deleteRecursively()
        // ErrnoException が契約どおり IOException に正規化されること
        assertThrows(IOException::class.java) {
            OsDirectorySync().sync(missing)
        }
    }

    @Test
    fun defaultDirectorySync_onAndroidRuntime_isOsImplementation() {
        assertTrue(defaultDirectorySync() is OsDirectorySync)
    }

    @Test
    fun kvStoreSyncMode_endToEnd_usesOsDirectorySync() {
        val dir = newDirectory("kv-store-sync-mode")
        // デフォルトの DirectorySync（実機では Os 実装）でオープン + compaction まで通す
        KvStore.open(dir, "store", syncMode = SyncMode.SYNC, compactionThreshold = 1).use { store ->
            store.put("key", "value")
        }
        KvStore.open(dir, "store", syncMode = SyncMode.SYNC).use { store ->
            assertEquals("value", store.get("key"))
        }
    }
}
