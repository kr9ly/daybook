package io.github.kr9ly.daybook.io

import java.nio.file.Files

/** java.nio.file.Files による一時ディレクトリ作成。 */
internal actual fun createTempDirectory(): FilePath =
    FilePath(Files.createTempDirectory("daybook-jvm-test").toString())
