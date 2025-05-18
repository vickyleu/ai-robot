@file:OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)

package voice.core.app

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.runBlocking
import platform.posix.SIGINT
import platform.posix.signal
import voice.core.config.VoiceAssistantConfig
import voice.core.service.VoiceAssistant
import kotlin.time.ExperimentalTime

/**
 * 语音识别与合成Demo主程序
 * 使用模块化架构
 */
private lateinit var globalVoiceAssistant: VoiceAssistant

fun voiceDemo() = runBlocking {
    println("启动语音识别与合成Demo")
    println("该Demo将使用麦克风监听关键词，检测到关键词后会播放\"你好\"")
    println("按Ctrl+C终止程序")
    val config = VoiceAssistantConfig()

    // 预热音频处理组件
    AudioApplication.initialize()

    // 创建语音助手实例
    val voiceAssistant = VoiceAssistant(config)
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
            var counter = 0
            while (true) {
                // 每秒钟增加计数器
                counter++
                
                // 降低日志频率：每30秒输出一次运行状态
                if (counter % 30 == 0) {
                    println("【系统检测】程序正在运行，已经运行${counter}秒")
                }
                
                // 降低诊断信息频率：每60秒打印一次诊断信息
                if (counter % 60 == 0) {
                    println("【诊断信息】语音助手状态: ${voiceAssistant.assistantState.value}")
                }
                
                kotlinx.coroutines.delay(1000)
            }
        } else {
            println("启动语音助手失败")
        }
    } else {
        println("初始化检测器失败")
    }
}