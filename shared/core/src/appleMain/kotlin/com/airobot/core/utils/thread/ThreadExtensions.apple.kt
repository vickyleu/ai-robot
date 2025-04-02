@file:OptIn(UnsafeNumber::class, ExperimentalForeignApi::class)

package com.airobot.core.utils.thread

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.toKString
import platform.posix.pthread_getname_np
import platform.posix.pthread_self

actual fun getThreadName(): String {
    // 苹果中获取线程名称的方法
    val buf = nativeHeap.allocArray<ByteVar>(64)
    return if (pthread_getname_np(pthread_self(), buf, 64u) == 0) buf.toKString() else "unknown"
}