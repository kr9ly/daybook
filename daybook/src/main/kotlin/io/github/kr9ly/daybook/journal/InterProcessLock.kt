package io.github.kr9ly.daybook.journal

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * プロセス間の書き込み排他。
 *
 * ロック対象は世代とともに rename されるジャーナルファイルではなく、固定名の
 * ロックファイル（実装は [FileInterProcessLock]）。プロセス内の排他は
 * KvStore の writeLock が担うため、この層は常に「プロセス内ロックの内側」で使われる。
 *
 * FileLock は同一 JVM 内での重複取得が OverlappingFileLockException になるため、
 * 「1 JVM 内で 2 store を開いて 2 プロセスを模す」JVM テストはこのインターフェースに
 * 共有ロックの偽物を注入する。実 FileLock のプロセス間排他は Instrumentation テストで検証する。
 */
internal interface InterProcessLock : Closeable {
    /** ロックを取得して [body] を実行する。ネスト（再入）は想定しない。 */
    fun <T> withLock(body: () -> T): T
}

/** 固定名のロックファイルへの [java.nio.channels.FileLock] による実装。 */
internal class FileInterProcessLock(file: File) : InterProcessLock {
    private val channel = RandomAccessFile(file, "rw").channel

    override fun <T> withLock(body: () -> T): T {
        val lock = channel.lock()
        try {
            return body()
        } finally {
            lock.release()
        }
    }

    override fun close() {
        channel.close()
    }
}
