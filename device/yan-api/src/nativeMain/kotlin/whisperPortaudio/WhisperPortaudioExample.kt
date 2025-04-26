package com.airobot.device.yanapi.whisperPortaudio

import com.airobot.core.utils.thread.globalScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import whisperPortaudio.WhisperPortaudioRecognizer

// 示例：如何在外部调用 WhisperPortaudioRecognizer
fun whisperPortaudio() {
    // 创建识别器实例
    val recognizer = WhisperPortaudioRecognizer()
    // 初始化（可指定模型路径、采样率、通道数）
    val initSuccess = recognizer.initialize()
    if (!initSuccess) {
        println("Whisper 初始化失败")
        return
    }
    // 启动识别
    recognizer.startRecognition()
    // 使用runBlocking来阻塞主线程，确保程序不会立即退出
    runBlocking {
        // 监听识别状态和结果
        val stateJob = launch {
            recognizer.recognitionState.collect { state ->
                println("当前识别状态: $state")
            }
        }

        val resultJob = launch {
            recognizer.recognitionResult.collect { result ->
                if (result != null) {
                    println("识别结果: $result")
                }
            }
        }

        // 在这个协程内运行计时器
        launch {
            println("开始等待识别...")
            // 假设运行一段时间后停止识别
            var time = 0
            while (time < 150_000) {
                delay(1000)
                println("运行时间: $time")
                time += 1000
            }

            // 停止识别
            recognizer.stopRecognition()
            // 释放资源
            recognizer.release()
            // 取消监听协程
            stateJob.cancel()
            resultJob.cancel()

            // 完成后退出runBlocking
        }

        // 等待所有协程完成
        // 如果你想让程序在10秒后一定终止，可以使用withTimeout
        withTimeout(12000L) {
            // 空的，只是为了确保超时后会退出
        }
    }

    println("识别流程结束。")
}