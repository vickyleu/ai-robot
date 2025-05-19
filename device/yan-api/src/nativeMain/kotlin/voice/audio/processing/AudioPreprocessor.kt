package voice.audio.processing

import kotlinx.cinterop.ExperimentalForeignApi
import voice.audio.AudioMetrics
import voice.audio.AudioProcessingPipeline
import voice.util.AudioUtils
import voice.util.LogManager
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.time.ExperimentalTime

/**
 * 音频预处理器
 * 负责对原始PCM数据进行预处理，包括：
 * 1. 音频质量评估（能量、振幅等）
 * 2. 降采样（48kHz -> 16kHz）
 * 3. 静音过滤
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
class AudioPreprocessor : AudioProcessingPipeline {
    private val logger = LogManager.getLogger("AudioPreprocessor")

    // 音频质量阈值配置 - 提高检测阈值
    private val config = PreprocessingConfig(
        minRmsThreshold = 150.0,           // 提高均方根阈值
        minAmplitudeThreshold = 700,       // 提高振幅阈值
        minNonZeroRatio = 0.4              // 提高非零样本比例要求
    )

    // 统计信息
    private var totalProcessedFrames = 0
    private var filteredLowQualityFrames = 0
    private var speechFrameCount = 0
    private var recognitionCallCount = 0
    private var lastFrameTime = 0L

    /**
     * 处理音频数据
     * @param rawAudio 原始音频数据（字节数组）
     * @param length 数据长度
     * @return 处理结果，包含处理后的数据和音频指标
     */
    override fun process(rawAudio: ByteArray, length: Int): AudioProcessingPipeline.ProcessResult {
        totalProcessedFrames++

        // 将字节数组转换为短整型数组
        val samples = convertToShortArray(rawAudio, length)

        // 计算音频指标
        val metrics = calculateAudioMetrics(samples)

        // 检查音频质量
        val qualityCheckPassed = checkAudioQuality(metrics)

        if (!qualityCheckPassed) {
            filteredLowQualityFrames++

            // 降低日志频率：每50帧记录一次过滤情况
            if (filteredLowQualityFrames % 50 == 0) {
                logger.debug(
                    "过滤低质量音频: RMS=${metrics.rms}, 最大振幅=${metrics.maxAmplitude}, " +
                            "非零比例=${metrics.nonZeroRatio}, 削波率=${metrics.clippingRatio}"
                )
            }

            // 返回空数据，表示不应继续处理
            return AudioProcessingPipeline.ProcessResult(
                processedAudio = ByteArray(0),
                processedLength = 0,
                metrics = metrics,
                shouldContinue = false
            )
        }

        // 进行降采样 (48kHz -> 16kHz, 3:1)
        val resampledData = resample(rawAudio, length)

        // 降低日志频率：仅每20帧记录一次完成信息
        if (totalProcessedFrames % 20 == 0) {
            logger.debug("预处理完成: 输出长度=${resampledData.size}")
        }

        // 返回处理结果
        return AudioProcessingPipeline.ProcessResult(
            processedAudio = resampledData,
            processedLength = resampledData.size,
            metrics = metrics,
            shouldContinue = true
        )
    }

    /**
     * 初始化音频处理管理器
     */
    override fun initialize(): Boolean {
        return true
    }

    /**
     * 设置关键词检测回调
     */
    override fun setKeywordDetectedCallback(callback: (String) -> Unit) {
        // 预处理器不需要实现此功能
    }

    /**
     * 更新关键词列表
     */
    override fun updateKeywords(keywords: List<String>) {
        // 预处理器不需要实现此功能
    }

    /**
     * 开始音频处理
     */
    override fun start() {
        // 预处理器不需要实现此功能
    }

    /**
     * 停止音频处理
     */
    override fun stop() {
        // 预处理器不需要实现此功能
    }

    /**
     * 生成诊断报告
     */
    override fun generateDiagnosticReport(): String {
        return "音频预处理器统计:\n" +
                "总处理帧数: $totalProcessedFrames\n" +
                "过滤低质量帧数: $filteredLowQualityFrames\n" +
                "过滤率: ${(filteredLowQualityFrames.toFloat() / totalProcessedFrames.toFloat() * 100).toInt()}%"
    }

    /**
     * 释放资源
     */
    override fun release() {
        // 预处理器不需要实现此功能
    }

    /**
     * 获取处理统计信息
     */
    override fun getStats(): AudioProcessingPipeline.ProcessingStats {
        return AudioProcessingPipeline.ProcessingStats(
            frameCount = totalProcessedFrames,
            speechFrameCount = speechFrameCount,
            recognitionCallCount = recognitionCallCount,
            lastFrameTime = lastFrameTime
        )
    }

    /**
     * 检查音频质量
     * @param metrics 音频指标
     * @return 是否通过质量检查
     */
    private fun checkAudioQuality(metrics: AudioMetrics): Boolean {
        // 检查均方根值
        if (metrics.rms < config.minRmsThreshold) {
            return false
        }

        // 检查最大振幅
        if (metrics.maxAmplitude < config.minAmplitudeThreshold) {
            return false
        }

        // 检查非零样本比例
        if (metrics.nonZeroRatio < config.minNonZeroRatio) {
            return false
        }

        // 检查削波比例
        if (metrics.clippingRatio > config.maxClippingRatio) {
            return false
        }

        return true
    }

    /**
     * 计算音频指标
     */
    private fun calculateAudioMetrics(samples: ShortArray): AudioMetrics {
        var sumSquares = 0.0
        var maxAmplitude = 0
        var zeroCrossings = 0
        var nonZeroSamples = 0
        var clippingSamples = 0

        // 上一个样本的符号
        var lastSign = 0

        for (i in samples.indices) {
            val sample = samples[i]
            val absValue = abs(sample.toInt())

            // 均方根计算
            sumSquares += (sample * sample).toDouble()

            // 最大振幅
            if (absValue > maxAmplitude) {
                maxAmplitude = absValue
            }

            // 非零样本
            if (absValue > 0) {
                nonZeroSamples++
            }

            // 过零率计算
            val currentSign = if (sample > 0) 1 else if (sample < 0) -1 else 0
            if (lastSign != 0 && currentSign != 0 && lastSign != currentSign) {
                zeroCrossings++
            }
            if (currentSign != 0) {
                lastSign = currentSign
            }

            // 削波检测（接近最大值）
            if (absValue > Short.MAX_VALUE * 0.95) {
                clippingSamples++
            }
        }

        // 计算均方根值
        val rms = sqrt(sumSquares / samples.size)
        // 计算非零样本比例
        val nonZeroRatio = nonZeroSamples.toDouble() / samples.size.toDouble()
        // 计算削波比例
        val clippingRatio = clippingSamples.toDouble() / samples.size.toDouble()

        return AudioMetrics(
            rms = rms,
            maxAmplitude = maxAmplitude,
            zeroCrossingRate = zeroCrossings,
            nonZeroRatio = nonZeroRatio,
            clippingRatio = clippingRatio
        )
    }

    /**
     * 将字节数组转换为短整型数组（16位PCM）
     * 支持立体声转单声道处理
     */
    private fun convertToShortArray(bytes: ByteArray, length: Int): ShortArray {
        // 检测是否为立体声数据（4字节表示2个样本）
        val isStereo = length % 4 == 0 && length > 4

        if (isStereo) {
            // 立体声转单声道处理
            // 首先使用AudioUtils将字节转为短整型
            val stereoShorts = AudioUtils.byteArrayToShortArray(bytes.copyOf(length))

            // 然后将立体声转为单声道
            return AudioUtils.stereoToMono(stereoShorts)
        } else {
            // 单声道数据，直接转换
            return AudioUtils.byteArrayToShortArray(bytes.copyOf(length))
        }
    }

    /**
     * 降采样 (48kHz -> 16kHz, 3:1)
     * 支持立体声数据
     */
    private fun resample(audio: ByteArray, length: Int): ByteArray {
        // 检测是否为立体声数据
        val isStereo = length % 4 == 0 && length > 4

        if (isStereo) {
            // 立体声数据处理
            // 对于16位音频，每个立体声样本4字节（左右各2字节）
            val numStereoSamples = length / 4
            val resampledLength = numStereoSamples / 3 * 4 // 3:1 降采样，但保持立体声格式
            val resampledAudio = ByteArray(resampledLength)

            var j = 0
            for (i in 0 until numStereoSamples step 3) {
                if (i + 2 < numStereoSamples) {
                    // 复制每三个立体声样本中的一个（4字节）
                    resampledAudio[j] = audio[i * 4]
                    resampledAudio[j + 1] = audio[i * 4 + 1]
                    resampledAudio[j + 2] = audio[i * 4 + 2]
                    resampledAudio[j + 3] = audio[i * 4 + 3]
                    j += 4
                }
            }

            return resampledAudio
        } else {
            // 单声道数据处理
            // 对于16位音频，每个样本2字节
            val numSamples = length / 2
            val resampledLength = numSamples / 3 * 2 // 3:1 降采样
            val resampledAudio = ByteArray(resampledLength)

            var j = 0
            for (i in 0 until numSamples step 3) {
                if (i + 2 < numSamples) {
                    // 复制每三个样本中的一个（2字节）
                    resampledAudio[j] = audio[i * 2]
                    resampledAudio[j + 1] = audio[i * 2 + 1]
                    j += 2
                }
            }

            return resampledAudio
        }
    }

    /**
     * 预处理配置
     */
    data class PreprocessingConfig(
        val minRmsThreshold: Double = 100.0,          // 最小均方根阈值
        val minAmplitudeThreshold: Int = 500,         // 最小振幅阈值
        val minNonZeroRatio: Double = 0.3,            // 最小非零样本比例
        val maxClippingRatio: Double = 0.1,           // 最大削波比例
        val vadThreshold: Double = 0.5,               // VAD能量阈值
        val vadHoldTimeMs: Long = 300                 // VAD保持时间（毫秒）
    )
} 