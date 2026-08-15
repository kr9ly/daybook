package io.github.kr9ly.daybook.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class JournalDirectoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun directory() = JournalDirectory(tmp.root, "store")

    private fun createFile(name: String): File =
        File(tmp.root, name).also { it.writeBytes(byteArrayOf(1)) }

    @Test
    fun emptyDirectory_startsAtFirstGeneration() {
        assertEquals(JournalDirectory.FIRST_GENERATION, directory().resolveCurrentGeneration())
    }

    @Test
    fun missingDirectory_isCreated() {
        val nested = File(tmp.root, "nested/dir")
        assertEquals(
            JournalDirectory.FIRST_GENERATION,
            JournalDirectory(nested, "store").resolveCurrentGeneration(),
        )
        assertTrue(nested.isDirectory)
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
        unrelated.forEach { assertTrue("${it.name} should survive", it.exists()) }
    }

    @Test
    fun highestGenerationWins_tempsAreCleaned_oldGenerationsSurviveResolve() {
        val gen1 = createFile("store.1.journal")
        val gen3 = createFile("store.3.journal")
        val temp = createFile("store.2.journal.tmp")
        assertEquals(3L, directory().resolveCurrentGeneration())
        assertFalse(temp.exists())
        // 旧世代の削除は resolve の責務ではない（最新世代のオープン成功後に呼び出し側が行う）
        assertTrue(gen1.exists())
        directory().deleteOlderThan(3L)
        assertFalse(gen1.exists())
        assertTrue(gen3.exists())
    }

    @Test
    fun tempOnly_highestIsAdoptedAndOthersAreDeleted() {
        createFile("store.2.journal.tmp")
        createFile("store.5.journal.tmp")
        assertEquals(5L, directory().resolveCurrentGeneration())
        assertTrue(File(tmp.root, "store.5.journal").exists())
        assertFalse(File(tmp.root, "store.5.journal.tmp").exists())
        assertFalse(File(tmp.root, "store.2.journal.tmp").exists())
    }

    @Test
    fun commitWithoutTemp_throws() {
        assertThrows(IOException::class.java) {
            directory().commit(1L)
        }
    }

    @Test
    fun pathThatIsNotADirectory_throws() {
        val file = createFile("regular-file")
        assertThrows(IOException::class.java) {
            JournalDirectory(file, "store").resolveCurrentGeneration()
        }
    }
}
