package voskPortaudio

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * YanVoskPortaudioSpeechRecognizer 使用示例
 */
fun portAudio() = runBlocking {
    val recognizer = YanVoskPortaudioSpeechRecognizer()
    println("初始化识别器...")
    val initSuccess = recognizer.initialize()
    if (!initSuccess) {
        println("初始化失败！")
        return@runBlocking
    }
    println("初始化成功，开始语音识别...")
    recognizer.startRecognition()

    // 启动一个协程监听识别结果流
    val job = launch {
        recognizer.recognitionResult.collectLatest { result ->
            result?.let {
                println("识别结果: $it")
            }
        }
    }

    // 主流程等待一段时间（如30秒），实际可根据需求调整
    delay(30000)
    println("停止识别...")
    recognizer.stopRecognition()
    recognizer.release()
    job.cancelAndJoin()
    println("识别流程结束。")
}