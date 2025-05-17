@file:OptIn(ExperimentalForeignApi::class)

package voice.core.app

import com.airobot.device.yanapi.voice.audio.processing.AudioProcessingResourceManager
import com.airobot.device.yanapi.voice.audio.processing.RNNoiseSingleton
import com.airobot.device.yanapi.voice.audio.processing.SoxrSingleton
import com.airobot.device.yanapi.voice.audio.processing.SpeexDspProcessor
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * 应用程序入口点
 * 负责初始化各种组件和资源
 */
object AudioApplication {
    private var isInitialized = false

    /**
     * 初始化应用程序
     * 注册资源管理器，确保程序退出时释放资源
     */
    fun initialize() {
        if (isInitialized) return

        // 注册资源释放钩子
        AudioProcessingResourceManager.registerShutdownHook()

        // 预热核心资源，减少运行时延迟
        preloadResources()

        // 初始化完成
        isInitialized = true
        println("[INFO] 应用程序初始化完成")
    }

    private fun preloadResources() {
        println("[INFO] 开始预热关键资源...")

        // 预热RNNoise实例 - 更低的VAD阈值以提高灵敏度
        RNNoiseSingleton.getInstance()?.let {
            // 预先设置更灵敏的参数
            RNNoiseSingleton.process(
                inputBuffer = null,
                outputBuffer = null,
                frameCount = 0,
                vadThreshold = 0.08f, // 降低VAD阈值提高灵敏度
                gain = 2.5f // 提高增益改善弱音识别
            )
            RNNoiseSingleton.releaseInstance()
            println("[INFO] RNNoise预热成功")
        }

        // 预热常用采样率配置的Soxr实例
        val commonSampleRates = arrayOf(
            Pair(48000.0, 16000.0),
            Pair(44100.0, 16000.0),
            Pair(16000.0, 8000.0),
            Pair(8000.0, 16000.0) // 添加上采样场景
        )

        for ((inputRate, outputRate) in commonSampleRates) {
            SoxrSingleton.getInstance(inputRate, outputRate)?.let {
                SoxrSingleton.releaseInstance(inputRate, outputRate)
                println("[INFO] Soxr预热成功: $inputRate → $outputRate")
            }
        }

        // 预热SpeexDSP处理器 - 减小滤波器长度，降低CPU需求
        val speexProcessor = SpeexDspProcessor()
        speexProcessor.initialize(
            sampleRate = 16000,
            frameSize = 320,
            enableDenoise = true,
            enableAgc = true,
            enableEcho = true
        )
        // 处理一个空帧以完成预热
        speexProcessor.process(ShortArray(320))
        speexProcessor.release()
        println("[INFO] SpeexDSP预热成功")

        println("[INFO] 资源预热完成")
    }

    /**
     * 关闭应用程序
     * 释放所有资源
     */
    fun shutdown() {
        // 释放音频处理资源
        AudioProcessingResourceManager.releaseAllResources()

        // 重置初始化状态
        isInitialized = false
        println("[INFO] 应用程序已关闭，所有资源已释放")
    }
} 