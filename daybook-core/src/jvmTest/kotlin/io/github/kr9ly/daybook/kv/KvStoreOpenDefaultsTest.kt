package io.github.kr9ly.daybook.kv

import io.github.kr9ly.daybook.io.toFilePath
import io.github.kr9ly.daybook.journal.JournalFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 本体 API（FilePath 受け）をデフォルト引数のまま呼ぶ経路のテスト。
 *
 * 他のテストは File 受けのテスト用アダプタ経由で全引数を明示渡しするため、
 * 本体側のデフォルト引数式（FileSink / platformDirectorySync / 既定ストア名）が
 * ここでしか実行されない。
 */
class KvStoreOpenDefaultsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun open_withDefaults_persistsUnderDefaultName() {
        KvStore.open(tmp.root.toFilePath()).use { store ->
            store.put("key", "value")
        }
        // 既定名 daybook の世代ファイルとして永続化されている
        KvStore.open(tmp.root.toFilePath()).use { store ->
            assertEquals("value", store.get("key"))
        }
    }

    @Test
    fun open_multiProcessWithoutWatcherFactory_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            KvStore.open(tmp.root.toFilePath(), multiProcess = true)
        }
    }

    @Test
    fun journalFileOpen_withDefaults_appendsAndReplays() {
        val file = tmp.root.resolve("defaults.journal").toFilePath()
        JournalFile.open(file).use { it.append(byteArrayOf(1, 2, 3)) }
        JournalFile.open(file).use { journal ->
            assertEquals(1, journal.replayedRecords.size)
            assertArrayEquals(byteArrayOf(1, 2, 3), journal.replayedRecords[0])
        }
    }
}
