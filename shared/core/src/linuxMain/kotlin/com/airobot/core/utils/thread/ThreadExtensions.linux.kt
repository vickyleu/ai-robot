@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class, UnsafeNumber::class)

package com.airobot.core.utils.thread

import kotlinx.cinterop.*
import com.airobot.pthread.*
import kotlin.experimental.ExperimentalNativeApi

@OptIn(UnsafeNumber::class)
actual fun getThreadName() = memScoped {
    val buf = allocArray<ByteVar>(64)
    if (pthread_getname_np(pthread_self(), buf, 64u) == 0) buf.toKString() else "unknown"
}