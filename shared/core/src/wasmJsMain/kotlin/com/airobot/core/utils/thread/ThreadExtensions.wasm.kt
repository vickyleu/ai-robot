package com.airobot.core.utils.thread

actual fun getThreadName(): String {
    // wasm中获取线程名称的方法
    return "main"
}