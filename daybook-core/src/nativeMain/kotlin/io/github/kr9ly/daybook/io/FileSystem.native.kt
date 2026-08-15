@file:OptIn(ExperimentalForeignApi::class)

package io.github.kr9ly.daybook.io

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.EEXIST
import platform.posix.ENOENT
import platform.posix.F_OK
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_RDONLY
import platform.posix.O_WRONLY
import platform.posix.PATH_MAX
import platform.posix.access
import platform.posix.close
import platform.posix.closedir
import platform.posix.errno
import platform.posix.fstat
import platform.posix.getcwd
import platform.posix.open
import platform.posix.opendir
import platform.posix.read
import platform.posix.readdir
import platform.posix.rename
import platform.posix.stat
import platform.posix.unlink

/** 所有者・グループ・その他の読み書き（0666）。実効値は umask で絞られる。 */
internal const val FILE_MODE_RW: Int = 0b110_110_110

/** 所有者・グループ・その他の読み書き実行（0777）。実効値は umask で絞られる。 */
internal const val DIRECTORY_MODE_RWX: Int = 0b111_111_111

internal actual fun readFileOrEmpty(path: FilePath): ByteArray {
    val fd = open(path.path, O_RDONLY)
    if (fd < 0) {
        if (errno == ENOENT) return ByteArray(0)
        throw IoException("cannot open for read: ${path.path} (errno=$errno)")
    }
    try {
        val size = memScoped {
            val st = alloc<stat>()
            if (fstat(fd, st.ptr) != 0) throw IoException("fstat failed: ${path.path} (errno=$errno)")
            st.st_size
        }
        if (size == 0L) return ByteArray(0)
        val bytes = ByteArray(size.toInt())
        var done = 0
        bytes.usePinned { pinned ->
            while (done < bytes.size) {
                val n = read(fd, pinned.addressOf(done), (bytes.size - done).convert()).toInt()
                if (n < 0) throw IoException("read failed: ${path.path} (errno=$errno)")
                // 読み取り中に他プロセスが truncate した場合は読めた分まで
                if (n == 0) break
                done += n
            }
        }
        return if (done == bytes.size) bytes else bytes.copyOf(done)
    } finally {
        close(fd)
    }
}

internal actual fun fileExists(path: FilePath): Boolean = access(path.path, F_OK) == 0

internal actual fun createEmptyFile(path: FilePath) {
    val fd = open(path.path, O_WRONLY or O_CREAT or O_EXCL, FILE_MODE_RW)
    if (fd < 0) {
        if (errno == EEXIST) return
        throw IoException("cannot create file: ${path.path} (errno=$errno)")
    }
    close(fd)
}

internal actual fun mkdirs(path: FilePath) {
    val normalized = absoluteNormalizedPath(path.path)
    var current = ""
    for (segment in normalized.split('/')) {
        if (segment.isEmpty()) continue
        current += "/$segment"
        // 既存（EEXIST）や権限エラーは JVM actual（File.mkdirs の戻り値無視）と同様に黙殺する。
        // 作成に失敗していれば後続のファイル操作が ENOENT で失敗して表面化する
        posixMkdir(current, DIRECTORY_MODE_RWX)
    }
}

internal actual fun listDirectory(path: FilePath): List<String>? {
    val dir = opendir(path.path) ?: return null
    try {
        val names = mutableListOf<String>()
        while (true) {
            val entry = readdir(dir) ?: break
            val name = entry.pointed.d_name.toKString()
            if (name != "." && name != "..") names.add(name)
        }
        return names
    } finally {
        closedir(dir)
    }
}

internal actual fun deleteFile(path: FilePath) {
    unlink(path.path)
}

internal actual fun renameFile(from: FilePath, to: FilePath): Boolean =
    rename(from.path, to.path) == 0

internal actual fun absoluteNormalizedPath(path: String): String {
    val absolute = if (path.startsWith("/")) path else "${currentWorkingDirectory()}/$path"
    val segments = ArrayDeque<String>()
    for (segment in absolute.split('/')) {
        when (segment) {
            "", "." -> {}
            ".." -> segments.removeLastOrNull()
            else -> segments.addLast(segment)
        }
    }
    return "/" + segments.joinToString("/")
}

private fun currentWorkingDirectory(): String = memScoped {
    val buffer = allocArray<ByteVar>(PATH_MAX)
    getcwd(buffer, PATH_MAX.convert())
        ?: throw IoException("getcwd failed (errno=$errno)")
    buffer.toKString()
}

/**
 * mkdir(2) の呼び出し。mode_t の cinterop 上のビット幅がプラットフォームで分かれる
 * （Linux は UInt、Darwin は UShort）ため expect で吸収する。
 */
internal expect fun posixMkdir(path: String, mode: Int): Int
