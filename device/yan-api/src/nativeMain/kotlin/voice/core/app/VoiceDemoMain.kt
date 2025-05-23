@file:OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)

package voice.core.app

import com.airobot.device.yanapi.voice.util.AudioBufferPool
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
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
import platform.posix.getenv
import platform.posix.signal
import voice.api.VoiceAssistantApi
import voice.api.VoiceAssistantApi.AssistantState
import voice.core.config.VoiceAssistantConfig
import voice.core.service.VoiceAssistant
import voice.util.LogManager
import voice.util.PerformanceMonitorManager
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
                // 决定 Vosk 模型路径优先级：环境变量 > 配置默认值 > 旧的相对路径
                val envModelPath = getenv("VOSK_MODEL_PATH")?.toKString()
                val modelPathToUse = when {
                    !envModelPath.isNullOrBlank() -> envModelPath
                    config.voskModelPath.isNotBlank() -> config.voskModelPath
                    else -> "models/vosk"
                }

                logger.info("尝试加载 Vosk 模型路径: $modelPathToUse")

                // 初始化并添加关键词
                if (!globalVoiceAssistant.initialize(modelPathToUse)) {
                    logger.error("语音助手初始化失败")
                    return@runBlocking
                }
                
                // 设置语音识别结果回调
                globalVoiceAssistant.setSpeechRecognizedCallback { text ->
                    logger.info("✅ 识别到语音: $text")
                    
                    // 处理识别到的语音
                    mainScope?.launch {
                        try {
                            // 这里是简单的回复，实际应用中可以调用AI生成回复
                            val response = "我收到了您的指令：$text"
                            logger.info("🤖 回复: \"$response\"")
                            // 如果需要语音回复，需要实现speak方法
                        } catch (e: Exception) {
                            logger.error("处理语音命令出错: ${e.message}")
                        }
                    }
                }
                
                // 设置状态变化回调
                globalVoiceAssistant.setStateChangeCallback { state ->
                    logger.info("语音助手状态变化: $state")
                    if (state == AssistantState.LISTENING_FOR_KEYWORD) {
                        println("语音助手已就绪，请说\"小度\"或\"你好\"来唤醒我")
                    } else if (state == AssistantState.LISTENING_FOR_SPEECH) {
                        println("我在听，请说出您的指令...")
                    }
                }
                
                // 启动语音助手
                logger.info("正在启动语音助手...")
                println("正在启动语音助手...")
                if (!globalVoiceAssistant.startListeningForKeyword()) {
                    logger.error("语音助手启动失败")
                    println("语音助手启动失败")
                    return@runBlocking
                }
                logger.info("语音助手启动成功，当前状态: ${globalVoiceAssistant.assistantState.value}")
                
                // 添加延迟确保助手完全启动
                delay(1000)
                
                // 确认助手状态
                if (globalVoiceAssistant.assistantState.value == AssistantState.LISTENING_FOR_KEYWORD) {
                    logger.info("语音助手已成功进入关键词监听状态")
                    println("语音助手已启动并正在监听，请说\"小度\"或\"你好\"来唤醒")
                } else {
                    logger.warn("语音助手未进入关键词监听状态，当前状态: ${globalVoiceAssistant.assistantState.value}")
                    println("注意：语音助手可能未正确启动，当前状态: ${globalVoiceAssistant.assistantState.value}")
                }
                
                // --- 阻塞主协程保持程序运行，直到收到 SIGINT ---
                while (isRunning) {
                    delay(1000)
                    if(false){
                        logger.info(PerformanceMonitorManager.generateGlobalReport())
                        logger.info(AudioBufferPool.getStats())
                    }

                }
            }
        } catch (e: Exception) {
            logger.error("运行语音助手时发生异常: ${e.message}")
            e.printStackTrace()
        } finally {
            job?.cancel()
            globalVoiceAssistant.stop()
            logger.info("语音助手已停止")
        }
    } catch (e: Exception) {
        logger.error("初始化语音助手时发生异常: ${e.message}")
        e.printStackTrace()
    } finally {
        // 释放所有资源
        try {
            globalVoiceAssistant.release()
            logger.info("已释放所有资源")
        } catch (e: Exception) {
            logger.error("释放资源时发生异常: ${e.message}")
        }
        
        // 取消主协程
        mainScope?.cancel()
        logger.info("退出程序")
    }
}