package voice.audio.vad

import kotlinx.cinterop.ExperimentalForeignApi
import voice.api.vad.IVoiceActivityDetector
import voice.audio.AudioPipeline
import voice.audio.VADMetrics
import voice.util.LogManager
import kotlin.math.sqrt
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

/**
 * 语音活动检测器
 * 负责判断音频是否包含人声
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
class VoiceActivityDetector : IVoiceActivityDetector {
    private val logger = LogManager.getLogger("VoiceActivityDetector")
    private val config = VADConfig()
    
    // 语音检测状态
    private var isSpeechDetected = false
    private var lastSpeechTime = TimeSource.Monotonic.markNow()
    
    // 新增：语音状态转换跟踪
    private var speechEndDetected = false
    private var waitingForSpeechEnd = false
    
    // 保存历史能量用于环境噪声自适应
    private val energyHistory = DoubleArray(ENERGY_HISTORY_SIZE) { 0.0 }
    private var energyHistoryIndex = 0
    private var noiseFloor = 0.0
    
    // 统计数据
    private var totalFrames = 0
    private var speechFrames = 0
    
    // 调试计数器
    private var frameCounter = 0
    
    // 存储上一次检测到的能量，用于打印日志
    private var lastEnergy = 0.0
    
    // 连续语音/静音帧计数
    private var consecutiveSpeechFrames = 0
    private var consecutiveSilenceFrames = 0
    
    /**
     * 检测音频是否包含语音
     * @param audio 音频数据
     * @param length 数据长度
     * @return 检测结果
     */
    override fun detect(audio: ByteArray, length: Int): IVoiceActivityDetector.DetectionResult {
        totalFrames++
        
        // 输出原始音频长度
        logger.info("【调试】VAD收到音频: 长度=${length}, 帧序号=${totalFrames}")
        
        // 计算当前帧能量
        val samples = convertBytesToShorts(audio, length)
        val energy = calculateEnergy(samples)
        
        logger.info("【调试】VAD音频能量: $energy, 阈值: ${config.energyThreshold}")
        
        // 每100帧记录一次能量值
        frameCounter++
        if (frameCounter % 100 == 0 || (energy > config.energyThreshold && frameCounter % 10 == 0)) {
            logger.debug("音频能量: $energy, 阈值: ${config.energyThreshold}, 上次能量: $lastEnergy")
            lastEnergy = energy
        }
        
        // 更新噪声基准
        updateNoiseFloor(energy)
        
        // 计算信噪比
        val snr = if (noiseFloor > 0) energy / noiseFloor else 0.0
        
        // 使用严格条件判断当前帧是否为语音
        val speechProbability = calculateSpeechProbability(energy, snr)
        val currentIsSpeech = speechProbability > config.speechThreshold
        
        // 更新连续帧计数
        if (currentIsSpeech) {
            consecutiveSpeechFrames++
            consecutiveSilenceFrames = 0
        } else {
            consecutiveSilenceFrames++
            consecutiveSpeechFrames = 0
        }
        
        // 使用连续帧判断来防止误判
        val isStableSpeech = consecutiveSpeechFrames >= config.minConsecutiveSpeechFrames
        val isStableSilence = consecutiveSilenceFrames >= config.minConsecutiveSilenceFrames
        
        logger.info("【调试】VAD状态: 能量=${energy}, 噪声基准=${noiseFloor}, 信噪比=${snr}, 语音概率=${speechProbability}, 连续语音帧=${consecutiveSpeechFrames}, 连续静音帧=${consecutiveSilenceFrames}")
        
        // 语音检测状态处理（防抖动）
        handleSpeechState(isStableSpeech, isStableSilence)
        
        // 创建VAD指标
        val metrics = VADMetrics(
            energyLevel = energy,
            speechProbability = speechProbability,
            noiseLevel = noiseFloor
        )
        
        // 记录详细诊断
        if (totalFrames % 100 == 0) {
            logger.debug("VAD统计: 总帧数=${totalFrames}, 语音帧数=${speechFrames}, " +
                    "语音比例=${speechFrames.toFloat() / totalFrames.toFloat() * 100f}%, " +
                    "当前噪声基准=${noiseFloor}")
        }
        
        // 确定是否有语音和是否可以开始识别
        val hasSpeech = isSpeechDetected || speechEndDetected
        
        // 如果检测到语音结束，在返回一次hasSpeech=true后重置状态
        if (speechEndDetected) {
            logger.info("【重要】语音片段已结束，触发一次识别")
            speechEndDetected = false
        }
        
        logger.info("【调试】VAD最终结果: 检测到语音=${hasSpeech}, 信心指数=${speechProbability}")
        
        return IVoiceActivityDetector.DetectionResult(
            hasSpeech = hasSpeech,
            confidence = speechProbability,
            metrics = metrics
        )
    }
    
    /**
     * 处理语音状态（防抖动）
     */
    private fun handleSpeechState(isStableSpeech: Boolean, isStableSilence: Boolean) {
        val now = TimeSource.Monotonic.markNow()
        
        if (isStableSpeech) {
            // 检测到稳定语音，更新最后语音时间
            lastSpeechTime = now
            
            if (!isSpeechDetected) {
                logger.info("开始检测到语音")
                isSpeechDetected = true
                // 当开始检测到语音时设置等待语音结束标志
                waitingForSpeechEnd = true
                speechEndDetected = false
            }
            
            speechFrames++
        } else if (isStableSilence) {
            // 检测到稳定静音，检查是否超过保持时间
            val elapsed = now - lastSpeechTime
            if (isSpeechDetected && elapsed.inWholeMilliseconds > config.speechHoldTimeMs) {
                logger.info("语音结束，持续 ${elapsed.inWholeMilliseconds} ms")
                
                // 如果正在等待语音结束，标记语音结束事件
                if (waitingForSpeechEnd) {
                    speechEndDetected = true
                    waitingForSpeechEnd = false
                    logger.info("检测到完整语音片段，可以开始识别")
                }
                
                isSpeechDetected = false
            }
        }
    }
    
    /**
     * 计算语音概率
     */
    private fun calculateSpeechProbability(energy: Double, snr: Double): Float {
        // 严格执行能量阈值判断
        if (energy < config.energyThreshold) {
            return 0.0f
        }
        
        // 强制要求信噪比达到阈值
        if (snr < config.snrThreshold) {
            return 0.0f
        }
        
        // 基于能量和信噪比计算语音概率，提高能量权重
        val energyFactor = minOf(1.0, (energy - config.energyThreshold) / config.energyThreshold)
        val snrFactor = minOf(1.0, (snr - config.snrThreshold) / config.snrThreshold)
        
        return (energyFactor * 0.8 + snrFactor * 0.2).toFloat()
    }
    
    /**
     * 更新噪声基准
     */
    private fun updateNoiseFloor(energy: Double) {
        // 更新能量历史
        energyHistory[energyHistoryIndex] = energy
        energyHistoryIndex = (energyHistoryIndex + 1) % ENERGY_HISTORY_SIZE
        
        // 计算能量历史中较低的值作为噪声基准
        // 在Kotlin/Native中，使用复制数组和手动排序代替clone()
        val sortedEnergies = DoubleArray(ENERGY_HISTORY_SIZE)
        for (i in 0 until ENERGY_HISTORY_SIZE) {
            sortedEnergies[i] = energyHistory[i]
        }
        
        // 简单的冒泡排序
        for (i in 0 until ENERGY_HISTORY_SIZE) {
            for (j in 0 until ENERGY_HISTORY_SIZE - 1 - i) {
                if (sortedEnergies[j] > sortedEnergies[j + 1]) {
                    val temp = sortedEnergies[j]
                    sortedEnergies[j] = sortedEnergies[j + 1]
                    sortedEnergies[j + 1] = temp
                }
            }
        }
        
        // 使用前20%的能量作为噪声基准
        val noiseThreshold = (ENERGY_HISTORY_SIZE * 0.2).toInt()
        var noiseSum = 0.0
        for (i in 0 until noiseThreshold) {
            noiseSum += sortedEnergies[i]
        }
        val noiseLevel = if (noiseThreshold > 0) noiseSum / noiseThreshold else 0.0
        
        // 平滑更新噪声基准
        if (noiseFloor == 0.0) {
            noiseFloor = noiseLevel
        } else {
            noiseFloor = noiseFloor * 0.95 + noiseLevel * 0.05
        }
    }
    
    /**
     * 计算音频能量
     */
    private fun calculateEnergy(samples: ShortArray): Double {
        var sumSquares = 0.0
        for (sample in samples) {
            sumSquares += (sample * sample).toDouble()
        }
        return sqrt(sumSquares / samples.size)
    }
    
    /**
     * 将字节数组转换为短整型数组（16位PCM）
     */
    private fun convertBytesToShorts(bytes: ByteArray, length: Int): ShortArray {
        val shorts = ShortArray(length / 2)
        for (i in shorts.indices) {
            shorts[i] = ((bytes[i * 2 + 1].toInt() and 0xFF) shl 8 or (bytes[i * 2].toInt() and 0xFF)).toShort()
        }
        return shorts
    }
    
    /**
     * 重置检测器状态
     */
    override fun reset() {
        isSpeechDetected = false
        waitingForSpeechEnd = false
        speechEndDetected = false
        consecutiveSpeechFrames = 0
        consecutiveSilenceFrames = 0
        
        // 清空历史能量
        for (i in energyHistory.indices) {
            energyHistory[i] = 0.0
        }
        
        noiseFloor = 0.0
        logger.info("VAD状态已重置")
    }
    
    /**
     * 设置敏感度
     * @param sensitivity 敏感度 (0.0-1.0)
     */
    override fun setSensitivity(sensitivity: Float) {
        // 根据敏感度调整各项阈值
        val factor = 1.0f - sensitivity // 反向调整，灵敏度高时阈值低
        
        val customConfig = VADConfig(
            energyThreshold = 500.0 * (1.0 + factor),
            snrThreshold = 2.5 * (1.0 + factor * 0.5),
            speechThreshold = 0.7f * (1.0f + factor * 0.3f),
            minConsecutiveSpeechFrames = (3 + factor * 2).toInt()
        )
        
        logger.info("VAD灵敏度设置为 $sensitivity, 能量阈值=${customConfig.energyThreshold}, 语音概率阈值=${customConfig.speechThreshold}")
    }
    
    /**
     * VAD配置
     */
    data class VADConfig(
        val energyThreshold: Double = 500.0,     // 能量阈值，进一步提高以降低误触发
        val snrThreshold: Double = 2.5,          // 信噪比阈值，提高以要求更清晰的语音
        val speechThreshold: Float = 0.7f,       // 语音概率阈值，提高以增加可信度要求
        val speechHoldTimeMs: Long = 600,        // 语音保持时间，降低以更快结束语音状态
        val minConsecutiveSpeechFrames: Int = 3, // 判定为语音所需的最少连续帧数
        val minConsecutiveSilenceFrames: Int = 5 // 判定为静音所需的最少连续帧数
    )
    
    companion object {
        private const val ENERGY_HISTORY_SIZE = 50 // 保存最近50帧的能量
    }
} 