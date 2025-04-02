package com.airobot.core.utils.thread

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

val globalScope = CoroutineScope(Dispatchers.Default)

expect fun getThreadName(): String