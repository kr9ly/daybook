@file:OptIn(ExperimentalForeignApi::class)

package io.github.kr9ly.daybook.journal

import io.github.kr9ly.daybook.io.FILE_MODE_RW
import io.github.kr9ly.daybook.io.FilePath
import io.github.kr9ly.daybook.io.IoException
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.LOCK_EX
import platform.posix.LOCK_UN
import platform.posix.O_CREAT
import platform.posix.O_RDWR
import platform.posix.close
import platform.posix.errno
import platform.posix.open

/**
 * flock(2) による実装（KMP-2.0.md の POSIX actual 裁定）。
 *
 * flock のロックは open file description 単位のため、同一プロセス内でも別 open どうしは
 * 排他される。JVM の fcntl レコードロック（FileLock）とはロック族が異なるが、
 * Native プロセスと JVM プロセスが同じストアディレクトリを共有する構成は存在しない
 * （iOS は全 Native、デスクトップ / Android は全 JVM）ため相互運用の問題はない。
 */
internal actual class FileInterProcessLock actual constructor(file: FilePath) : InterProcessLock {

    private val fd: Int = open(file.path, O_RDWR or O_CREAT, FILE_MODE_RW).also {
        if (it < 0) throw IoException("cannot open lock file: ${file.path} (errno=$errno)")
    }

    actual override fun <T> withLock(body: () -> T): T {
        if (posixFlock(fd, LOCK_EX) != 0) throw IoException("flock failed (errno=$errno)")
        try {
            return body()
        } finally {
            posixFlock(fd, LOCK_UN)
        }
    }

    actual override fun close() {
        close(fd)
    }
}

/**
 * flock(2) の呼び出し。関数の cinterop 上の所在がプラットフォームで分かれる
 * （Linux は platform.linux、Darwin は platform.posix）ため expect で吸収する。
 */
internal expect fun posixFlock(fd: Int, operation: Int): Int
