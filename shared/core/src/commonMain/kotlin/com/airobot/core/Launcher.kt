package com.airobot.core
import kotlinx.coroutines.*

interface Launcher {

    @OptIn(DelicateCoroutinesApi::class)
    fun startApplication()

}