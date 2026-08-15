@file:OptIn(ExperimentalForeignApi::class)

package io.github.kr9ly.daybook.io

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import platform.posix.mkdir

internal actual fun posixMkdir(path: String, mode: Int): Int = mkdir(path, mode.convert())
