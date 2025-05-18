@file:OptIn(ExperimentalForeignApi::class)

package voice.core.app

import com.airobot.device.yanapi.voice.audio.processing.AudioProcessingResourceManager
import com.airobot.device.yanapi.voice.audio.processing.RNNoiseSingleton
import com.airobot.device.yanapi.voice.audio.processing.SoxrSingleton
import com.airobot.device.yanapi.voice.audio.processing.SpeexDspProcessor
import kotlinx.cinterop.ExperimentalForeignApi
import voice.acquisition.portaudio.PortAudioDevice
import voice.hal.LinuxAudioDeviceSelector
import platform.posix.system

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

        // 注册资源释放钩子
        println("[INFO] 已注册程序退出清理钩子")
        AudioProcessingResourceManager.registerShutdownHook()

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
     * 预热音频处理资源
     */
    private fun preloadResources() {
        println("[INFO] 预热音频处理资源...")

        try {
            // 预热RNNoise实例
            RNNoiseSingleton.getInstance()?.let {
                RNNoiseSingleton.process(
                    inputBuffer = null,
                    outputBuffer = null,
                    frameCount = 0,
                    vadThreshold = 0.08f,
                    gain = 2.5f
                )
                RNNoiseSingleton.releaseInstance()
                println("[INFO] RNNoise预热成功")
            }
        } catch (e: Exception) {
            println("[WARN] RNNoise预热失败: ${e.message}")
        }

        try {
            // 预热Soxr实例
            val commonSampleRates = arrayOf(
                Pair(48000.0, 16000.0),
                Pair(44100.0, 16000.0),
                Pair(16000.0, 8000.0),
                Pair(8000.0, 16000.0)
            )

            for ((inputRate, outputRate) in commonSampleRates) {
                SoxrSingleton.getInstance(inputRate, outputRate)?.let {
                    SoxrSingleton.releaseInstance(inputRate, outputRate)
                    println("[INFO] Soxr预热成功: $inputRate → $outputRate")
                }
            }
        } catch (e: Exception) {
            println("[WARN] Soxr预热失败: ${e.message}")
        }

        try {
            // 预热SpeexDSP处理器
            val speexProcessor = SpeexDspProcessor()
            speexProcessor.initialize(
                sampleRate = 16000,
                frameSize = 320,
                enableDenoise = true,
                enableAgc = true,
                enableEcho = true
            )
            speexProcessor.process(ShortArray(320))
            speexProcessor.release()
            println("[INFO] SpeexDSP预热成功")
        } catch (e: Exception) {
            println("[WARN] SpeexDSP预热失败: ${e.message}")
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
        // 释放音频处理资源
        AudioProcessingResourceManager.releaseAllResources()
        
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