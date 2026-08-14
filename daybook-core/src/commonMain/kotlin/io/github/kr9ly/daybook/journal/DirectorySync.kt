package io.github.kr9ly.daybook.journal

import io.github.kr9ly.daybook.internal.DaybookInternalApi
import io.github.kr9ly.daybook.io.FilePath

/**
 * ディレクトリエントリ（ファイルの作成・rename）の永続化。
 *
 * ファイル内容の fsync はファイル名の永続化を含まない。電源断をまたいで
 * 「作成したファイルが名前ごと消える」「rename が巻き戻る」を防ぐには、
 * 親ディレクトリ自体の fsync が必要になる。SYNC モードの耐久性契約はこれで完成する
 * （ASYNC モードは fsync の発行順序による安全性のみで、ディレクトリ fsync は使わない）。
 *
 * 実装が環境で分かれるのは機能差ではなく API の存在差:
 * java.nio.file は Android では API 26 以降、android.system.Os は Android 専用で
 * JVM ユニットテストから呼べない。どちらも同じ fsync(2) を発行する
 * （Android 実装は :daybook 側にあり、KvStore.open へ明示注入される）。
 */
@DaybookInternalApi
public fun interface DirectorySync {
    /** ディレクトリの内容（エントリ）を永続ストレージに同期する。 */
    public fun sync(directory: FilePath)
}

/**
 * 実行プラットフォームの標準 API による [DirectorySync]。JVM actual は java.nio.file。
 *
 * Android ランタイムでは java.nio.file が API 26+ のため使えない。:daybook が
 * Dalvik 判定でこの実装と android.system.Os 実装を選び分けて KvStore.open へ渡す。
 */
@DaybookInternalApi
public expect fun platformDirectorySync(): DirectorySync
