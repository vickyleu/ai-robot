@file:OptIn(ExperimentalForeignApi::class)

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.nativeHeap
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock.System
import voice.core.app.initSignalHandler
import voice.core.app.runVoiceDemo

fun main(args: Array<String>) {
    initSignalHandler()
    runVoiceDemo()
}