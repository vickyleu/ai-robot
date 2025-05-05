package com.airobot.device.yanapi.snowboyPiper.impl

import com.airobot.device.yanapi.snowboyPiper.interfaces.AudioAnalyzer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class BasicAudioAnalyzer(
    private val energyThreshold: Double,
    private val noiseGateThreshold: Double,
    private val validVoiceRmsThreshold: Double,
    private val validVoiceZcrThreshold: Double
) : AudioAnalyzer {

    private var backgroundNoiseLevel = 0.0
    private var adaptiveRmsThreshold = validVoiceRmsThreshold
    private val adaptationFactor = 0.95 // 适应因子
    private var silenceCounter = 0
    private val maxSilenceBeforeAdapt = 10
    override fun hasVoiceActivity(audioData: ShortArray): Boolean {
        var energy = 0.0
        for (sample in audioData) {
            energy += (sample * sample)
        }
        energy /= audioData.size
        return energy > energyThreshold
    }

    override fun applyNoiseGate(audioData: ShortArray): ShortArray {
        val result = ShortArray(audioData.size)

        // 先计算平均背景噪声水平（如果尚未初始化）
        if (backgroundNoiseLevel == 0.0) {
            var sum = 0.0
            for (sample in audioData) {
                sum += abs(sample.toDouble())
            }
            backgroundNoiseLevel = sum / audioData.size * 0.8 // 使用80%作为保守估计
        }

        // 应用噪声门限
        for (i in audioData.indices) {
            val sample = audioData[i]
            if (abs(sample.toDouble()) > noiseGateThreshold) {
                result[i] = sample
            } else {
                result[i] = 0 // 低于阈值的信号设为0
            }
        }

        return result
    }

    override fun containsValidVoice(audioData: ShortArray): Boolean {
        // 计算RMS
        var sumSquares = 0.0
        for (sample in audioData) {
            sumSquares += (sample * sample)
        }
        val rms = sqrt(sumSquares / audioData.size)

        // 计算ZCR
        var zeroCrossings = 0
        for (i in 1 until audioData.size) {
            if ((audioData[i] > 0 && audioData[i-1] <= 0) ||
                (audioData[i] <= 0 && audioData[i-1] > 0)) {
                zeroCrossings++
            }
        }
        val zcr = zeroCrossings.toDouble() / audioData.size

        // 动态调整RMS阈值
        if (rms < adaptiveRmsThreshold) {
            silenceCounter++
            if (silenceCounter > maxSilenceBeforeAdapt) {
                // 连续检测到多次"无声"，降低阈值使系统更灵敏
                adaptiveRmsThreshold *= adaptationFactor
                adaptiveRmsThreshold =
                    max(adaptiveRmsThreshold, validVoiceRmsThreshold * 0.5) // 设置下限
                silenceCounter = 0
                println("[DEBUG] 调整RMS阈值到: $adaptiveRmsThreshold")
            }
        } else {
            silenceCounter = 0
            // 检测到"有声"，逐渐恢复阈值
            adaptiveRmsThreshold = (adaptiveRmsThreshold * 0.95) + (validVoiceRmsThreshold * 0.05)
        }

        // 使用适应性阈值判断
        val hasVoice = rms > adaptiveRmsThreshold && zcr < validVoiceZcrThreshold
        if(hasVoice){
            println("[DEBUG] 音频分析 - RMS: $rms (阈值: $adaptiveRmsThreshold), ZCR: $zcr")
        }
        return hasVoice
    }

    /**
     * 重置分析器状态
     */
    fun reset() {
        backgroundNoiseLevel = 0.0
    }
}