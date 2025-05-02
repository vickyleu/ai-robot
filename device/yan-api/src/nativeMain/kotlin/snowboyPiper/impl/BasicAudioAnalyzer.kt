package com.airobot.device.yanapi.snowboyPiper.impl

import com.airobot.device.yanapi.snowboyPiper.interfaces.AudioAnalyzer
import kotlin.math.abs
import kotlin.math.sqrt

class BasicAudioAnalyzer(
    private val energyThreshold: Double = 500.0,
    private val noiseGateThreshold: Double = 200.0,
    private val validVoiceRmsThreshold: Double = 500.0,
    private val validVoiceZcrThreshold: Double = 0.3
) : AudioAnalyzer {

    private var backgroundNoiseLevel = 0.0

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
        // 计算RMS (Root Mean Square)能量
        var sumSquares = 0.0
        for (sample in audioData) {
            sumSquares += (sample * sample)
        }
        val rms = sqrt(sumSquares / audioData.size)

        // 计算零交叉率(Zero Crossing Rate)，可以帮助区分语音和噪音
        var zeroCrossings = 0
        for (i in 1 until audioData.size) {
            if ((audioData[i] > 0 && audioData[i-1] <= 0) ||
                (audioData[i] <= 0 && audioData[i-1] > 0)) {
                zeroCrossings++
            }
        }
        val zcr = zeroCrossings.toDouble() / audioData.size

        // 语音通常有一定的能量和适中的零交叉率
        // 纯噪音往往零交叉率高，而无声段能量低
        val hasVoice = rms > validVoiceRmsThreshold && zcr < validVoiceZcrThreshold

        // 仅在有语音的情况下打印调试信息
        if (hasVoice) {
            println("[DEBUG] 音频分析 - RMS: $rms, ZCR: $zcr, 判断: ${if(hasVoice) "有语音" else "无语音"}")
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