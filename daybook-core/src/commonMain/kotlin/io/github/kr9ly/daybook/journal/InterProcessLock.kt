package io.github.kr9ly.daybook.journal

import io.github.kr9ly.daybook.internal.DaybookInternalApi
import io.github.kr9ly.daybook.io.FilePath

/**
 * プロセス間の書き込み排他。
 *
 * ロック対象は世代とともに rename されるジャーナルファイルではなく、固定名の
 * ロックファイル（実装は [FileInterProcessLock]）。プロセス内の排他は
 * KvStore の writeLock が担うため、この層は常に「プロセス内ロックの内側」で使われる。
 *
 * JVM の FileLock は同一 JVM 内での重複取得が OverlappingFileLockException になるため、
 * 「1 JVM 内で 2 store を開いて 2 プロセスを模す」JVM テストはこのインターフェースに
 * 共有ロックの偽物を注入する。実 FileLock のプロセス間排他は Instrumentation テストで検証する。
 */
@DaybookInternalApi
public interface InterProcessLock : AutoCloseable {
    /** ロックを取得して [body] を実行する。ネスト（再入）は想定しない。 */
    public fun <T> withLock(body: () -> T): T
}

/** 固定名のロックファイルへの OS ファイルロックによる実装。JVM actual は FileLock。 */
internal expect class FileInterProcessLock(file: FilePath) : InterProcessLock {
    override fun <T> withLock(body: () -> T): T
    override fun close()
}
