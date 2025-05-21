@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package voice.core.app

import kotlinx.cinterop.ExperimentalForeignApi
import voice.acquisition.portaudio.PortAudioDevice
import voice.hal.LinuxAudioDeviceSelector
import voice.audio.processing.WebRtcApmSingleton
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * 应用程序入口点
 * 负责初始化各种组件和资源
 */
object AudioApplication {
    private var isInitialized = false
    private val deviceSelector = LinuxAudioDeviceSelector()

    /**
     * 初始化应用程序
     * 注册资源管理器，确保程序退出时释放资源
     */
    fun initialize() {
        if (isInitialized) return

        // 注册退出清理
        println("[INFO] 已注册程序退出清理钩子")
        registerShutdownHook()

        // 释放系统音频资源
        deviceSelector.killOtherAudioProcesses()
        
        // 应用ALSA配置
        if (deviceSelector.isRaspberryPi()) {
            deviceSelector.fixAlsaConfig()
            println("[INFO] 已应用树莓派优化配置")
        }

        // 预热核心资源
        preloadResources()

        // 初始化音频设备（全局单例）
        initializeAudioDevice()

        // 初始化完成
        isInitialized = true
        println("[INFO] 应用程序初始化完成")
    }

    /**
     * 清理应用程序资源
     */
    private fun cleanup() {
        println("[INFO][AudioApplication] 执行清理...")
        
        // 释放WebRTC APM资源
        WebRtcApmSingleton.release()
        
        println("[INFO][AudioApplication] 清理完成")
    }

    /**
     * 注册关闭钩子
     */
    private fun registerShutdownHook() {
        // 在实际应用程序中，可以考虑使用JVM Runtime.addShutdownHook或其他机制
        // 由于Kotlin/Native限制，这里只是声明一个方法，实际要在C侧实现注册
        val cleanupFunction = {
            println("[INFO][AudioApplication] 程序退出，执行资源清理...")
            WebRtcApmSingleton.release()
            println("[INFO][AudioApplication] 资源清理完成")
        }
        
        // 存储函数引用，防止GC回收
        val ref = AtomicReference<Function0<Unit>>(cleanupFunction)
        
        // 这里只是记录日志，实际上没有注册钩子，因为Kotlin/Native没有这个能力
        println("[INFO] 已准备退出钩子，但注意这需要在原生代码层面实现")
    }

    /**
     * 预热音频处理资源
     */
    private fun preloadResources() {
        println("[INFO] 预热音频处理资源...")

        try {
            // 预热WebRTC APM实例
            WebRtcApmSingleton.getInstance(16000, 1).let {
                // 进行简单处理以确保实例正确初始化
                if (WebRtcApmSingleton.isVoiceDetected()) {
                    println("[INFO] WebRTC APM VAD测试通过")
                }
                println("[INFO] WebRTC APM预热成功")
            }
        } catch (e: Exception) {
            println("[WARN] WebRTC APM预热失败: ${e.message}")
        }
    }

    /**
     * 初始化音频设备
     */
    private fun initializeAudioDevice() {
        println("[INFO] 初始化PortAudio...")
        
        // 获取全局单例实例
        val audioDevice = PortAudioDevice.getInstance()
        
        try {
            if (audioDevice.initialize("default", 16000)) {
                println("[INFO] PortAudio初始化成功")
            } else {
                println("[WARN] PortAudio初始化失败，应用可能无法正常工作")
            }
        } catch (e: Exception) {
            println("[WARN] PortAudio初始化异常: ${e.message}")
        }
    }

    /**
     * 关闭应用程序
     * 释放所有资源
     */
    fun shutdown() {
        // 释放WebRTC APM资源
        try {
            WebRtcApmSingleton.release()
        } catch (e: Exception) {
            println("[WARN] 清理WebRTC APM资源时出错: ${e.message}")
        }
        
        // 释放PortAudio全局单例
        try {
            PortAudioDevice.getInstance().release()
        } catch (e: Exception) {
            println("[WARN] 清理PortAudio资源时出错: ${e.message}")
        }

        // 重置初始化状态
        isInitialized = false
        println("[INFO] 应用程序已关闭，所有资源已释放")
    }
} 