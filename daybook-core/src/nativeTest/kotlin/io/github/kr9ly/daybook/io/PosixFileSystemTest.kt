package io.github.kr9ly.daybook.io

import io.github.kr9ly.daybook.journal.FileSink
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** POSIX actual のファイルシステム操作のテスト。契約は commonMain の expect KDoc が正。 */
class PosixFileSystemTest {

    @Test
    fun readFileOrEmpty_missingFile_returnsEmpty() {
        val dir = createTempDirectory()
        assertContentEquals(ByteArray(0), readFileOrEmpty(dir.resolve("missing")))
    }

    @Test
    fun readFileOrEmpty_roundTrip() {
        val dir = createTempDirectory()
        val file = dir.resolve("data")
        val bytes = ByteArray(70_000) { it.toByte() }
        FileSink(file).use { sink -> sink.write(bytes) }
        assertContentEquals(bytes, readFileOrEmpty(file))
    }

    @Test
    fun fileExists_reflectsCreation() {
        val dir = createTempDirectory()
        val file = dir.resolve("marker")
        assertFalse(fileExists(file))
        createEmptyFile(file)
        assertTrue(fileExists(file))
    }

    @Test
    fun createEmptyFile_existingFile_keepsContent() {
        val dir = createTempDirectory()
        val file = dir.resolve("marker")
        FileSink(file).use { sink -> sink.write(byteArrayOf(1, 2, 3)) }
        createEmptyFile(file)
        assertContentEquals(byteArrayOf(1, 2, 3), readFileOrEmpty(file))
    }

    @Test
    fun mkdirs_createsNestedDirectories_andIsIdempotent() {
        val dir = createTempDirectory()
        val nested = dir.resolve("a/b/c")
        mkdirs(nested)
        mkdirs(nested)
        assertEquals(emptyList(), listDirectory(nested))
    }

    @Test
    fun listDirectory_returnsEntryNames_withoutDotEntries() {
        val dir = createTempDirectory()
        createEmptyFile(dir.resolve("one"))
        createEmptyFile(dir.resolve("two"))
        mkdirs(dir.resolve("sub"))
        assertEquals(listOf("one", "sub", "two"), listDirectory(dir)?.sorted())
    }

    @Test
    fun listDirectory_missingDirectory_returnsNull() {
        val dir = createTempDirectory()
        assertNull(listDirectory(dir.resolve("missing")))
    }

    @Test
    fun deleteFile_removesFile_andIgnoresMissing() {
        val dir = createTempDirectory()
        val file = dir.resolve("victim")
        createEmptyFile(file)
        deleteFile(file)
        assertFalse(fileExists(file))
        deleteFile(file)
    }

    @Test
    fun renameFile_movesFile_andOverwritesTarget() {
        val dir = createTempDirectory()
        val from = dir.resolve("from")
        val to = dir.resolve("to")
        FileSink(from).use { sink -> sink.write(byteArrayOf(7)) }
        FileSink(to).use { sink -> sink.write(byteArrayOf(9)) }
        assertTrue(renameFile(from, to))
        assertFalse(fileExists(from))
        assertContentEquals(byteArrayOf(7), readFileOrEmpty(to))
    }

    @Test
    fun renameFile_missingSource_returnsFalse() {
        val dir = createTempDirectory()
        assertFalse(renameFile(dir.resolve("missing"), dir.resolve("to")))
    }

    @Test
    fun absoluteNormalizedPath_resolvesDotSegments() {
        assertEquals("/a/c", absoluteNormalizedPath("/a/./b/../c"))
        assertEquals("/a/b", absoluteNormalizedPath("/a//b/"))
        assertEquals("/", absoluteNormalizedPath("/.."))
    }

    @Test
    fun absoluteNormalizedPath_relativePath_isAnchoredToCwd() {
        val normalized = absoluteNormalizedPath("relative/dir")
        assertTrue(normalized.startsWith("/"))
        assertTrue(normalized.endsWith("/relative/dir"))
    }
}
