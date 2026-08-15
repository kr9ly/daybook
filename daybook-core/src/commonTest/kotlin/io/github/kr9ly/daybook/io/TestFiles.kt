package io.github.kr9ly.daybook.io

/** ファイルの全バイトを [bytes] で置き換える（存在しなければ作成）。破損注入・素材配置用。 */
internal expect fun writeFileBytes(path: FilePath, bytes: ByteArray)

/** ファイルの全バイトを読む。存在しなければ空配列（[readFileOrEmpty] の別名）。 */
internal fun readFileBytes(path: FilePath): ByteArray = readFileOrEmpty(path)

/** ファイル末尾にバイトを追記する（壊れたテールの再現用）。 */
internal fun appendFileBytes(path: FilePath, bytes: ByteArray) {
    writeFileBytes(path, readFileBytes(path) + bytes)
}

/** ディレクトリの書き込み可否を切り替える（書き込み失敗経路の再現用）。 */
internal expect fun setDirectoryWritable(path: FilePath, writable: Boolean)
