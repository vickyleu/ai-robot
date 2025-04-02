package com.airobot.core.utils.thread

actual fun getThreadName(): String {
    // js中获取线程名称的方法
    // 这里返回一个空字符串，表示没有线程名称
    return "main"
}