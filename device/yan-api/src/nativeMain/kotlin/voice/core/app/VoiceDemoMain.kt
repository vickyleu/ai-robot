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
                try {
                    withTimeout(10000) { // 最多等待10秒
                        val result = globalVoiceAssistant.start()
                        if (result) {
                            logger.info("语音助手启动成功")
                        } else {
                            logger.error("语音助手启动失败")
                            return@withTimeout
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    logger.error("语音助手启动超时")
                    return@runBlocking
                }
                
                // 启动诊断监控任务
                job = mainScope?.launch {
                    while (isActive) {
                        delay(30000) // 每30秒生成一次诊断报告
                        if (isRunning) {
                            try {
                                val diagnostics = globalVoiceAssistant.generateDiagnostics()
                                logger.info("===== 周期性诊断报告 =====\n$diagnostics")
                            } catch (e: Exception) {
                                logger.error("生成诊断报告失败: ${e.message}")
                            }
                        }
                    }
                }
                
                // 提示准备就绪
                logger.info("语音助手已准备就绪，正在监听...")
                println("语音助手已准备就绪，说\"小样\"或\"嘿小样\"来激活，按Ctrl+C退出")
                
                // 使用更细粒度的状态检查循环
                while (isRunning) {
                    delay(1000)
                    if (globalVoiceAssistant.assistantState.value == AssistantState.IDLE) {
                        logger.warn("检测到语音助手已停止运行，尝试重新启动...")
                        globalVoiceAssistant.start()
                    }
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