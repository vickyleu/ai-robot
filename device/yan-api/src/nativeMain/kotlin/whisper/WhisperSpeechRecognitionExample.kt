@file:OptIn(ExperimentalForeignApi::class)

package whisper

import com.airobot.core.utils.thread.globalScope
// Correct the import path based on the actual location of YanWhisperSpeechService
import whisper.YanWhisperSpeechService
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import platform.posix.exit
import platform.posix.printf

/**
 * Whisper语音识别示例程序
 *
 * 这个示例展示了如何使用YanWhisperSpeechService进行语音识别
 * 它会初始化语音服务，启动识别，并打印识别结果
 * (注意: Whisper可能不像Vosk那样直接控制麦克风音量)
 */
fun whisper(): Unit = runBlocking {
    printf("Whisper语音识别示例程序\n")
//    com.airobot.openccinterop.opencc_open()
    // 创建语音服务实例
    val speechService = YanWhisperSpeechService()

    // 初始化服务
    // 注意：确保模型路径正确，例如 "/usr/local/share/yanshee-model/whisper/ggml-base.en.bin"
    if (!speechService.initialize(modelPath = YanWhisperSpeechService.DEFAULT_WHISPER_MODEL)) { // Use the default model constant
        printf("初始化Whisper语音服务失败\n")
        exit(1)
    }

    // Whisper服务可能不直接管理音量，注释掉相关代码
    // printf("当前麦克风音量: %d%%\n", speechService.getMicrophoneVolume())

    printf("Whisper语音服务初始化成功\n")

    // 检查麦克风状态 (复用Vosk的逻辑，可能需要调整)
    printf("正在检查麦克风状态...")
    val checkJob = globalScope.launch {
        withContext(Dispatchers.Unconfined) {
            async {
                val micStatus = speechService.checkMicrophoneStatus()
                printf("%s\n", micStatus)
            }.await()
        }
    }
    // 等待麦克风检查完成
    checkJob.join()
    printf("麦克风状态检查完成。\n")
    printf("如果日志中显示[WARNING]，可能表示麦克风没有接收到足够的声音输入\n")

    printf("开始语音识别，请对着麦克风说话...\n")
    // printf("提示: 如果识别效果不佳，可以尝试调整麦克风音量或检查麦克风连接\n") // Whisper音量控制不同

    // Whisper服务可能不直接管理音量，注释掉相关代码
    // speechService.setMicrophoneVolume(100)
    // printf("已调整麦克风音量为: %d%%\n", speechService.getMicrophoneVolume())

    // 方法1：持续识别模式
    // 启动识别并收集结果
    speechService.startRecognition()

    // 监听识别结果
    val job = globalScope.launch {
        speechService.recognitionText.collect { text ->
            if (text != null) {
                printf("识别结果: %s\n", text)
            }
        }
    }

    // 等待20秒 (持续模式)
    printf("持续识别模式，将在20秒后切换到单次识别模式...\n")
    kotlinx.coroutines.delay(20000)

    // 停止持续识别
    speechService.stopRecognition()
    job.cancel()

    // 方法2：单次识别模式
    printf("\n切换到单次识别模式，请对着麦克风说话...\n")
    // printf("当前麦克风音量: %d%%\n", speechService.getMicrophoneVolume()) // Whisper音量控制不同



    // 释放资源
    speechService.release()
    printf("Whisper语音识别示例结束\n")
}