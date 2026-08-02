package io.github.kr9ly.daybook.journal

import java.io.File
import java.io.IOException

/**
 * 世代番号つきジャーナルファイルの命名・現在世代の解決・世代の入れ替え。
 *
 * ファイル名は `<name>.<世代番号>.journal`、compaction が書き出す一時ファイルは
 * `<name>.<世代番号>.journal.tmp`。世代番号は 1 始まりで単調に増える。
 *
 * ディレクトリ fsync には頼らない（minSdk 21 では java.nio.file が使えない）。
 * 電源断で rename が巻き戻っても安全なのは、compaction が次の順序を守るため:
 *
 * 1. 一時ファイルの内容を fsync してから rename を発行する
 * 2. 旧世代の削除は rename より後に発行する
 *
 * この順序があると「世代ファイルがひとつもないのに一時ファイルがある」状態は、
 * 旧世代の削除がディスクに届いた後 — すなわち一時ファイルの内容が完全に永続化された
 * 後 — にしか起こりえない。このときに限り一時ファイルを正式な世代として採用する。
 *
 * 旧世代の削除はこのクラスでは行わない。呼び出し側が新世代のオープンに成功してから
 * [deleteOlderThan] を呼ぶ（最新世代が開けなかったとき、直前の世代を道連れにしないため）。
 */
internal class JournalDirectory(private val dir: File, private val name: String) {

    /** 世代番号に対応する正式なジャーナルファイル。 */
    fun fileFor(generation: Long): File = File(dir, "$name.$generation$GENERATION_SUFFIX")

    /** 世代番号に対応する compaction 書き出し先の一時ファイル。 */
    fun tempFor(generation: Long): File = File(dir, "$name.$generation$TEMP_SUFFIX")

    /**
     * 現在の世代番号を決定する。
     *
     * - 世代ファイルがあれば最大の世代番号（一時ファイルは全て未完了の残骸として削除）
     * - 世代ファイルがなく一時ファイルだけがあれば、最大世代の一時ファイルを採用して
     *   正式名へ rename（クラス KDoc の順序保証により内容の完全性が導ける）
     * - どちらもなければ [FIRST_GENERATION]
     */
    fun resolveCurrentGeneration(): Long {
        dir.mkdirs()
        val generations = scan(GENERATION_SUFFIX)
        val temps = scan(TEMP_SUFFIX)
        if (generations.isNotEmpty()) {
            temps.values.forEach { it.delete() }
            return generations.keys.max()
        }
        val adopted = temps.keys.maxOrNull() ?: return FIRST_GENERATION
        temps.filterKeys { it != adopted }.values.forEach { it.delete() }
        commit(adopted)
        return adopted
    }

    /** 一時ファイルを正式な世代ファイルへアトミック rename する。 */
    fun commit(generation: Long) {
        val temp = tempFor(generation)
        val file = fileFor(generation)
        if (!temp.renameTo(file)) {
            throw IOException("failed to rename ${temp.name} to ${file.name}")
        }
    }

    /** 指定世代より古い世代ファイルを削除する。 */
    fun deleteOlderThan(generation: Long) {
        scan(GENERATION_SUFFIX).filterKeys { it < generation }.values.forEach { it.delete() }
    }

    /** ディレクトリ内の `<name>.<世代番号><suffix>` を世代番号 → ファイルで列挙する。 */
    private fun scan(suffix: String): Map<Long, File> {
        val files = dir.listFiles() ?: throw IOException("cannot list directory: $dir")
        val prefix = "$name."
        return files.mapNotNull { file ->
            val fileName = file.name
            if (fileName.length <= prefix.length + suffix.length ||
                !fileName.startsWith(prefix) || !fileName.endsWith(suffix)
            ) {
                return@mapNotNull null
            }
            fileName.substring(prefix.length, fileName.length - suffix.length)
                .toLongOrNull()
                ?.takeIf { it >= FIRST_GENERATION }
                ?.let { it to file }
        }.toMap()
    }

    companion object {
        const val FIRST_GENERATION = 1L
        private const val GENERATION_SUFFIX = ".journal"
        private const val TEMP_SUFFIX = ".journal.tmp"
    }
}
