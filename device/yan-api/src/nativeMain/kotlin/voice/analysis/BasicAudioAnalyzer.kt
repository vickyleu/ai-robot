@file:OptIn(ExperimentalTime::class)

package com.airobot.device.yanapi.voice.analysis

import com.airobot.device.yanapi.voice.interfaces.AudioAnalyzer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * 基础音频分析器实现
 * 提供音频特征分析、噪声处理和语音活动检测
 * 集成了AdaptiveNoiseProfiler进行自适应噪声分析
 */
class BasicAudioAnalyzer(
    private val energyThreshold: Double = 600.0,        // 降低能量阈值(原为800.0)
    private val noiseGateThreshold: Double = 200.0,     // 降低噪声门限阈值(原为300.0)
    private val validVoiceRmsThreshold: Double = 700.0, // 降低有效语音RMS阈值(原为1000.0)
    private val validVoiceZcrThreshold: Double = 0.15   // 降低有效语音ZCR阈值(原为0.2)
) : AudioAnalyzer {

    // 添加自适应环境噪声分析器
    private val noiseProfiler = AdaptiveNoiseProfiler()

    private var backgroundNoiseLevel = 0.0
    private var adaptiveRmsThreshold = validVoiceRmsThreshold
    private val adaptationFactor = 0.75 // 提高适应因子(原为0.65)，加快系统适应环境变化
    private var silenceCounter = 0
    private val maxSilenceBeforeAdapt = 3 // 增加静音帧阈值(原为2)

    // 滑动窗口计算平均能量
    private val energyWindowSize = 10 // 减小窗口大小(原为12)，提高对短暂声音的响应速度
    private val energyWindow = DoubleArray(energyWindowSize) { 0.0 }
    private var energyWindowIndex = 0

    // 噪声抑制
    private var noiseFloor = 0.0
    private val noiseAdaptationRate = 0.96 // 提高噪声适应速度(原为0.94)
    private var isFirstFrame = true

    // 回声消除相关参数
    private var lastPlaybackTime = 0L
    private val echoSuppressionTimeMs = 600L // 减少回声抑制时间(原为800L)
    private val echoDynamicSuppressionFactor = 1.7 // 降低回声抑制因子(原为2.0)

    // 峰值保持器，用于更好地识别语音开始和结束
    private var peakEnergy = 0.0
    private val peakDecayRate = 0.97 // 提高峰值保持率(原为0.95)

    // 检测相关参数
    private var consecutiveVoiceFrames = 0
    private val minConsecutiveVoiceFrames = 3 // 降低连续语音帧需求(原为5)
    private var isVoiceActive = false

    // 噪音检测历史记录
    private val energyHistorySize = 20
    private val energyHistory = DoubleArray(energyHistorySize) { 0.0 }
    private var energyHistoryIndex = 0
    private var baselineNoiseEstablished = false

    // 保存最近检测到的语音事件时间
    private var lastVoiceDetectionTime = 0L
    private val minimumVoiceIntervalMs = 800L // 减少最小语音间隔(原为1000L)

    // 唤醒词检测相关参数
    private var lastWakewordPatternTime = 0L
    private val minWakewordPatternInterval = 2000L // 减少唤醒词间隔(原为3000L)

    /**
     * 分析音频数据的特征
     * @param audioData 音频数据
     * @return 音频特征Map，包含能量、ZCR等特征值
     */
    override fun analyzeFeatures(audioData: ShortArray): Map<String, Double> {
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

        // 更新环境噪声分析器
        noiseProfiler.processFrame(audioData, false)

        // 计算频谱能量分布（简化）
        val segmentCount = 5
        val segmentLength = audioData.size / segmentCount
        val segmentEnergies = DoubleArray(segmentCount) { 0.0 }

        for (i in 0 until segmentCount) {
            var segmentEnergy = 0.0
            val startIdx = i * segmentLength
            val endIdx = min((i + 1) * segmentLength, audioData.size)

            for (j in startIdx until endIdx) {
                segmentEnergy += (audioData[j] * audioData[j])
            }
            segmentEnergies[i] = segmentEnergy / (endIdx - startIdx)
        }

        return mapOf(
            "rms" to rms,
            "zcr" to zcr,
            "peakEnergy" to peakEnergy,
            "noiseFloor" to noiseFloor,
            "adaptiveThreshold" to noiseProfiler.getRecommendedEnergyThreshold(),
            "segment1" to segmentEnergies[0],
            "segment2" to segmentEnergies[1],
            "segment3" to segmentEnergies[2],
            "segment4" to segmentEnergies[3],
            "segment5" to segmentEnergies[4]
        )
    }

    /**
     * 检测音频数据中是否有人声活动 - 提高灵敏度
     */
    override fun hasVoiceActivity(audioData: ShortArray): Boolean {
        var energy = 0.0
        for (sample in audioData) {
            energy += (sample * sample)
        }
        energy /= audioData.size

        // 更新环境噪声分析器
        noiseProfiler.processFrame(audioData, false)

        // 更新能量历史
        energyHistory[energyHistoryIndex] = energy
        energyHistoryIndex = (energyHistoryIndex + 1) % energyHistorySize

        // 计算环境基线噪音
        if (energyHistoryIndex == 0 && !baselineNoiseEstablished) {
            // 计算基线噪声水平(使用较低百分位数)
            val sortedEnergies = energyHistory.sortedArray()
            val baselineNoise = sortedEnergies[energyHistorySize / 6] // 使用16.7%分位数(原为20%)
            noiseFloor = max(noiseFloor, baselineNoise * 1.1) // 降低系数(原为1.2)
            baselineNoiseEstablished = true
        }

        // 更新峰值能量
        if (energy > peakEnergy) {
            peakEnergy = energy
        } else {
            peakEnergy = peakEnergy * peakDecayRate + energy * (1.0 - peakDecayRate)
        }

        // 更新噪声底线
        if (isFirstFrame) {
            noiseFloor = energy * 0.5 // 降低初始噪声底线(原为0.6)
            isFirstFrame = false
        } else {
            // 动态更新噪声底线，适应环境
            if (energy < noiseFloor * 2.5) { // 提高系数(原为2.0)
                noiseFloor = noiseFloor * noiseAdaptationRate + energy * (1.0 - noiseAdaptationRate)
            }
        }

        // 使用滑动窗口计算平均能量，使用加权平均
        energyWindow[energyWindowIndex] = energy
        energyWindowIndex = (energyWindowIndex + 1) % energyWindowSize

        // 计算加权平均能量，近期样本权重更高
        var avgEnergy = 0.0
        var totalWeight = 0.0
        for (i in 0 until energyWindowSize) {
            val weight = 1.0 + (i * 0.15) // 提高权重差异(原为0.1)
            avgEnergy += energyWindow[(energyWindowIndex - i + energyWindowSize) % energyWindowSize] * weight
            totalWeight += weight
        }
        avgEnergy /= totalWeight

        // 如果处于回声抑制期，提高阈值
        val currentTime = Clock.System.now().toEpochMilliseconds()
        val timeSincePlayback = currentTime - lastPlaybackTime
        val echoSuppressionActive = timeSincePlayback < echoSuppressionTimeMs

        // 使用动态阈值 - 从AdaptiveNoiseProfiler获取并降低
        val dynamicEnergyThreshold = noiseProfiler.getRecommendedEnergyThreshold() * 0.8 // 降低到80%(新增)

        // 与动态阈值比较，基于噪声底线、峰值能量和回声抑制状态
        val dynamicThreshold = if (echoSuppressionActive) {
            // 回声抑制期内使用更高的阈值，动态衰减
            val echoFactor = max(
                1.0,
                echoDynamicSuppressionFactor * (1.0 - timeSincePlayback.toDouble() / echoSuppressionTimeMs)
            )
            max(dynamicEnergyThreshold * echoFactor, noiseFloor * 6.0) // 降低系数(原为8.0)
        } else {
            // 正常情况下，使用基于环境的自适应阈值
            max(dynamicEnergyThreshold, noiseFloor * 4.0) // 降低系数(原为5.0)
        }

        // 判断是否过于频繁触发
        val timeSinceLastVoice = currentTime - lastVoiceDetectionTime
        val minIntervalReached = timeSinceLastVoice > minimumVoiceIntervalMs

        // 使用连续帧检测来提高稳定性
        val currentFrameHasVoice = avgEnergy > dynamicThreshold && minIntervalReached

        if (currentFrameHasVoice) {
            consecutiveVoiceFrames++
            if (consecutiveVoiceFrames >= minConsecutiveVoiceFrames) { // 需要更多连续帧
                isVoiceActive = true
                lastVoiceDetectionTime = currentTime
            }
        } else {
            // 更慢地减少连续帧计数，提高灵敏度
            if (consecutiveVoiceFrames > 0) {
                // 每次只减一，而不是重置为0
                consecutiveVoiceFrames--
            }
            
            if (isVoiceActive) {
                // 添加一些粘性，避免声音断断续续，更慢结束语音状态
                isVoiceActive = avgEnergy > dynamicThreshold * 0.7 // 降低保持阈值(原为0.9)
            }
        }

        return isVoiceActive
    }

    /**
     * 应用噪声门限处理 - 保留更多细节
     */
    override fun applyNoiseGate(audioData: ShortArray): ShortArray {
        val result = ShortArray(audioData.size)

        // 计算整体能量水平，用于自适应噪声门限
        var sumSquares = 0.0
        for (sample in audioData) {
            sumSquares += (sample * sample)
        }
        val rms = sqrt(sumSquares / audioData.size)

        // 更新环境噪声分析器
        noiseProfiler.processFrame(audioData, false)

        // 使用动态噪声门限 - 基于AdaptiveNoiseProfiler的推荐
        val adaptiveThreshold = noiseProfiler.getRecommendedNoiseGateThreshold() * 0.5 // 降低到原有的50%(原为60%)

        // 检查是否可能为唤醒词 - 放宽判断条件
        var containsWakewordPattern = false
        if (audioData.size > 1400) { // 减少所需数据长度(原为1600)
            val segmentCount = 5
            val segmentLength = audioData.size / segmentCount
            val segmentEnergies = DoubleArray(segmentCount) { 0.0 }

            // 计算各段能量
            for (i in 0 until segmentCount) {
                var segmentEnergy = 0.0
                val startIdx = i * segmentLength
                val endIdx = min((i + 1) * segmentLength, audioData.size)

                for (j in startIdx until endIdx) {
                    segmentEnergy += (audioData[j] * audioData[j])
                }
                segmentEnergies[i] = segmentEnergy / (endIdx - startIdx)
            }

            // 使用AdaptiveNoiseProfiler来判断是否为唤醒词特征 - 放宽条件
            val avgZcr = calculateZeroCrossingRate(audioData)
            val avgEnergy = rms

            // 检查能量模式 - 结合AdaptiveNoiseProfiler的唤醒词特征判断
            val currentTime = Clock.System.now().toEpochMilliseconds()
            // 更容易检测到唤醒词模式
            if ((noiseProfiler.isPossibleWakeword(avgEnergy * 0.9, avgZcr) ||  // 降低能量要求
                 (avgEnergy > 600.0 && avgZcr in 0.05..0.7)) &&  // 额外条件判断
                (currentTime - lastWakewordPatternTime) > minWakewordPatternInterval
            ) {
                containsWakewordPattern = true
                lastWakewordPatternTime = currentTime
                println("[INFO] 检测到疑似唤醒词音频模式 - 能量：${avgEnergy.format(0)}, ZCR: ${avgZcr.format(2)}")
            }
        }

        // 基于是否可能包含唤醒词来调整处理策略
        val gateThreshold = if (containsWakewordPattern) {
            adaptiveThreshold * 0.4 // 进一步降低门限(原为0.5)
        } else {
            adaptiveThreshold
        }

        // 应用噪声门限和平滑处理 - 保留更多细节
        for (i in audioData.indices) {
            val sample = audioData[i]
            val sampleAbs = abs(sample.toInt())

            if (sampleAbs > gateThreshold) {
                // 对超过门限的样本应用渐变增益，以平滑处理
                val gain = 1.0 + ((sampleAbs - gateThreshold) / (32767 - gateThreshold) * 0.7) // 提高增益(原为0.5)
                result[i] = min(32767, (sample * gain).toInt()).toShort()
            } else if (containsWakewordPattern && sampleAbs > gateThreshold * 0.4) { // 降低阈值(原为0.5)
                // 针对疑似唤醒词的情况，保留更多的低能量信号
                result[i] = sample
            } else if (sampleAbs > gateThreshold * 0.3) { // 新增条件，保留更多信号
                // 对于接近但未达到门限的样本，适当保留
                val attenuationFactor = (sampleAbs / gateThreshold) * 0.7 // 0.3-0.7范围的衰减
                result[i] = (sample * attenuationFactor).toInt().toShort()
            } else {
                // 低于门限的样本设为0
                result[i] = 0
            }
        }

        return result
    }

    /**
     * 计算过零率
     */
    private fun calculateZeroCrossingRate(samples: ShortArray): Double {
        var crossings = 0
        for (i in 1 until samples.size) {
            if ((samples[i] > 0 && samples[i - 1] <= 0) || 
                (samples[i] <= 0 && samples[i - 1] > 0)) {
                crossings++
            }
        }
        return crossings.toDouble() / samples.size
    }

    /**
     * 检测音频数据是否包含有效人声 - 提高灵敏度
     */
    override fun containsValidVoice(audioData: ShortArray, thresholdFactor: Float): Boolean {
        // 更新环境噪声分析器
        noiseProfiler.processFrame(audioData, false)

        // 检查是否在回声抑制期内
        val currentTime = Clock.System.now().toEpochMilliseconds()
        val timeSincePlayback = currentTime - lastPlaybackTime

        // 使用较短的回声抑制时间
        val longEchoSuppression = timeSincePlayback < echoSuppressionTimeMs * 1.2 // 降低系数(原为1.5)

        if (longEchoSuppression) {
            // 在回声抑制期内，但降低要求
            var sumSquares = 0.0
            for (sample in audioData) {
                sumSquares += (sample * sample)
            }
            val rms = sqrt(sumSquares / audioData.size)

            // 回声抑制强度随时间衰减，且强度降低
            val suppressionFactor =
                1.0 + 4.0 * (1.0 - timeSincePlayback.toDouble() / (echoSuppressionTimeMs * 1.2)) // 降低系数

            // 获取动态语音阈值并应用thresholdFactor
            val dynamicRmsThreshold = noiseProfiler.getRecommendedValidVoiceRmsThreshold() * thresholdFactor

            // 回声期间对信号要求降低
            return rms > dynamicRmsThreshold * suppressionFactor
        }

        // 计算RMS
        var sumSquares = 0.0
        for (sample in audioData) {
            sumSquares += (sample * sample)
        }
        val rms = sqrt(sumSquares / audioData.size)

        // 计算ZCR
        var zeroCrossings = 0
        for (i in 1 until audioData.size) {
            if ((audioData[i] > 0 && audioData[i - 1] <= 0) ||
                (audioData[i] <= 0 && audioData[i - 1] > 0)
            ) {
                zeroCrossings++
            }
        }
        val zcr = zeroCrossings.toDouble() / audioData.size

        // 使用AdaptiveNoiseProfiler判断是否符合唤醒词特征 - 放宽条件
        val isWakewordPattern = noiseProfiler.isPossibleWakeword(rms * 0.85, zcr) // 降低能量要求

        if (isWakewordPattern) {
            println("[INFO] 检测到符合唤醒词特征的音频: ZCR=${zcr.format(2)}, RMS=${rms.format(0)}")
            return true
        }

        // 获取动态阈值并应用thresholdFactor
        val dynamicRmsThreshold = noiseProfiler.getRecommendedValidVoiceRmsThreshold() * thresholdFactor
        val dynamicZcrThreshold = noiseProfiler.getRecommendedValidVoiceZcrThreshold() * thresholdFactor

        // 动态调整RMS阈值 - 更快响应
        adaptiveRmsThreshold =
            max(dynamicRmsThreshold * 0.7, min(dynamicRmsThreshold * 1.1, rms * 0.75)) // 降低各阈值

        // 综合判断，使用动态阈值
        return rms > adaptiveRmsThreshold * 0.7 && zcr >= dynamicZcrThreshold * 0.7 // 降低判断阈值(原为0.8)
    }

    /**
     * 通知分析器刚刚播放了音频，需要暂时抑制回声
     */
    override fun notifyAudioPlayback() {
        lastPlaybackTime = Clock.System.now().toEpochMilliseconds()
        // 重置峰值能量，避免播放的音频影响后续检测
        peakEnergy = 0.0
        // 重置语音活动相关状态
        consecutiveVoiceFrames = 0
        isVoiceActive = false
    }

    /**
     * 重置分析器状态
     */
    override fun reset() {
        backgroundNoiseLevel = 0.0
        adaptiveRmsThreshold = validVoiceRmsThreshold
        silenceCounter = 0
        noiseFloor = 0.0
        isFirstFrame = true
        peakEnergy = 0.0
        // 重置语音检测状态
        consecutiveVoiceFrames = 0
        isVoiceActive = false
        baselineNoiseEstablished = false
        // 不重置lastPlaybackTime，保持回声抑制

        // 重置噪声分析器
        noiseProfiler.reset()

        // 清空能量窗口
        for (i in energyWindow.indices) {
            energyWindow[i] = 0.0
        }
        energyWindowIndex = 0

        // 清空能量历史
        for (i in energyHistory.indices) {
            energyHistory[i] = 0.0
        }
        energyHistoryIndex = 0
    }


}

// 格式化双精度数，用于日志输出
fun Double.format(decimals: Int): String {
    val factor = 10.0.pow(decimals.toDouble())
    val rounded = round(this * factor) / factor
    return rounded.toString()
}