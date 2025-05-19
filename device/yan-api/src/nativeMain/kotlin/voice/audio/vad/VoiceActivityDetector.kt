package voice.audio.vad

import kotlinx.cinterop.ExperimentalForeignApi
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
class VoiceActivityDetector : VoiceActivityDetection {
    private val logger = LogManager.getLogger("VoiceActivityDetector")
    private val config = VADConfig(
        energyThreshold = 800.0,     // 提高能量阈值，减少误触发
        snrThreshold = 3.5,          // 提高信噪比要求，增加触发难度
        speechThreshold = 0.85f,     // 提高语音阈值要求
        minConsecutiveSpeechFrames = 6, // 提高连续语音帧要求
        minConsecutiveSilenceFrames = 6  // 提高连续静音帧要求
    )

    // 语音检测状态
    private var isSpeechDetected = false
    private var lastSpeechTime = TimeSource.Monotonic.markNow()

    // 新增：语音状态转换跟踪
    private var speechEndDetected = false
    private var waitingForSpeechEnd = false

    // 保存历史能量用于环境噪声自适应
    private val energyHistory = DoubleArray(ENERGY_HISTORY_SIZE) { 0.0 }
    private var energyHistoryIndex = 0
    private var noiseFloor = 100.0 // 初始噪声基准值

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

    // 添加帧计数相关变量
    private var frameCount = 0
    private var speechFrameCount = 0

    /**
     * 检测音频数据中是否包含语音
     * @param audio 音频数据
     * @param length 数据长度
     * @return 检测结果
     */
    override fun detect(audio: ByteArray, length: Int): VoiceActivityDetection.DetectionResult {
        if (audio.isEmpty() || length <= 0) {
            logger.warn("VAD: 输入的音频数据为空")
            return VoiceActivityDetection.DetectionResult(false, 0.0f, createEmptyMetrics())
        }

        // 仅使用前16K的数据进行降噪处理
        val processLength = kotlin.math.min(length, 16000)

        try {
            // 使用RNNoise进行处理
            val energy = calculateRms(audio, processLength)

            // 计算自适应阈值
            adaptNoiseFloor(energy)

            // 使用能量和自适应阈值进行VAD判断
            val energyThreshold = noiseFloor * 1.8
            val hasSpeech = energy > energyThreshold

            // 计算信噪比
            val snr = if (noiseFloor > 0) energy / noiseFloor else 1.0

            // 计算置信度 - 基于能量超过阈值的程度
            val confidence = if (hasSpeech) {
                val rawConfidence = (energy - energyThreshold) / energyThreshold
                // 限制在0.1-1.0范围内
                kotlin.math.min(1.0f, kotlin.math.max(0.1f, rawConfidence.toFloat()))
            } else {
                0.0f
            }

            // 创建指标对象
            val metrics = createMetrics(energy, noiseFloor, snr, hasSpeech, confidence)

            // 如果检测到语音，记录详细日志
            if (hasSpeech) {
                logger.info("VAD: 检测到语音活动! 能量=$energy, 阈值=$energyThreshold, 信噪比=$snr, 置信度=$confidence")

                // 每10次活动帧记录一次详细的帧数据
                if (speechFrameCount++ % 10 == 0) {
                    val dataInfo = audio.take(20).joinToString(", ") { it.toString() } + "..."
                    logger.info("VAD: 语音数据样本: $dataInfo")
                }
            } else if (frameCount % 50 == 0) {
                // 偶尔记录一下无语音的帧信息
                logger.info("VAD: 无语音活动 (能量=$energy, 阈值=$energyThreshold, 噪声基线=$noiseFloor)")
            }

            // 更新帧计数
            frameCount++

            return VoiceActivityDetection.DetectionResult(
                hasSpeech = hasSpeech,
                confidence = confidence,
                metrics = metrics
            )
        } catch (e: Exception) {
            logger.error("VAD处理异常: ${e.message}")
            return VoiceActivityDetection.DetectionResult(false, 0.0f, createEmptyMetrics())
        }
    }

    /**
     * 处理语音状态（防抖动）
     */
    private fun handleSpeechState(
        isStableSpeech: Boolean,
        isStableSilence: Boolean,
        speechProbability: Float,
        energy: Double
    ) {
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
            // 检测到稳定静音
            val elapsed = now - lastSpeechTime

            // 如果语音概率为0，且当前是语音状态，且已经有足够的静音帧，则立即结束语音
            if (isSpeechDetected && speechProbability < 0.2f &&
                consecutiveSilenceFrames >= config.minConsecutiveSilenceFrames
            ) {
                // 降低语音结束的时间阈值，使语音更快结束
                val timeThreshold =
                    if (energy < config.energyThreshold * 0.7) 300L else config.speechHoldTimeMs

                if (elapsed.inWholeMilliseconds > timeThreshold) {
                    logger.info("语音结束，持续 ${elapsed.inWholeMilliseconds} ms")

                    // 如果正在等待语音结束，标记语音结束事件
                    if (waitingForSpeechEnd) {
                        speechEndDetected = true
                        waitingForSpeechEnd = false
                        logger.info("检测到完整语音片段，可以开始识别")
                    }

                    // 立即重置语音状态
                    isSpeechDetected = false
                }
            }
        }

        // 新增：强制结束过长语音
        val speechDuration = now - lastSpeechTime
        if (isSpeechDetected && speechDuration.inWholeMilliseconds > 3000) { // 3秒最大语音时长
            logger.info("语音时长过长，强制结束")
            if (waitingForSpeechEnd) {
                speechEndDetected = true
                waitingForSpeechEnd = false
            }
            isSpeechDetected = false
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

        return (energyFactor * 0.85 + snrFactor * 0.15).toFloat()
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
            shorts[i] =
                ((bytes[i * 2 + 1].toInt() and 0xFF) shl 8 or (bytes[i * 2].toInt() and 0xFF)).toShort()
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
            minConsecutiveSpeechFrames = (3 + factor * 2).toInt(),
            minConsecutiveSilenceFrames = (4 + factor * 2).toInt()
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
        val speechHoldTimeMs: Long = 400,        // 语音保持时间，降低以更快结束语音状态
        val minConsecutiveSpeechFrames: Int = 3, // 判定为语音所需的最少连续帧数
        val minConsecutiveSilenceFrames: Int = 4 // 判定为静音所需的最少连续帧数
    )

    /**
     * 计算音频数据的RMS (均方根) 能量
     */
    private fun calculateRms(audioData: ByteArray, length: Int): Double {
        if (audioData.isEmpty() || length <= 0) return 0.0

        var sum = 0.0
        val processLength = kotlin.math.min(audioData.size, length)

        for (i in 0 until processLength step 2) {
            if (i + 1 < processLength) {
                // 从字节数组中提取16位有符号整数
                val sample = (audioData[i].toInt() and 0xFF) or
                        ((audioData[i + 1].toInt() and 0xFF) shl 8)
                // 处理有符号整数
                val shortSample = if (sample > 32767) sample - 65536 else sample
                sum += (shortSample * shortSample).toDouble()
            }
        }

        // 计算均方根
        val rms = kotlin.math.sqrt(sum / (processLength / 2))
        return rms
    }

    /**
     * 自适应调整噪声基准值
     */
    private fun adaptNoiseFloor(energy: Double) {
        // 使用平滑因子调整噪声基准
        val alpha = 0.95

        if (energy < noiseFloor) {
            // 如果当前能量小于噪声基准，快速调整基准下降
            noiseFloor = alpha * noiseFloor + (1.0 - alpha) * energy
        } else {
            // 如果当前能量大于噪声基准，缓慢调整基准上升
            noiseFloor = alpha * noiseFloor + (1.0 - alpha) * 0.1 * energy
        }
    }

    /**
     * 创建VAD指标对象
     */
    private fun createMetrics(
        energy: Double, noiseFloor: Double, snr: Double,
        hasSpeech: Boolean, confidence: Float
    ): VADMetrics {
        return VADMetrics(
            energy = energy.toFloat(),
            noiseFloor = noiseFloor.toFloat(),
            signalToNoiseRatio = snr.toFloat(),
            speechProbability = confidence,
            hasVoice = hasSpeech
        )
    }

    /**
     * 创建空的VAD指标对象
     */
    private fun createEmptyMetrics(): VADMetrics {
        return VADMetrics(
            energy = 0.0f,
            noiseFloor = 0.0f,
            signalToNoiseRatio = 0.0f,
            speechProbability = 0.0f,
            hasVoice = false
        )
    }

    companion object {
        private const val ENERGY_HISTORY_SIZE = 50 // 保存最近50帧的能量
    }
} 