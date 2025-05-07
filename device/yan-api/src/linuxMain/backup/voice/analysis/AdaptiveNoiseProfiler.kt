@file:OptIn(ExperimentalTime::class, ExperimentalTime::class)

package com.airobot.device.yanapi.voice.analysis

import kotlin.math.sqrt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * 自适应环境噪声分析器
 * 用于动态调整噪声阈值，减少误触发
 */
class AdaptiveNoiseProfiler {
    // 环境噪声历史
    private val historySize = 35  // 进一步减少历史大小(原为40)，加快响应环境变化
    private val energyHistory = DoubleArray(historySize) { 0.0 }
    private val zcrHistory = DoubleArray(historySize) { 0.0 }
    private var historyIndex = 0
    private var historyFilled = false

    // 噪声特征
    private var baselineNoiseEnergy = 0.0
    private var baselineNoiseZcr = 0.0
    private var noiseEnergyVariance = 0.0
    private var noiseZcrVariance = 0.0

    // 自适应参数
    private var adaptationRate = 0.90 // 进一步降低噪声基线适应速度(原为0.92)，更快适应环境
    private var varThresholdMultiplier = 1.8 // 进一步降低方差倍数(原为2.0)，降低阈值

    // 唤醒词模式检测
    private var lastWakewordPatternTime = 0L
    private val minWakewordPatternInterval = 1800L // 进一步减少间隔时间(原为2000L)

    // 初始化标志
    private var isInitialized = false

    // 新增的噪声阈值相关变量
    private var noiseThreshold = 0.0
    private var noiseThresholdFactor = 1.0
    private var noiseFloor = 0.0
    private var baseNoiseThreshold = 0.0
    private var wakewordPatternCount = 0
    private var consecutivePatternCount = 0

    /**
     * 初始化分析器，重置所有参数
     */
    fun reset() {
        for (i in energyHistory.indices) {
            energyHistory[i] = 0.0
            zcrHistory[i] = 0.0
        }
        historyIndex = 0
        historyFilled = false
        baselineNoiseEnergy = 0.0
        baselineNoiseZcr = 0.0
        noiseEnergyVariance = 0.0
        noiseZcrVariance = 0.0
        isInitialized = false
        noiseThreshold = 0.0
        noiseThresholdFactor = 1.0
        noiseFloor = 0.0
        baseNoiseThreshold = 0.0
        wakewordPatternCount = 0
        consecutivePatternCount = 0
    }

    /**
     * 处理一帧音频数据，更新噪声模型
     * @param audioData 音频数据
     * @param hasVoice 是否检测到语音活动
     */
    fun processFrame(audioData: ShortArray, hasVoice: Boolean) {
        // 计算RMS能量
        var sumSquares = 0.0
        for (sample in audioData) {
            sumSquares += (sample * sample)
        }
        val rms = sqrt(sumSquares / audioData.size)

        // 计算ZCR（过零率）
        var zeroCrossings = 0
        for (i in 1 until audioData.size) {
            if ((audioData[i] > 0 && audioData[i - 1] <= 0) ||
                (audioData[i] <= 0 && audioData[i - 1] > 0)
            ) {
                zeroCrossings++
            }
        }
        val zcr = zeroCrossings.toDouble() / audioData.size

        // 如果没有检测到语音，则更新噪声模型
        if (!hasVoice) {
            updateNoiseModel(rms, zcr)
        }
    }

    /**
     * 更新噪声模型
     * @param energy 当前帧能量
     * @param zcr 当前帧过零率
     */
    private fun updateNoiseModel(energy: Double, zcr: Double) {
        // 更新历史数据
        energyHistory[historyIndex] = energy
        zcrHistory[historyIndex] = zcr
        historyIndex = (historyIndex + 1) % historySize

        if (historyIndex == 0) {
            historyFilled = true
        }

        // 如果历史数据足够，计算噪声特征
        if (historyFilled) {
            // 计算能量均值
            var energySum = 0.0
            for (e in energyHistory) {
                energySum += e
            }
            val energyMean = energySum / historySize

            // 计算ZCR均值
            var zcrSum = 0.0
            for (z in zcrHistory) {
                zcrSum += z
            }
            val zcrMean = zcrSum / historySize

            // 计算能量方差
            var energyVarSum = 0.0
            for (e in energyHistory) {
                val diff = e - energyMean
                energyVarSum += diff * diff
            }
            val energyVar = energyVarSum / historySize

            // 计算ZCR方差
            var zcrVarSum = 0.0
            for (z in zcrHistory) {
                val diff = z - zcrMean
                zcrVarSum += diff * diff
            }
            val zcrVar = zcrVarSum / historySize

            // 更新基线噪声特征
            if (!isInitialized) {
                // 首次初始化
                baselineNoiseEnergy = energyMean
                baselineNoiseZcr = zcrMean
                noiseEnergyVariance = energyVar
                noiseZcrVariance = zcrVar
                isInitialized = true
            } else {
                // 逐渐适应环境变化
                baselineNoiseEnergy =
                    baselineNoiseEnergy * adaptationRate + energyMean * (1 - adaptationRate)
                baselineNoiseZcr =
                    baselineNoiseZcr * adaptationRate + zcrMean * (1 - adaptationRate)
                noiseEnergyVariance =
                    noiseEnergyVariance * adaptationRate + energyVar * (1 - adaptationRate)
                noiseZcrVariance = noiseZcrVariance * adaptationRate + zcrVar * (1 - adaptationRate)
            }
        }
    }

    /**
     * 获取当前推荐的能量阈值
     * 基于环境噪声特征动态调整
     * @return 推荐的能量阈值
     */
    fun getRecommendedEnergyThreshold(): Double {
        if (!isInitialized) return 500.0 // 进一步降低默认值(原为600.0)

        // 噪声能量 + 几个标准差，动态适应环境 - 降低系数
        return baselineNoiseEnergy + sqrt(noiseEnergyVariance) * varThresholdMultiplier
    }

    /**
     * 获取当前推荐的噪声门限
     * @return 推荐的噪声门限
     */
    fun getRecommendedNoiseGateThreshold(): Double {
        if (!isInitialized) return 150.0 // 进一步降低默认值(原为200.0)

        // 基于噪声能量特征 - 降低系数
        return baselineNoiseEnergy * 1.0 // 进一步降低系数(原为1.2)
    }

    /**
     * 获取当前推荐的有效语音RMS阈值
     * @return 推荐的有效语音RMS阈值
     */
    fun getRecommendedValidVoiceRmsThreshold(): Double {
        if (!isInitialized) return 700.0 // 进一步降低默认值(原为800.0)

        // 噪声能量 + 多个标准差，确保有足够的区分度 - 降低系数
        return baselineNoiseEnergy + sqrt(noiseEnergyVariance) * 2.5 // 进一步降低系数(原为3.0)
    }

    /**
     * 获取当前推荐的有效语音ZCR阈值
     * @return 推荐的有效语音ZCR阈值
     */
    fun getRecommendedValidVoiceZcrThreshold(): Double {
        if (!isInitialized) return 0.12 // 进一步降低默认值(原为0.15)

        // 要求显著高于噪声ZCR - 降低系数
        return baselineNoiseZcr + sqrt(noiseZcrVariance) * 1.2 // 进一步降低系数(原为1.6)
    }
    
    /**
     * 判断给定的音频特征是否可能包含唤醒词
     * 专门针对"小度你好"等中文唤醒词优化
     * @param energy 能量
     * @param zcr 过零率
     * @return 是否可能包含唤醒词
     */
    fun isPossibleWakeword(energy: Double, zcr: Double): Boolean {
        if (!isInitialized) return false

        // 获取当前时间戳
        val currentTime = Clock.System.now().toEpochMilliseconds()

        // 检查是否在最小间隔内
        if (currentTime - lastWakewordPatternTime < minWakewordPatternInterval) {
            return false
        }

        // 能量必须显著高于噪声基线 - 降低系数
        val energyThreshold = baselineNoiseEnergy + sqrt(noiseEnergyVariance) * 2.0 // 进一步降低系数(原为2.5)
        if (energy < energyThreshold) {
            // 额外检查：如果能量不太高但过零率特别合适，也可能是唤醒词
            if (energy > energyThreshold * 0.7 && zcr >= 0.08 && zcr <= 0.6) {
                println("[DEBUG] 检测到潜在唤醒词特征: ZCR=${zcr.format(2)}, RMS=${energy.format(0)}, 平坦度=${(energy/baselineNoiseEnergy).format(2)}")
                lastWakewordPatternTime = currentTime
                return true
            }
            return false
        }

        // 过零率应该在合适的范围内
        // 中文唤醒词通常有特定的过零率范围，区别于一般噪声
        val zcrLowerBound = baselineNoiseZcr + sqrt(noiseZcrVariance) * 0.2 // 进一步降低系数(原为0.3)
        val zcrUpperBound = baselineNoiseZcr + sqrt(noiseZcrVariance) * 3.0

        // 中文唤醒词的过零率特征 - 扩大范围
        val hasWakewordZcrPattern = zcr >= 0.03 && zcr <= 0.8 // 进一步扩大范围(原为0.05-0.75)

        if (hasWakewordZcrPattern && energy > energyThreshold) {
            println("[DEBUG] 检测到潜在唤醒词特征: ZCR=${zcr.format(2)}, RMS=${energy.format(0)}, 平坦度=${(energy/baselineNoiseEnergy).format(2)}")
            lastWakewordPatternTime = currentTime
            return true
        }

        // 添加额外的兜底判断条件，提高灵敏度
        if (energy > energyThreshold * 1.3 && zcr >= 0.02 && zcr <= 0.85) { // 降低倍数并扩大范围
            // 高能量信号也有较高概率是唤醒词
            println("[DEBUG] 检测到高能量潜在唤醒词: ZCR=${zcr.format(2)}, RMS=${energy.format(0)}, 平坦度=${(energy/baselineNoiseEnergy).format(2)}")
            lastWakewordPatternTime = currentTime
            return true
        }

        return false
    }

    /**
     * 判断给定的音频数据是否可能包含唤醒词
     * @param audioData 音频数据
     * @return 是否可能包含唤醒词
     */
    fun analyzeForWakewordPattern(audioData: ShortArray): Boolean {
        // 计算RMS能量
        var sumSquares = 0.0
        for (sample in audioData) {
            sumSquares += (sample * sample)
        }
        val rms = sqrt(sumSquares / audioData.size)

        // 计算ZCR（过零率）
        var zeroCrossings = 0
        for (i in 1 until audioData.size) {
            if ((audioData[i] > 0 && audioData[i - 1] <= 0) ||
                (audioData[i] <= 0 && audioData[i - 1] > 0)
            ) {
                zeroCrossings++
            }
        }
        val zcr = zeroCrossings.toDouble() / audioData.size
        
        // 使用计算出的特征检测唤醒词模式
        return isPossibleWakeword(rms, zcr)
    }

    /**
     * 设置自适应速率
     * @param rate 适应速率，0-1之间，越大对环境变化适应越慢
     */
    fun setAdaptationRate(rate: Double) {
        if (rate in 0.0..1.0) {
            this.adaptationRate = rate
        }
    }

    /**
     * 设置方差倍数
     * @param multiplier 方差倍数，用于计算阈值
     */
    fun setVarianceThresholdMultiplier(multiplier: Double) {
        if (multiplier > 0) {
            this.varThresholdMultiplier = multiplier
        }
    }

    /**
     * 设置噪声阈值
     * @param noiseThreshold 新的噪声阈值
     */
    fun setNoiseThreshold(noiseThreshold: Double) {
        this.noiseThreshold = noiseThreshold
    }

//    /**
//     * 检测是否可能是唤醒词特征
//     */
//    fun isPossibleWakeword(rms: Double, zcr: Double): Boolean {
//        // 判断是否满足"小度小度"的声学特征 - 显著放宽条件
//        // 小度小度的特点:
//        // 1. 能量适中(不会特别大也不会特别小)
//        // 2. 过零率比较低(因为元音为主)
//
//        // 放宽条件，只要RMS能量在合理范围并且过零率不是特别高就视为可能的唤醒词
//        val energyMatch = rms in 100.0..2500.0 // 极大放宽能量范围
//        val zcrMatch = zcr < 0.30 // 提高过零率上限
//
//        if (energyMatch && zcrMatch) {
//            // 记录可能的唤醒词特征
//            wakewordPatternCount++
//            val currentTime = Clock.System.now().toEpochMilliseconds()
//
//            // 如果一段时间内检测到多个可能唤醒词模式，更新上次检测时间
//            if (currentTime - lastWakewordPatternTime < minWakewordPatternInterval) {
//                consecutivePatternCount++
//
//                // 只要连续检测到2次或以上可能的唤醒词模式，就认为是有效的
//                if (consecutivePatternCount >= 2) {
//                    lastWakewordPatternTime = currentTime
//                    return true
//                }
//            } else {
//                // 重置连续计数，但仍然更新上次检测时间
//                lastWakewordPatternTime = currentTime
//                consecutivePatternCount = 1
//            }
//        }
//
//        return false
//    }
    
    /**
     * 获取推荐的噪声阈值
     */
    fun getRecommendedNoiseThreshold(): Double {
        // 使用噪声分位数和系数计算推荐阈值
        return kotlin.math.max(noiseFloor * noiseThresholdFactor, baseNoiseThreshold)
    }
} 