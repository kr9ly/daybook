package io.github.kr9ly.daybook.kv

import io.github.kr9ly.daybook.io.createTempDirectory
import io.github.kr9ly.daybook.journal.JournalFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 本体 API（FilePath 受け）をデフォルト引数のまま呼ぶ経路のテスト。
 *
 * 他のテストは全引数を明示渡しするため、
 * 本体側のデフォルト引数式（FileSink / platformDirectorySync / 既定ストア名）が
 * ここでしか実行されない。
 */
class KvStoreOpenDefaultsTest {

    @Test
    fun open_withDefaults_persistsUnderDefaultName() {
        val dir = createTempDirectory()
        KvStore.open(dir).use { store ->
            store.put("key", "value")
        }
        // 既定名 daybook の世代ファイルとして永続化されている
        KvStore.open(dir).use { store ->
            assertEquals("value", store.get("key"))
        }
    }

    @Test
    fun open_multiProcessWithoutWatcherFactory_throws() {
        val dir = createTempDirectory()
        assertFailsWith<IllegalArgumentException> {
            KvStore.open(dir, multiProcess = true)
        }
    }

    @Test
    fun journalFileOpen_withDefaults_appendsAndReplays() {
        val file = createTempDirectory().resolve("defaults.journal")
        JournalFile.open(file).use { it.append(byteArrayOf(1, 2, 3)) }
        JournalFile.open(file).use { journal ->
            assertEquals(1, journal.replayedRecords.size)
            assertContentEquals(byteArrayOf(1, 2, 3), journal.replayedRecords[0])
        }
    }
}
