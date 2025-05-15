package com.airobot.device.yanapi.snowboyPiper.impl

import com.airobot.device.yanapi.snowboyPiper.interfaces.AudioAnalyzer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.set
import snowboyPiper.interop.AudioProcessingResourceManager
import snowboyPiper.interop.RNNoiseSingleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class, ExperimentalForeignApi::class)
class BasicAudioAnalyzer(
    private val energyThreshold: Double,
    private val noiseGateThreshold: Double,
    private val validVoiceRmsThreshold: Double,
    private val validVoiceZcrThreshold: Double
) : AudioAnalyzer {

    // 连续语音检测计数器
    private var consecutiveVoiceFrames = 0
    private val minConsecutiveFramesForVoice = 1 // 降低连续帧要求到1，提高灵敏度

    // 回声消除相关变量
    private var lastPlaybackTime = 0L
    private val echoSuppressionTimeMs = 1000L // 缩短回声抑制时间以提高灵敏度

    // 记录连续语音状态
    private var lastVoiceActivityTime = 0L
    private var isInContinuousSpeech = false
    private val voiceContinuityThreshold = 600L // 降低连续语音阈值提高灵敏度
    private val silencePauseThreshold = 800L // 降低静音阈值提高灵敏度

    // RNNoise参数优化
    // 降低阈值以提高灵敏度
    private val rnnVadThreshold = 0.08f // 从0.15f降低到0.08f，提高灵敏度
    private val rnnGracePeriod = 1.8f  // 从2.5f降低到1.8f，加快响应

    init {
        // 初始化时注册资源释放钩子
        AudioProcessingResourceManager.registerShutdownHook()
    }

    override fun hasVoiceActivity(buffer: ShortArray): Boolean {
        // 检查是否在回声抑制时间内
        val currentTime = Clock.System.now().toEpochMilliseconds()
        if (currentTime - lastPlaybackTime < echoSuppressionTimeMs) {
            return false // 回声期间直接忽略
        }

        // 直接使用RNNoise进行语音检测
        val isHumanVoice = checkVoiceWithRNNoise(buffer)

        // 如果RNNoise检测到语音，增加连续帧计数
        if (isHumanVoice) {
            consecutiveVoiceFrames++
            lastVoiceActivityTime = currentTime
            
            // 如果与上次语音活动时间接近，标记为连续语音
            if (currentTime - lastVoiceActivityTime < voiceContinuityThreshold) {
                isInContinuousSpeech = true
            }
        } else {
            // 没有检测到语音，减少连续帧计数
            consecutiveVoiceFrames = max(0, consecutiveVoiceFrames - 1)
            
            // 如果静音时间过长，重置连续语音状态
            if (currentTime - lastVoiceActivityTime > silencePauseThreshold) {
                isInContinuousSpeech = false
            }
        }

        // 要求至少有连续帧才认为是有效语音，连续语音状态下放宽要求
        return isHumanVoice && (consecutiveVoiceFrames >= minConsecutiveFramesForVoice || isInContinuousSpeech)
    }

    override fun containsValidVoice(audioData: ShortArray): Boolean {
        // 检查是否在回声抑制时间内
        val currentTime = Clock.System.now().toEpochMilliseconds()
        if (currentTime - lastPlaybackTime < echoSuppressionTimeMs) {
            return false // 回声期间直接忽略
        }

        // 在连续语音中更宽容
        if (isInContinuousSpeech) {
            return true
        }

        // 直接使用RNNoise进行人声检测
        return checkVoiceWithRNNoise(audioData)
    }

    /**
     * 使用RNNoise检测是否为人声，优化参数提高灵敏度
     */
    private fun checkVoiceWithRNNoise(buffer: ShortArray): Boolean {
        try {
            // 创建输入和输出缓冲区
            val frameCount = buffer.size
            val inputBuffer = nativeHeap.allocArray<ShortVar>(frameCount)
            val outputBuffer = nativeHeap.allocArray<ShortVar>(frameCount)

            // 复制音频数据到输入缓冲区
            for (i in 0 until frameCount) {
                inputBuffer[i] = buffer[i]
            }

            // 创建VAD概率数组
            val maxVadValues = frameCount / 480 + 1 // 每480样本一个VAD值
            val vadProbabilitiesPtr = nativeHeap.allocArray<FloatVar>(maxVadValues)

            // 使用RNNoise单例处理音频数据 - 降低VAD阈值提高灵敏度
            val processResult = RNNoiseSingleton.process(
                inputBuffer,
                outputBuffer,
                frameCount,
                vadProbabilitiesPtr,
                maxVadValues,
                rnnVadThreshold, // 低阈值提高灵敏度
                rnnGracePeriod   // 优化grace period加快响应
            )

            // 检查处理结果
            if (processResult <= 0) {
                nativeHeap.free(inputBuffer.rawValue)
                nativeHeap.free(outputBuffer.rawValue)
                nativeHeap.free(vadProbabilitiesPtr.rawValue)
                return false
            }

            // 分析VAD概率
            var voiceFrames = 0
            var totalFrames = minOf(processResult, maxVadValues)
            var maxProb = 0.0f

            for (i in 0 until totalFrames) {
                val prob = vadProbabilitiesPtr[i]
                maxProb = max(maxProb, prob)
                if (prob >= 0.15f) { // 降低阈值从0.2f到0.15f提高灵敏度
                    voiceFrames++
                }
            }

            // 释放资源
            nativeHeap.free(inputBuffer.rawValue)
            nativeHeap.free(outputBuffer.rawValue)
            nativeHeap.free(vadProbabilitiesPtr.rawValue)

            // 判断是否检测到足够的人声帧 - 降低阈值提高灵敏度
            val voiceRatio = if (totalFrames > 0) voiceFrames.toFloat() / totalFrames else 0f
            val isHumanVoice = voiceRatio >= 0.12f || maxProb >= 0.2f // 降低阈值提高灵敏度

            // 只在有人声时或调试需要时输出日志
            if (isHumanVoice) {
                println("[DEBUG] 检测到人声: 帧比例=$voiceRatio, 最高概率=$maxProb")
            }

            return isHumanVoice
        } catch (e: Exception) {
            println("[ERROR] RNNoise处理异常: ${e.message}")
            return false 
        }
    }

    /**
     * 应用噪声门限，使用RNNoise进行降噪处理
     */
    override fun applyNoiseGate(audioData: ShortArray): ShortArray {
        try {
            // 创建输入和输出缓冲区
            val frameCount = audioData.size
            val inputBuffer = nativeHeap.allocArray<ShortVar>(frameCount)
            val outputBuffer = nativeHeap.allocArray<ShortVar>(frameCount)

            // 复制音频数据到输入缓冲区
            for (i in 0 until frameCount) {
                inputBuffer[i] = audioData[i]
            }

            // 使用RNNoise单例处理音频数据 - 配置为降噪模式
            val processResult = RNNoiseSingleton.process(
                inputBuffer,
                outputBuffer,
                frameCount,
                null, // 不需要VAD概率
                0,
                0.1f, // 较低的VAD阈值以保留更多语音内容
                1.5f  // 适中增益，提高信噪比
            )

            // 检查处理结果
            if (processResult <= 0) {
                nativeHeap.free(inputBuffer.rawValue)
                nativeHeap.free(outputBuffer.rawValue)
                return audioData // 出错时返回原始音频
            }

            // 创建输出数组并复制降噪后的音频数据
            val result = ShortArray(frameCount)
            for (i in 0 until frameCount) {
                result[i] = outputBuffer[i]
            }

            // 释放资源
            nativeHeap.free(inputBuffer.rawValue)
            nativeHeap.free(outputBuffer.rawValue)

            return result
        } catch (e: Exception) {
            println("[ERROR] RNNoise降噪处理异常: ${e.message}")
            return audioData // 出错时返回原始音频
        }
    }

    /**
     * 通知分析器播放音频，需要暂时抑制回声
     */
    override fun notifyAudioPlayback(audioData: ShortArray) {
        // 记录播放时间
        lastPlaybackTime = Clock.System.now().toEpochMilliseconds()

        // 重置语音检测状态
        consecutiveVoiceFrames = 0
        isInContinuousSpeech = false
    }

    /**
     * 重置分析器状态
     */
    override fun reset() {
        consecutiveVoiceFrames = 0
        isInContinuousSpeech = false
        lastVoiceActivityTime = 0L

        println("[DEBUG] 音频分析器状态已重置")
    }
}