package io.github.kr9ly.daybook.io

/**
 * テスト用の一時ディレクトリを作る。
 *
 * 後始末はしない: テストのファイル残骸は OS の一時領域掃除に任せる
 * （JUnit4 の TemporaryFolder 相当の仕組みを持ち込むほどの量ではない）。
 */
internal expect fun createTempDirectory(): FilePath
