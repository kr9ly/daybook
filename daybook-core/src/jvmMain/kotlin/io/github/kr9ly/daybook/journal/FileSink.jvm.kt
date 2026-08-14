package io.github.kr9ly.daybook.journal

import io.github.kr9ly.daybook.io.FilePath
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

public actual class FileSink actual constructor(path: FilePath) : JournalSink {

    private val channel = FileChannel.open(
        File(path.path).toPath(),
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
        StandardOpenOption.APPEND,
    )

    actual override fun write(data: ByteArray) {
        val buf = ByteBuffer.wrap(data)
        while (buf.hasRemaining()) {
            channel.write(buf)
        }
    }

    actual override fun force() {
        channel.force(true)
    }

    actual override fun truncate(size: Long) {
        channel.truncate(size)
    }

    actual override fun close() {
        channel.close()
    }
}
