package io.github.kr9ly.daybook.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilePathTest {

    @Test
    fun resolve_joinsWithSlash() {
        assertEquals("dir/child", FilePath("dir").resolve("child").path)
    }

    @Test
    fun resolve_fromEmptyPath_isChildItself() {
        assertEquals("child", FilePath("").resolve("child").path)
    }

    @Test
    fun name_isLastSegment() {
        assertEquals("c.txt", FilePath("a/b/c.txt").name)
        assertEquals("plain", FilePath("plain").name)
    }

    @Test
    fun name_handlesBackslashSeparators() {
        // Windows の JVM では File.path 由来のバックスラッシュ区切りが混ざりうる
        assertEquals("c.txt", FilePath("a\\b\\c.txt").name)
        assertEquals("c.txt", FilePath("a\\b/c.txt").name)
    }

    @Test
    fun equality_isByPathString() {
        assertEquals(FilePath("a/b"), FilePath("a/b"))
        assertEquals(FilePath("a/b").hashCode(), FilePath("a/b").hashCode())
        assertFalse(FilePath("a/b").equals("a/b"))
        assertFalse(FilePath("a/b") == FilePath("a/c"))
    }

    @Test
    fun toString_isPath() {
        assertTrue(FilePath("a/b").toString() == "a/b")
    }
}
