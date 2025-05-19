@file:OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)

package voice.core.app

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.posix.SIGINT
import platform.posix.signal
import voice.api.VoiceAssistantApi.AssistantState
import voice.core.config.VoiceAssistantConfig
import voice.core.service.VoiceAssistant
import voice.util.LogManager
import kotlin.time.ExperimentalTime

/**
 * 语音识别与合成Demo主程序
 * 使用模块化架构
 */
private lateinit var globalVoiceAssistant: VoiceAssistant
private val logger = LogManager.getLogger("VoiceDemoMain")
private var isRunning = true
private var mainScope: CoroutineScope? = null



/**
 * 处理信号，用于优雅退出
 */
fun initSignalHandler() {
    signal(SIGINT, staticCFunction { signal ->
        println("收到信号 $signal，准备退出...")
        isRunning = false
        mainScope?.cancel("用户中断")
        println("已停止语音助手")
    })
}

/**
 * 启动语音助手Demo
 */
fun runVoiceDemo() {
    logger.info("启动语音助手Demo...")
    // 全局一次性初始化所有底层资源
    try {
        AudioApplication.initialize()
    } catch (e: Exception) {
        logger.warn("AudioApplication.initialize() 失败: ${e.message}")
    }
    println("启动中，请稍候...")
    
    // 创建主协程作用域
    mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    try {
        // 设置语音助手配置
        val config = VoiceAssistantConfig()
        
        // 创建语音助手实例
        globalVoiceAssistant = VoiceAssistant(config)
        
        // 添加关键词、启动并运行
        var job: Job? = null
        
        try {
            runBlocking {
                // 初始化并添加关键词
                if (!globalVoiceAssistant.initialize()) {
                    logger.error("语音助手初始化失败")
                    return@runBlocking
                }
                
                // 设置关键词检测回调
                globalVoiceAssistant.setKeywordDetectedCallback { keyword ->
                    logger.info("✅ 检测到关键词: $keyword")
                    
                    // 语音反馈
                    mainScope?.launch {
                        try {
                            val text = "我在呢，需要什么帮助?"
                            logger.info("🗣️ 合成并播放: \"$text\"")
                            globalVoiceAssistant.speak(text)
                        } catch (e: Exception) {
                            logger.error("语音合成或播放出错: ${e.message}")
                        }
                    }
                }
                
                // 启动语音助手
                logger.info("正在启动语音助手...")
                println("正在启动语音助手...")
                if (!globalVoiceAssistant.start()) {
                    logger.error("语音助手启动失败")
                    println("语音助手启动失败")
                    return@runBlocking
                }
                logger.info("语音助手启动成功，当前状态: ${globalVoiceAssistant.assistantState.value}")
                
                // 添加延迟确保助手完全启动
                delay(1000)
                
                // 确认助手状态
                if (globalVoiceAssistant.assistantState.value == AssistantState.LISTENING_KEYWORD) {
                    logger.info("语音助手已成功进入关键词监听状态")
                    println("语音助手已启动并正在监听，请说\"小样\"来唤醒")
                } else {
                    logger.warn("语音助手未进入关键词监听状态，当前状态: ${globalVoiceAssistant.assistantState.value}")
                    println("注意：语音助手可能未正确启动，当前状态: ${globalVoiceAssistant.assistantState.value}")
                }
                
                // --- 阻塞主协程保持程序运行，直到收到 SIGINT ---
                while (isRunning) {
                    delay(1000)
                }
            }
        } catch (e: Exception) {
            logger.error("运行语音助手时发生异常: ${e.message}")
            e.printStackTrace()
        } finally {
            job?.cancel()
            runBlocking { 
                globalVoiceAssistant.stop()
            }
            logger.info("语音助手已停止")
        }
    } catch (e: Exception) {
        logger.error("初始化语音助手时发生异常: ${e.message}")
        e.printStackTrace()
    } finally {
        // 释放所有资源
        try {
            runBlocking {
                globalVoiceAssistant.release()
            }
            logger.info("已释放所有资源")
        } catch (e: Exception) {
            logger.error("释放资源时发生异常: ${e.message}")
        }
        
        // 取消主协程
        mainScope?.cancel()
        logger.info("退出程序")
    }
}