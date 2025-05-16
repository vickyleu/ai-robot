@file:OptIn(ExperimentalForeignApi::class)

package snowboyPiper.impl

import com.airobot.core.utils.FormatUtil
import com.airobot.core.utils.FormatUtil.formatDouble
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.set
import kotlinx.datetime.Clock.System
import snowboyPiper.interfaces.AudioAnalyzer
import snowboyPiper.interop.AudioProcessingResourceManager
import snowboyPiper.interop.RNNoiseSingleton
import snowboyPiper.interop.SpeexDspProcessor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 增强版音频分析器
 * 提供高级音频特征分析、自适应环境噪声检测及AGC功能
 * 专为资源受限设备(如树莓派)优化，确保高效低延迟运行
 */
class EnhancedAudioAnalyzer(
    energyThreshold: Double = 300.0,
    noiseGateThreshold: Double = 150.0,
    validVoiceRmsThreshold: Double = 500.0,
    validVoiceZcrThreshold: Double = 0.1
) : AudioAnalyzer {

    // 音频特征分析相关参数
    private var rmsEnergyThreshold = energyThreshold
    private var noiseFloorThreshold = noiseGateThreshold
    private var voiceRmsThreshold = validVoiceRmsThreshold
    private var voiceZcrThreshold = validVoiceZcrThreshold

    // 使用单例资源管理器获取预热的语音增强组件
    private val speexDsp: SpeexDspProcessor? = AudioProcessingResourceManager.getSpeexDsp()
    private val rnnoise = RNNoiseSingleton.getInstance()

    // 噪声环境自适应控制参数
    private var currentNoiseFloor = 100.0
    private var noiseEstimateWindow = FloatArray(20) // 存储最近的噪声估计值
    private var noiseEstimatePos = 0
    private var noiseAdaptationRate = 0.03f // 噪声适应速率

    // 增益控制参数
    private var currentGain = 1.0f
    private var targetGain = 1.0f
    private var gainAdaptationRate = 0.05f
    private var maxGain = 3.0f // 防止过度放大导致失真
    private var minGain = 0.5f // 防止过度衰减导致丢失信号

    // 性能监控
    private var processingTimeSum = 0.0
    private var processedFrameCount = 0
    private var maxProcessTime = 0.0

    // 分析样本计数
    private var sampleCounter = 0

    // 临时缓冲区
    private val tempFloatBuffer = FloatArray(2048) // 预分配足够大的缓冲区

    // 信号统计
    private var voiceDetectionCount = 0
    private var noiseFrameCount = 0
    private var silenceFrameCount = 0

    // 最新的音频统计数据
    private var lastRmsEnergy = 0.0
    private var lastZeroCrossingRate = 0.0
    private var lastSpectralFlatness = 0.0
    private var lastVoiceProbability = 0.0

    // 记录上次检测到的语音活动
    private var lastVoiceActivityResult = false

    init {
        println("[INFO] EnhancedAudioAnalyzer初始化完成，使用参数:")
        println("[INFO] 能量阈值=${rmsEnergyThreshold}, 噪声门限=${noiseFloorThreshold}")
        println("[INFO] 语音RMS阈值=${voiceRmsThreshold}, 过零率阈值=${voiceZcrThreshold}")
    }

    /**
     * 检测音频数据中是否含有语音活动
     * @param buffer 音频数据
     * @return 是否检测到语音活动
     */
    override fun hasVoiceActivity(buffer: ShortArray): Boolean {
        // 使用analyze方法实现，保持接口兼容性
        return analyze(buffer)
    }

    /**
     * 应用噪声门限，去除无用噪音
     * @param audioData 原始音频数据
     * @return 降噪后的音频数据
     */
    override fun applyNoiseGate(audioData: ShortArray): ShortArray {
        val energy = calculateRMSEnergy(audioData)

        // 如果能量低于噪声门限，返回静音帧
        if (energy < noiseFloorThreshold) {
            return ShortArray(audioData.size)
        }

        // 否则应用增益
        return applyGain(audioData)
    }

    /**
     * 检查音频是否包含有效的语音信号
     * @param audioData 音频数据
     * @return 是否包含有效语音
     */
    override fun containsValidVoice(audioData: ShortArray): Boolean {
        val rmsEnergy = calculateRMSEnergy(audioData)
        val zcr = calculateZeroCrossingRate(audioData)
        val voiceProbability = estimateVoiceProbability(audioData)

        // 返回是否同时满足多个语音特征要求
        return rmsEnergy > voiceRmsThreshold &&
                zcr > voiceZcrThreshold &&
                voiceProbability > 0.6
    }

    /**
     * 通知分析器刚刚播放了音频，用于回声消除
     * @param audioData 播放的音频数据
     */
    override fun notifyAudioPlayback(audioData: ShortArray) {
        // 将播放数据传递给SpeexDSP用于回声消除
        speexDsp?.setPlaybackReference(audioData)
    }

    /**
     * 重置分析器状态
     */
    override fun reset() {
        // 重置统计数据
        voiceDetectionCount = 0
        noiseFrameCount = 0
        silenceFrameCount = 0

        // 重置噪声估计
        currentNoiseFloor = 100.0
        for (i in noiseEstimateWindow.indices) {
            noiseEstimateWindow[i] = 0.0f
        }
        noiseEstimatePos = 0

        // 重置增益
        currentGain = 1.0f
        targetGain = 1.0f

        println("[INFO] 音频分析器已重置")
    }

    /**
     * 分析音频帧，提取特征并进行语音活动检测
     * @param audioData 输入音频数据 (16位PCM)
     * @return 是否检测到有效语音
     */
    fun analyze(audioData: ShortArray): Boolean {
        // 性能监控开始
        val startTime = System.now().toEpochMilliseconds()

        // 每1000个样本进行环境参数调整
        if (sampleCounter++ % 1000 == 0) {
            updateEnvironmentParameters()
        }

        try {
            // 检查有效输入
            if (audioData.isEmpty()) {
                return false
            }

            // 计算基本音频特征
            val rmsEnergy = calculateRMSEnergy(audioData)
            val zeroCrossingRate = calculateZeroCrossingRate(audioData)
            val spectralFeatures = calculateSpectralFeatures(audioData)

            // 保存最新统计数据用于状态报告
            lastRmsEnergy = rmsEnergy
            lastZeroCrossingRate = zeroCrossingRate
            lastSpectralFlatness = spectralFeatures.first

            // 应用RNNoise进行语音概率估计
            val voiceProbability = estimateVoiceProbability(audioData)
            lastVoiceProbability = voiceProbability

            // 使用组合条件进行语音活动检测
            lastVoiceActivityResult = isVoiceDetected(rmsEnergy, zeroCrossingRate, voiceProbability)

            // 更新统计计数器
            if (lastVoiceActivityResult) {
                voiceDetectionCount++
            } else if (rmsEnergy < noiseFloorThreshold) {
                silenceFrameCount++
            } else {
                noiseFrameCount++
            }

            // 自适应更新噪声基线
            updateNoiseFloor(rmsEnergy, lastVoiceActivityResult)

            // 更新自适应增益
            updateAdaptiveGain(rmsEnergy, lastVoiceActivityResult)

            // 计算性能指标
            val processingTime =
                (System.now().toEpochMilliseconds() - startTime) / 1_000_000.0 // 转换为毫秒
            processingTimeSum += processingTime
            processedFrameCount++
            if (processingTime > maxProcessTime) {
                maxProcessTime = processingTime
            }

            // 定期输出性能统计
            if (processedFrameCount % 500 == 0) {
                val avgProcessTime = processingTimeSum / processedFrameCount
                println(
                    "[PERF] 音频分析平均耗时: ${
                        formatDouble(
                            avgProcessTime,
                            3
                        )
                    }ms, 最大: ${formatDouble(maxProcessTime, 3)}ms, 帧数: $processedFrameCount"
                )
            }

            return lastVoiceActivityResult
        } catch (e: Exception) {
            println("[ERROR] 音频分析异常: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    /**
     * 计算音频信号的RMS能量
     * @param audioData 音频数据
     * @return RMS能量值
     */
    private fun calculateRMSEnergy(audioData: ShortArray): Double {
        var sumSquared = 0.0
        for (sample in audioData) {
            val normalizedSample = sample / 32768.0 // 归一化到[-1,1]范围
            sumSquared += normalizedSample * normalizedSample
        }
        return sqrt(sumSquared / audioData.size) * 100 // 缩放到更易读的范围
    }

    /**
     * 计算音频的过零率 (Zero Crossing Rate)
     * 过零率是衡量信号频率内容的简单方法，语音通常有较高的ZCR
     * @param audioData 音频数据
     * @return 过零率 (0.0-1.0)
     */
    private fun calculateZeroCrossingRate(audioData: ShortArray): Double {
        if (audioData.size <= 1) return 0.0

        var crossings = 0
        for (i in 1 until audioData.size) {
            if ((audioData[i] >= 0 && audioData[i - 1] < 0) ||
                (audioData[i] < 0 && audioData[i - 1] >= 0)
            ) {
                crossings++
            }
        }

        // 归一化到[0,1]范围
        return crossings.toDouble() / (audioData.size - 1)
    }

    /**
     * 计算信号的谱平坦度和谱质心等频谱特征
     * 返回一个包含谱平坦度和谱质心的Pair
     */
    private fun calculateSpectralFeatures(audioData: ShortArray): Pair<Double, Double> {
        // 这里使用简化计算，在资源受限设备上避免FFT计算
        // 返回一个基于能量和过零率的近似值

        // 在实际应用中，这里可以实现一个轻量级的FFT计算
        // 但对于语音检测目的，基本特征往往已经足够

        val energy = calculateRMSEnergy(audioData)
        val zcr = calculateZeroCrossingRate(audioData)

        // 谱平坦度的粗略估计 (0=纯音调，1=白噪声)
        val approximateFlatness = min(1.0, max(0.0, zcr * 2.0))

        // 谱质心的粗略估计
        val approximateCentroid = energy * zcr * 5000.0

        return Pair(approximateFlatness, approximateCentroid)
    }

    /**
     * 使用RNNoise估计语音概率
     * @param audioData 音频数据
     * @return 语音概率 (0.0-1.0)
     */
    private fun estimateVoiceProbability(audioData: ShortArray): Double {
        // 使用RNNoise处理器获取语音概率估计
        if (rnnoise == null) return 0.5 // 如果RNNoise不可用，返回中间值

        try {
            // 创建必要的缓冲区
            val frameCount = audioData.size
            val inputBuffer = nativeHeap.allocArray<ShortVar>(frameCount)
            val outputBuffer = nativeHeap.allocArray<ShortVar>(frameCount)

            // 复制数据到输入缓冲区
            for (i in 0 until frameCount) {
                inputBuffer[i] = audioData[i]
            }

            // 创建VAD概率数组
            val maxVadValues = frameCount / 480 + 1 // 每480样本一个VAD值
            val vadProbabilitiesPtr = nativeHeap.allocArray<FloatVar>(maxVadValues)

            // 使用RNNoise处理
            val processResult = RNNoiseSingleton.process(
                inputBuffer,
                outputBuffer,
                frameCount,
                vadProbabilitiesPtr,
                maxVadValues,
                0.08f, // 低阈值提高灵敏度
                1.5f   // 适中增益
            )

            // 计算平均语音概率
            var sumProb = 0.0f
            var count = 0
            var maxProb = 0.0f

            if (processResult > 0) {
                val totalFrames = minOf(processResult, maxVadValues)
                for (i in 0 until totalFrames) {
                    val prob = vadProbabilitiesPtr[i]
                    maxProb = max(maxProb, prob)
                    sumProb += prob
                    count++
                }
            }

            // 释放资源
            nativeHeap.free(inputBuffer.rawValue)
            nativeHeap.free(outputBuffer.rawValue)
            nativeHeap.free(vadProbabilitiesPtr.rawValue)

            // 返回最大概率作为结果
            return maxProb.toDouble()
        } catch (e: Exception) {
            println("[WARN] RNNoise处理异常: ${e.message}")
            return 0.5 // 出错时返回中间值
        }
    }

    /**
     * 基于多个特征组合判断是否检测到语音
     */
    private fun isVoiceDetected(
        energy: Double,
        zeroCrossingRate: Double,
        voiceProbability: Double
    ): Boolean {
        // 能量阈值判断
        val passesEnergyThreshold = energy > rmsEnergyThreshold

        // 过零率判断 (语音通常有一定的过零率，不会太低也不会太高)
        val passesZCRThreshold = zeroCrossingRate > voiceZcrThreshold &&
                zeroCrossingRate < 0.5

        // 语音概率判断
        val passesVoiceProbThreshold = voiceProbability > 0.6

        // 噪声门控判断 (能量必须高于噪声基线)
        val passesNoiseGate = energy > currentNoiseFloor + noiseFloorThreshold

        // 组合条件: 需要满足能量阈值和噪声门控，
        // 同时满足过零率条件或语音概率条件
        return passesEnergyThreshold && passesNoiseGate &&
                (passesZCRThreshold || passesVoiceProbThreshold)
    }

    /**
     * 自适应更新环境噪声基线
     */
    private fun updateNoiseFloor(energy: Double, isVoice: Boolean) {
        // 只在非语音段更新噪声基线
        if (!isVoice && energy > 0 && energy < voiceRmsThreshold) {
            // 更新噪声估计窗口
            noiseEstimateWindow[noiseEstimatePos] = energy.toFloat()
            noiseEstimatePos = (noiseEstimatePos + 1) % noiseEstimateWindow.size

            // 计算窗口平均值作为新的噪声基线
            var sum = 0.0f
            var count = 0
            for (value in noiseEstimateWindow) {
                if (value > 0) {
                    sum += value
                    count++
                }
            }

            if (count > 0) {
                val newNoiseFloor = sum / count
                // 平滑过渡到新的噪声基线
                currentNoiseFloor = currentNoiseFloor * (1 - noiseAdaptationRate) +
                        newNoiseFloor * noiseAdaptationRate
            }
        }
    }

    /**
     * 自适应增益控制
     */
    private fun updateAdaptiveGain(energy: Double, isVoice: Boolean) {
        // 确定目标增益
        if (isVoice) {
            // 对于语音，我们希望将能量带到一个理想范围
            val idealEnergy = 2000.0
            if (energy > 0) {
                // 计算所需增益以达到理想能量
                val newTargetGain = (idealEnergy / energy).toFloat()
                // 限制在合理范围内
                targetGain = max(minGain, min(maxGain, newTargetGain))
            }
        } else if (energy < noiseFloorThreshold) {
            // 对于安静段，略微提高增益
            targetGain = min(maxGain, currentGain * 1.05f)
        }

        // 平滑过渡到目标增益
        currentGain = currentGain * (1 - gainAdaptationRate) +
                targetGain * gainAdaptationRate

        // 确保增益在合理范围内
        currentGain = max(minGain, min(maxGain, currentGain))
    }

    /**
     * 应用自适应增益到音频数据
     * @param audioData 输入音频数据
     * @return 应用增益后的音频数据
     */
    fun applyGain(audioData: ShortArray): ShortArray {
        val gainedData = ShortArray(audioData.size)

        for (i in audioData.indices) {
            // 应用当前增益值
            val gainedValue = audioData[i] * currentGain
            // 限制在short范围内
            gainedData[i] = max(-32768.0f, min(32767.0f, gainedValue)).toInt().toShort()
        }

        return gainedData
    }

    /**
     * 更新环境参数，基于统计结果动态调整阈值
     */
    private fun updateEnvironmentParameters() {
        // 根据历史统计调整阈值
        val totalFrames = voiceDetectionCount + noiseFrameCount + silenceFrameCount
        if (totalFrames > 100) {
            // 噪声比例
            val noiseRatio = noiseFrameCount.toDouble() / totalFrames

            // 如果噪声比例过高，提高阈值
            if (noiseRatio > 0.8) {
                rmsEnergyThreshold = min(800.0, rmsEnergyThreshold * 1.1)
                noiseFloorThreshold = min(300.0, noiseFloorThreshold * 1.1)
            }
            // 如果噪声比例适中，微调阈值
            else if (noiseRatio > 0.5) {
                rmsEnergyThreshold = min(600.0, rmsEnergyThreshold * 1.05)
            }
            // 如果噪声比例较低，可以降低阈值提高灵敏度
            else if (noiseRatio < 0.3) {
                rmsEnergyThreshold = max(200.0, rmsEnergyThreshold * 0.95)
                noiseFloorThreshold = max(100.0, noiseFloorThreshold * 0.95)
            }

            // 重置计数器
            voiceDetectionCount = 0
            noiseFrameCount = 0
            silenceFrameCount = 0
        }
    }

    /**
     * 获取当前分析器状态报告
     * @return 状态报告字符串
     */
    fun getStatusReport(): String {
        val sb = StringBuilder()
        sb.append("=== 音频分析器状态报告 ===\n")
        sb.append("能量阈值: ${formatDouble(rmsEnergyThreshold, 1)}, ")
        sb.append("噪声门阈值: ${formatDouble(noiseFloorThreshold, 1)}\n")
        sb.append("当前噪声基线: ${formatDouble(currentNoiseFloor, 1)}, ")
        sb.append("当前增益: ${formatDouble(currentGain.toDouble(), 2)}\n")
        sb.append("最新RMS能量: ${formatDouble(lastRmsEnergy, 1)}, ")
        sb.append("最新过零率: ${formatDouble(lastZeroCrossingRate, 3)}\n")
        sb.append("最新语音概率: ${formatDouble(lastVoiceProbability, 2)}\n")

        if (processedFrameCount > 0) {
            val avgProcessTime = processingTimeSum / processedFrameCount
            sb.append("性能: 平均${formatDouble(avgProcessTime, 3)}ms/帧, ")
            sb.append("最大${formatDouble(maxProcessTime, 3)}ms, ")
            sb.append("总帧数: $processedFrameCount\n")
        }

        return sb.toString()
    }


}
