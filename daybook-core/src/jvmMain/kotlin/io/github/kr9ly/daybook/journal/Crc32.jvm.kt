package io.github.kr9ly.daybook.journal

import java.util.zip.CRC32

internal actual class Crc32 {

    private val delegate = CRC32()

    actual fun update(bytes: ByteArray, offset: Int, length: Int) {
        delegate.update(bytes, offset, length)
    }

    actual val value: Long
        get() = delegate.value
}
