package io.github.kr9ly.daybook.journal

import io.github.kr9ly.daybook.io.FilePath
import io.github.kr9ly.daybook.io.IoException
import io.github.kr9ly.daybook.io.createTempDirectory
import io.github.kr9ly.daybook.io.fileExists
import io.github.kr9ly.daybook.io.listDirectory
import io.github.kr9ly.daybook.io.writeFileBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JournalDirectoryTest {

    private val tmp = createTempDirectory()

    private fun directory() = JournalDirectory(tmp, "store")

    private fun createFile(name: String): FilePath =
        tmp.resolve(name).also { writeFileBytes(it, byteArrayOf(1)) }

    @Test
    fun emptyDirectory_startsAtFirstGeneration() {
        assertEquals(JournalDirectory.FIRST_GENERATION, directory().resolveCurrentGeneration())
    }

    @Test
    fun missingDirectory_isCreated() {
        val nested = tmp.resolve("nested/dir")
        assertEquals(
            JournalDirectory.FIRST_GENERATION,
            JournalDirectory(nested, "store").resolveCurrentGeneration(),
        )
        assertNotNull(listDirectory(nested)) // ディレクトリとして列挙できる
    }

    @Test
    fun unrelatedFiles_areIgnoredAndLeftUntouched() {
        val unrelated = listOf(
            "other.1.journal", // 名前不一致
            "store.x.journal", // 世代番号が数値でない
            "store.0.journal", // 世代番号は 1 始まり
            "store.-1.journal", // 負の世代番号
            "store.journal", // 世代番号なし（プレフィックス+サフィックスより短い）
            "store.1.journal.bak", // サフィックス不一致
        ).map { createFile(it) }
        assertEquals(JournalDirectory.FIRST_GENERATION, directory().resolveCurrentGeneration())
        unrelated.forEach { assertTrue(fileExists(it), "${it.name} should survive") }
    }

    @Test
    fun highestGenerationWins_tempsAreCleaned_oldGenerationsSurviveResolve() {
        val gen1 = createFile("store.1.journal")
        val gen3 = createFile("store.3.journal")
        val temp = createFile("store.2.journal.tmp")
        assertEquals(3L, directory().resolveCurrentGeneration())
        assertFalse(fileExists(temp))
        // 旧世代の削除は resolve の責務ではない（最新世代のオープン成功後に呼び出し側が行う）
        assertTrue(fileExists(gen1))
        directory().deleteOlderThan(3L)
        assertFalse(fileExists(gen1))
        assertTrue(fileExists(gen3))
    }

    @Test
    fun tempOnly_highestIsAdoptedAndOthersAreDeleted() {
        createFile("store.2.journal.tmp")
        createFile("store.5.journal.tmp")
        assertEquals(5L, directory().resolveCurrentGeneration())
        assertTrue(fileExists(tmp.resolve("store.5.journal")))
        assertFalse(fileExists(tmp.resolve("store.5.journal.tmp")))
        assertFalse(fileExists(tmp.resolve("store.2.journal.tmp")))
    }

    @Test
    fun commitWithoutTemp_throws() {
        assertFailsWith<IoException> {
            directory().commit(1L)
        }
    }

    @Test
    fun pathThatIsNotADirectory_throws() {
        val file = createFile("regular-file")
        assertFailsWith<IoException> {
            JournalDirectory(file, "store").resolveCurrentGeneration()
        }
    }
}
