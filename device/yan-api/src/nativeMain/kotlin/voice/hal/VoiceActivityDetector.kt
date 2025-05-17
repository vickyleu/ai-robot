package voice.hal

import voice.util.LogManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 语音活动检测器
 * 用于检测音频中是否包含人声以及静音状态
 */
class VoiceActivityDetector(
    // 能量阈值：振幅的平方和超过此值认为有声音
    private var energyThreshold: Double = 300.0,
    // 过零率下限：过零率超过此值可能是语音
    private var zcrLowerThreshold: Double = 0.1,
    // 过零率上限：过零率超过此值可能是噪声
    private var zcrUpperThreshold: Double = 0.3,
    // 连续帧计数器阈值：连续检测到指定帧数才算有效
    private val consecutiveFrameThreshold: Int = 3,
    // 静音能量阈值：低于此值视为静音
    private var silenceThreshold: Double = 100.0,
    // 静音持续时间：静音超过此帧数视为有效静音
    private val silenceDurationThreshold: Int = 10
) {
    private val logger = LogManager.getLogger("VoiceActivityDetector")
    
    // 连续检测到语音的帧数
    private var consecutiveVoiceFrames = 0
    // 连续检测到静音的帧数
    private var consecutiveSilenceFrames = 0
    // 是否处于语音状态
    private var isInVoiceState = false
    
    // 历史RMS值，用于动态调整阈值
    private val rmsHistory = mutableListOf<Double>()
    private val historyMaxSize = 50
    
    /**
     * 检测音频帧是否包含人声
     * @param audioData 音频数据
     * @return 是否检测到人声
     */
    fun detectVoice(audioData: ShortArray): Boolean {
        // 计算能量和过零率
        val energy = calculateEnergy(audioData)
        val zcr = calculateZeroCrossingRate(audioData)
        
        // 更新RMS历史
        val rms = sqrt(energy / audioData.size)
        updateRmsHistory(rms)
        
        // 人声检测逻辑：
        // 1. 能量需要超过阈值
        // 2. 过零率需要在特定范围内（避免高频噪音）
        val isVoiceFrame = energy > energyThreshold && 
                           zcr > zcrLowerThreshold && 
                           zcr < zcrUpperThreshold

        if (isVoiceFrame) {
            consecutiveVoiceFrames++
            consecutiveSilenceFrames = 0
            
            // 连续多帧都检测到语音才认为是有效人声
            if (consecutiveVoiceFrames >= consecutiveFrameThreshold) {
                if (!isInVoiceState) {
                    isInVoiceState = true
                    logger.debug("检测到语音开始")
                }
                return true
            }
        } else {
            consecutiveVoiceFrames = 0
            
            // 当前状态为有声，但检测为无声，考虑是否静音过长
            if (isInVoiceState) {
                consecutiveSilenceFrames++
                if (consecutiveSilenceFrames >= silenceDurationThreshold) {
                    isInVoiceState = false
                    logger.debug("检测到语音结束")
                }
            }
        }
        
        // 维持当前状态
        return isInVoiceState
    }
    
    /**
     * 检测是否处于静音状态
     * @param audioData 音频数据
     * @return 是否静音
     */
    fun detectSilence(audioData: ShortArray): Boolean {
        val energy = calculateEnergy(audioData)
        
        // 静音检测：能量低于静音阈值
        val isSilentFrame = energy < silenceThreshold
        
        if (isSilentFrame) {
            consecutiveSilenceFrames++
            if (consecutiveSilenceFrames >= silenceDurationThreshold) {
                return true
            }
        } else {
            consecutiveSilenceFrames = 0
        }
        
        return false
    }
    
    /**
     * 计算音频能量
     * @param audioData 音频数据
     * @return 音频能量
     */
    private fun calculateEnergy(audioData: ShortArray): Double {
        var sum = 0.0
        for (sample in audioData) {
            sum += sample * sample
        }
        return sum / audioData.size
    }
    
    /**
     * 计算过零率
     * @param audioData 音频数据
     * @return 过零率
     */
    private fun calculateZeroCrossingRate(audioData: ShortArray): Double {
        if (audioData.isEmpty()) return 0.0
        
        var crossings = 0
        for (i in 1 until audioData.size) {
            if ((audioData[i] >= 0 && audioData[i-1] < 0) || 
                (audioData[i] < 0 && audioData[i-1] >= 0)) {
                crossings++
            }
        }
        
        return crossings.toDouble() / (audioData.size - 1)
    }
    
    /**
     * 更新RMS历史并调整阈值
     */
    private fun updateRmsHistory(rms: Double) {
        rmsHistory.add(rms)
        
        // 保持历史大小
        if (rmsHistory.size > historyMaxSize) {
            rmsHistory.removeAt(0)
        }
        
        // 每10个样本调整一次阈值
        if (rmsHistory.size >= 10 && rmsHistory.size % 10 == 0) {
            adaptThresholds()
        }
    }
    
    /**
     * 根据历史音频自适应调整阈值
     */
    private fun adaptThresholds() {
        if (rmsHistory.isEmpty()) return
        
        // 计算RMS平均值和标准差
        val mean = rmsHistory.average()
        val std = sqrt(rmsHistory.map { (it - mean) * (it - mean) }.average())
        
        // 调整能量阈值：平均值上下波动不应太大
        if (std / mean < 0.5) { // 相对稳定的背景
            // 如果背景相对稳定，将阈值设置为平均值的1.5倍
            val newEnergyThreshold = mean * mean * 1.5
            
            // 避免阈值变化太剧烈
            energyThreshold = energyThreshold * 0.7 + newEnergyThreshold * 0.3
            
            // 调整静音阈值为能量阈值的1/3
            silenceThreshold = energyThreshold / 3.0
            
            logger.debug("自适应调整阈值: 能量=$energyThreshold, 静音=$silenceThreshold")
        }
    }
    
    /**
     * 重置检测器状态
     */
    fun reset() {
        consecutiveVoiceFrames = 0
        consecutiveSilenceFrames = 0
        isInVoiceState = false
        rmsHistory.clear()
    }
} 