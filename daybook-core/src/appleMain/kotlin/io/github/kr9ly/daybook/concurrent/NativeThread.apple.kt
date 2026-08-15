@file:OptIn(ExperimentalForeignApi::class)

package io.github.kr9ly.daybook.concurrent

import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.posix.pthread_create
import platform.posix.pthread_detach
import platform.posix.pthread_tVar

internal actual fun createDetachedPthread(
    routine: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>,
    arg: COpaquePointer?,
): Int = memScoped {
    val thread = alloc<pthread_tVar>()
    val rc = pthread_create(thread.ptr, null, routine, arg)
    if (rc == 0) pthread_detach(thread.value)
    rc
}
