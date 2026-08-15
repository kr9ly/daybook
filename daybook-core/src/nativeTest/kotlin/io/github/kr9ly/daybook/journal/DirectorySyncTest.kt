package io.github.kr9ly.daybook.journal

import io.github.kr9ly.daybook.io.IoException
import io.github.kr9ly.daybook.io.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DirectorySyncTest {

    @Test
    fun sync_existingDirectory_succeeds() {
        platformDirectorySync().sync(createTempDirectory())
    }

    @Test
    fun sync_missingDirectory_throwsIoException() {
        val dir = createTempDirectory()
        assertFailsWith<IoException> {
            platformDirectorySync().sync(dir.resolve("missing"))
        }
    }
}
