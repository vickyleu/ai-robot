package com.airobot.device.yanapi.snowboyPiper.impl

import com.airobot.device.yanapi.snowboyPiper.interfaces.AudioAnalyzer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.pow

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
    
    // 添加变量以记录环境噪声基线
    private var noiseBaseline = 0.0
    private var hasEstablishedNoise = false
    private val noiseHistorySize = 30 // 增加历史样本数量
    private val noiseHistory = DoubleArray(noiseHistorySize) { 0.0 }
    private var noiseHistoryIndex = 0
    
    // 添加语音特征历史以确认真实语音
    private val featureHistorySize = 5
    private val energyHistory = DoubleArray(featureHistorySize) { 0.0 }
    private val zcrHistory = DoubleArray(featureHistorySize) { 0.0 }
    private var featureHistoryIndex = 0
    
    // 添加连续语音检测计数器
    private var consecutiveVoiceFrames = 0
    private val minConsecutiveFramesForVoice = 2 // 需要至少2帧连续满足语音特征才认为是真实语音
    
    override fun hasVoiceActivity(audioData: ShortArray): Boolean {
        // 简化版本，暂时总是返回true以便调试
//        println("[DEBUG] 检测语音活动 - 暂时返回true以便调试")
//        return true
        
        // 计算能量
        var energy = 0.0
        for (sample in audioData) {
            energy += (sample * sample)
        }
        energy /= audioData.size
        
        // 计算ZCR
        var zeroCrossings = 0
        for (i in 1 until audioData.size) {
            if ((audioData[i] > 0 && audioData[i-1] <= 0) ||
                (audioData[i] <= 0 && audioData[i-1] > 0)) {
                zeroCrossings++
            }
        }
        val zcr = zeroCrossings.toDouble() / audioData.size
        
        // 更新噪声历史
        noiseHistory[noiseHistoryIndex] = energy
        noiseHistoryIndex = (noiseHistoryIndex + 1) % noiseHistorySize
        
        // 更新特征历史
        energyHistory[featureHistoryIndex] = energy
        zcrHistory[featureHistoryIndex] = zcr
        featureHistoryIndex = (featureHistoryIndex + 1) % featureHistorySize
        
        // 定期计算噪声基线
        if (noiseHistoryIndex == 0 || !hasEstablishedNoise) {
            val sortedEnergies = noiseHistory.sortedArray()
            // 使用较低百分位数估计背景噪声
            noiseBaseline = sortedEnergies[noiseHistorySize / 5]
            hasEstablishedNoise = true
        }
        
        // 计算信噪比 - 当前能量与噪声基线的比值
        val signalToNoiseRatio = if (noiseBaseline > 0) energy / noiseBaseline else 1.0
        
        // 使用动态阈值 - 确保能量明显高于噪声基线
        val dynamicThreshold = if (hasEstablishedNoise) {
            // 至少需要比噪声基线高5倍，或达到固定阈值
            max(noiseBaseline * 5.0, energyThreshold * 1.2)
        } else {
            energyThreshold * 1.2 // 提高固定阈值要求
        }
        
        // 检查是否符合语音特征：
        // 1. 能量必须高于动态阈值
        // 2. 信噪比必须高
        // 3. ZCR(过零率)必须在合理范围内 - 人声通常在0.1到0.3之间
        val isPotentialVoice = energy > dynamicThreshold &&
                signalToNoiseRatio > 2.0 &&
                zcr > 0.05 && zcr < 0.5
        
        // 更新连续语音帧计数器
        if (isPotentialVoice) {
            consecutiveVoiceFrames++
            if (consecutiveVoiceFrames > 20) {  // 限制计数器上限
                consecutiveVoiceFrames = 20
            }
        } else {
            // 快速减少计数器，防止噪声积累
            consecutiveVoiceFrames -= 2
            if (consecutiveVoiceFrames < 0) {
                consecutiveVoiceFrames = 0
            }
        }
        
        // 检查特征历史中是否有稳定的语音模式
        var stableVoicePattern = false
        if (featureHistoryIndex >= featureHistorySize - 1) {
            println("[DEBUG] 潜在语音检测: 能量=$energy, 阈值=$dynamicThreshold, SNR=$signalToNoiseRatio, ZCR=$zcr")
            var stableFrames = 0
            // 检查能量稳定性和ZCR稳定性
            for (i in 0 until featureHistorySize - 1) {
                val energyRatio = energyHistory[i+1] / energyHistory[i]
                // 能量变化在0.7-1.3之间视为稳定
                if (energyRatio > 0.7 && energyRatio < 1.3 && 
                    zcrHistory[i] > 0.1 && zcrHistory[i] < 0.3) {
                    stableFrames++
                }
            }
            stableVoicePattern = stableFrames >= 3 // 至少3帧稳定
        }
        
        // 必须同时满足条件：
        // 1. 有足够连续帧被检测为潜在语音
        // 2. 特征历史表明有稳定的语音模式 或 当前帧信噪比极高
        val isVoiceActive = consecutiveVoiceFrames >= minConsecutiveFramesForVoice && 
                           (stableVoicePattern || signalToNoiseRatio > 8.0)
        
        if (isVoiceActive) {
            println("[DEBUG] 检测到有效语音: 能量=$energy, SNR=$signalToNoiseRatio, ZCR=$zcr, 连续帧=$consecutiveVoiceFrames")
        }
        
        return isVoiceActive
    }

    override fun applyNoiseGate(audioData: ShortArray): ShortArray {
        // 简化版，直接返回原始数据
//        return audioData
        
        val result = ShortArray(audioData.size)

        // 先计算平均背景噪声水平（如果尚未初始化）
        if (backgroundNoiseLevel == 0.0) {
            var sum = 0.0
            for (sample in audioData) {
                sum += abs(sample.toDouble())
            }
            backgroundNoiseLevel = sum / audioData.size * 0.8 // 使用80%作为保守估计
        }

        // 应用噪声门限 - 使用更严格的阈值
        val effectiveThreshold = max(noiseGateThreshold, backgroundNoiseLevel * 1.5)
        for (i in audioData.indices) {
            val sample = audioData[i]
            if (abs(sample.toDouble()) > effectiveThreshold) {
                result[i] = sample
            } else {
                result[i] = 0 // 低于阈值的信号设为0
            }
        }

        return result
    }

    override fun containsValidVoice(audioData: ShortArray): Boolean {
        // 简化版本，暂时返回true以便调试
//        println("[DEBUG] 检查有效语音 - 暂时返回true以便调试")
//        return true
        
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
        
        // 计算能量的方差 - 人声通常有明显的能量波动
        var meanEnergy = 0.0
        val frameSize = 160 // 约10ms
        val frames = audioData.size / frameSize
        val frameEnergies = DoubleArray(frames) { 0.0 }
        
        // 计算每一帧的能量
        for (f in 0 until frames) {
            var frameEnergy = 0.0
            val startIdx = f * frameSize
            val endIdx = minOf((f + 1) * frameSize, audioData.size)
            
            for (i in startIdx until endIdx) {
                frameEnergy += audioData[i] * audioData[i]
            }
            frameEnergy /= (endIdx - startIdx)
            frameEnergies[f] = frameEnergy
            meanEnergy += frameEnergy
        }
        meanEnergy /= frames
        
        // 计算能量方差
        var energyVariance = 0.0
        for (f in 0 until frames) {
            energyVariance += (frameEnergies[f] - meanEnergy).pow(2)
        }
        energyVariance /= frames
        
        // 计算归一化能量方差
        val normalizedEnergyVariance = if (meanEnergy > 0) sqrt(energyVariance) / meanEnergy else 0.0
        
        // 动态调整RMS阈值 - 但设置更严格的下限
        if (rms < adaptiveRmsThreshold) {
            silenceCounter++
            if (silenceCounter > maxSilenceBeforeAdapt) {
                // 连续检测到多次"无声"，降低阈值，但保持合理下限
                adaptiveRmsThreshold *= adaptationFactor
                adaptiveRmsThreshold = max(adaptiveRmsThreshold, validVoiceRmsThreshold * 0.7) // 提高下限
                silenceCounter = 0
                println("[DEBUG] 调整RMS阈值到: $adaptiveRmsThreshold")
            }
        } else {
            silenceCounter = 0
            // 检测到"有声"，快速恢复阈值
            adaptiveRmsThreshold = (adaptiveRmsThreshold * 0.8) + (validVoiceRmsThreshold * 0.2)
        }

        // 使用更严格的条件判断是否为有效语音:
        // 1. RMS必须高于阈值
        // 2. ZCR必须在人声范围内
        // 3. 能量方差必须够大（人声有明显波动）

        val hasVoice = rms > adaptiveRmsThreshold && 
                       zcr >= 0.1 && zcr <= 0.3 && 
                       normalizedEnergyVariance > 0.1 //能量方差阈值
                       
        if (hasVoice) {
            println("[DEBUG] 音频分析 - RMS: $rms, ZCR: $zcr, 能量方差: $normalizedEnergyVariance")
        }
        return hasVoice
    }

    /**
     * 重置分析器状态
     */
    fun reset() {
        backgroundNoiseLevel = 0.0
        noiseBaseline = 0.0
        hasEstablishedNoise = false
        consecutiveVoiceFrames = 0
        adaptiveRmsThreshold = validVoiceRmsThreshold
        silenceCounter = 0
        
        // 清空噪声历史
        for (i in noiseHistory.indices) {
            noiseHistory[i] = 0.0
        }
        noiseHistoryIndex = 0
        
        // 清空特征历史
        for (i in energyHistory.indices) {
            energyHistory[i] = 0.0
            zcrHistory[i] = 0.0
        }
        featureHistoryIndex = 0
    }
}