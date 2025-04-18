@file:OptIn(ExperimentalForeignApi::class)

package vosk.examples

import com.airobot.core.utils.thread.globalScope
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import platform.posix.exit
import platform.posix.printf
import vosk.YanVoskSpeechService

/**
 * Vosk语音识别示例程序
 *
 * 这个示例展示了如何使用YanVoskSpeechService进行语音识别
 * 它会初始化语音服务，启动识别，并打印识别结果
 * 同时演示如何调整麦克风音量以提高识别效果
 *
 * 增强功能：
 * 1. 麦克风状态诊断 - 检测麦克风是否正常工作
 * 2. 音频信号增强 - 放大语音信号并降低噪声
 * 3. 详细的调试信息 - 输出音频振幅数据以帮助诊断问题
 */
fun vosk(): Unit = runBlocking {
    printf("Vosk语音识别示例程序\n")

    // 创建语音服务实例
    val speechService = YanVoskSpeechService()

    // 初始化服务，设置初始麦克风音量为70%
    // 注意：确保模型路径正确，默认为"/usr/local/share/yanshee-model/vosk-model-small-cn-0.22"
    if (!speechService.initialize(micVolume = 70)) {
        printf("初始化语音服务失败\n")
        exit(1)
    }

    printf("当前麦克风音量: %d%%\n", speechService.getMicrophoneVolume())

    printf("语音服务初始化成功\n")

    // 检查麦克风状态
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
    printf("提示: 如果识别效果不佳，可以尝试调整麦克风音量或检查麦克风连接\n")

    // 调整麦克风音量以提高识别效果
    // 这里设置为100%，以确保捕获足够的声音
    speechService.setMicrophoneVolume(100)
    printf("已调整麦克风音量为: %d%%\n", speechService.getMicrophoneVolume())

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

    // 等待10秒
    printf("持续识别模式，将在10秒后尝试调整音量...\n")
    delay(10000)

    // 如果识别效果不佳，尝试调整音量
    printf("\n尝试调整麦克风音量以提高识别效果...\n")
    // 增加音量到90%
    speechService.setMicrophoneVolume(100)
    printf("已调整麦克风音量为: %d%%\n", speechService.getMicrophoneVolume())

    // 继续等待10秒
    printf("继续持续识别模式，将在10秒后切换到单次识别模式...\n")
    delay(10000)

    // 停止持续识别
    speechService.stopRecognition()
    job.cancel()

    // 方法2：单次识别模式
    printf("\n切换到单次识别模式，请对着麦克风说话...\n")
    printf("当前麦克风音量: %d%%\n", speechService.getMicrophoneVolume())

    // 执行5次单次识别
    repeat(5) { i ->
        printf("第%d次识别 (最多等待5秒)...\n", i + 1)
        val result = speechService.recognizeOnce(5000)

        if (result != null) {
            printf("识别结果: %s\n", result)
        } else {
            printf("未能识别语音或超时\n")
        }

        // 短暂暂停
        delay(1000)
    }

    // 释放资源
    speechService.release()
    printf("语音识别示例结束\n")
}