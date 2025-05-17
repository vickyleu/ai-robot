@file:OptIn(ExperimentalForeignApi::class, NativeRuntimeApi::class)

package com.airobot.device.yanapi.voice.audio.processing

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import platform.posix.atexit

/**
 * 音频处理资源管理器
 * 负责预热和缓存常用的音频处理组件，减少运行时创建开销
 */
object AudioProcessingResourceManager {
    // 预热的SpeexDSP处理器
    private var prewarmedSpeexDsp: SpeexDspProcessor? = null

    // 是否已初始化
    private var initialized = false

    // 默认采样率
    private val defaultSampleRate = 16000

    // 默认帧大小
    private val defaultFrameSize = 320 // 20ms @ 16kHz

    // 跟踪预热和关闭钩子状态
    private var isShutdownHookRegistered = false
    private var isPrewarmed = false

    /**
     * 初始化音频处理资源
     * 预热常用组件以减少首次使用时的延迟
     * @param sampleRate 采样率
     * @param frameSize 音频帧大小
     * @param prewarmSpeexDsp 是否预热SpeexDSP
     * @return 初始化是否成功
     */
    fun initialize(
        sampleRate: Int = defaultSampleRate,
        frameSize: Int = defaultFrameSize,
        prewarmSpeexDsp: Boolean = true
    ): Boolean {
        if (initialized) {
            return true
        }

        var success = true

        try {
            // 预热SpeexDSP处理器
            if (prewarmSpeexDsp) {
                prewarmedSpeexDsp = SpeexDspProcessor()
                val speexInitResult = prewarmedSpeexDsp?.initialize(
                    sampleRate = sampleRate,
                    frameSize = frameSize,
                    enableDenoise = true,
                    enableAgc = true,
                    enableVad = true,
                    enableEcho = true,
                    deviceProfile = "raspberry_pi"
                ) ?: false

                if (!speexInitResult) {
                    println("[WARN] SpeexDSP预热失败")
                    success = false
                } else {
                    println("[INFO] SpeexDSP处理器已预热")
                }
            }

            // 注册关闭钩子
            registerShutdownHook()

            initialized = true
            println("[INFO] 音频处理资源管理器初始化${if (success) "成功" else "部分成功"}")
            return success
        } catch (e: Exception) {
            println("[ERROR] 初始化音频处理资源管理器异常: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    /**
     * 获取预热的SpeexDSP处理器
     * 如果未预热，尝试按需创建
     * @return SpeexDSP处理器实例或null
     */
    fun getSpeexDsp(): SpeexDspProcessor? {
        if (prewarmedSpeexDsp != null) {
            return prewarmedSpeexDsp
        }

        // 未预热，尝试创建
        try {
            println("[INFO] SpeexDSP未预热，尝试创建新实例")
            val newSpeexDsp = SpeexDspProcessor()
            val initResult = newSpeexDsp.initialize(
                sampleRate = defaultSampleRate,
                frameSize = defaultFrameSize,
                deviceProfile = "raspberry_pi"
            )

            if (!initResult) {
                println("[ERROR] 按需创建SpeexDSP失败")
                return null
            }

            // 保存为预热实例以便后续复用
            prewarmedSpeexDsp = newSpeexDsp
            return newSpeexDsp
        } catch (e: Exception) {
            println("[ERROR] 创建SpeexDSP实例异常: ${e.message}")
            return null
        }
    }

    /**
     * 注册关闭钩子，确保在程序结束时释放资源
     */
    fun registerShutdownHook() {
        if (!isShutdownHookRegistered) {
            // C 标准库 atexit 注册函数，在程序退出时调用
            atexit(staticCFunction<Unit> {
                // 清理资源
                releaseAllResources()
            })
            isShutdownHookRegistered = true
            println("[INFO] 已注册程序退出清理钩子")
        }
    }

    /**
     * 预热资源，提前加载所有必要的资源
     */
    fun prewarmResources() {
        if (isPrewarmed) return

        // 预热常用采样率配置的实例
        SoxrSingleton.preloadCommonConfigs()

        // 预热RNNoise实例
        val rnnoise = RNNoiseSingleton.getInstance()
        if (rnnoise != null) {
            println("[INFO] RNNoise实例预热成功")
            RNNoiseSingleton.releaseInstance()
        }

        // 预热SpeexDSP
        if (prewarmedSpeexDsp == null) {
            initialize(prewarmSpeexDsp = true)
        }

        isPrewarmed = true
        println("[INFO] 所有音频处理资源已预热完成")
    }

    /**
     * 释放所有资源
     */
    fun releaseAll() {
        try {
            // 释放SpeexDSP
            prewarmedSpeexDsp?.release()
            prewarmedSpeexDsp = null

            initialized = false
            println("[INFO] SpeexDSP资源已释放")
        } catch (e: Exception) {
            println("[ERROR] 释放音频处理资源异常: ${e.message}")
        }
    }

    /**
     * 释放所有音频处理资源
     */
    fun releaseAllResources() {
        // 释放SpeexDSP
        releaseAll()

        // 释放RNNoise资源
        RNNoiseSingleton.forceRelease()

        // 释放Soxr资源
        SoxrSingleton.forceReleaseAll()

        // 强制垃圾回收
        GC.collect()

        // 重置预热状态
        isPrewarmed = false

        println("[INFO] 所有音频处理资源已释放")
    }
}