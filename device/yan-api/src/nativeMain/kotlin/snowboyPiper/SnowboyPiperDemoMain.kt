@file:OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)

package snowboyPiper


import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.runBlocking
import platform.posix.SIGINT
import platform.posix.signal
import snowboyPiper.config.VoiceAssistantConfig
import snowboyPiper.impl.SnowboyPiperVoiceAssistant
import kotlin.time.ExperimentalTime

/**
 * Snowboy关键词检测与Piper语音合成Demo主程序
 * 使用重构后的模块化架构
 */
private lateinit var globalVoiceAssistant: SnowboyPiperVoiceAssistant

fun snowboyPiper() = runBlocking {
    println("启动Snowboy关键词检测与Piper语音合成Demo")
    println("该Demo将使用麦克风监听关键词，检测到关键词后会播放\"你好\"")
    println("按Ctrl+C终止程序")
    val config = VoiceAssistantConfig()

    // 预热音频处理组件
    AudioApplication.initialize()

    // 创建语音助手实例
    val voiceAssistant = SnowboyPiperVoiceAssistant(config)
    // 将局部变量赋值给全局变量
    globalVoiceAssistant = voiceAssistant

    // 设置信号处理，以便能够优雅地关闭程序
    // 设置信号处理
    signal(SIGINT, staticCFunction { sig ->
        println("\n接收到终止信号，正在关闭...")
        runBlocking {
            globalVoiceAssistant.stop()
            globalVoiceAssistant.release()
        }
        kotlin.system.exitProcess(0)
    })
    // 初始化检测器
    println("初始化检测器...")
    val initSuccess = voiceAssistant.initialize()

    if (initSuccess) {
        println("初始化成功，开始关键词检测...")

        // 启动语音助手
        if (voiceAssistant.start()) {
            // 保持程序运行，直到收到终止信号
            while (true) {
                kotlinx.coroutines.delay(1000)
            }
        } else {
            println("启动语音助手失败")
        }
    } else {
        println("初始化失败")
    }

    // 释放资源
    voiceAssistant.release()
}