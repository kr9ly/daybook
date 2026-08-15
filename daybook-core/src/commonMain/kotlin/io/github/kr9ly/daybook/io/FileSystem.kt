package io.github.kr9ly.daybook.io

/**
 * ジャーナル層が必要とする最小のファイルシステム操作。
 *
 * kotlinx-io 等の汎用抽象を採らず自前の expect/actual に留める裁定（KMP-2.0.md）に基づく。
 * 必要なのは「全読み・ディレクトリ操作（作成・列挙・削除・rename）」だけで、
 * fsync 付き書き込み・位置指定読みはそれぞれ FileSink / PositionalFileReader が担う。
 */

/** ファイルの全バイトを読む。ファイルが存在しなければ空配列。 */
internal expect fun readFileOrEmpty(path: FilePath): ByteArray

/** ファイルが存在するか。 */
internal expect fun fileExists(path: FilePath): Boolean

/** 空ファイルを作成する。既に存在する場合は何もしない（マイグレーションマーカー用）。 */
internal expect fun createEmptyFile(path: FilePath)

/** ディレクトリを（親ごと）作成する。既に存在する場合は何もしない。 */
internal expect fun mkdirs(path: FilePath)

/** ディレクトリ直下のエントリ名を列挙する。列挙できない場合は null。 */
internal expect fun listDirectory(path: FilePath): List<String>?

/** ファイルを削除する。存在しない場合は何もしない。 */
internal expect fun deleteFile(path: FilePath)

/** ファイルを rename する。成功したら true。 */
internal expect fun renameFile(from: FilePath, to: FilePath): Boolean

/**
 * パスを絶対・正規化形（`.` / `..` の解決）にする。シンボリックリンクは解決しない。
 * DaybookRegistry が「同じディレクトリ」の同定に使う。
 */
internal expect fun absoluteNormalizedPath(path: String): String
