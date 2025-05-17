package voice.audio.processing

import kotlinx.cinterop.ExperimentalForeignApi
import voice.audio.AudioMetrics
import voice.audio.AudioPipeline
import voice.util.LogManager
import kotlin.math.abs
import kotlin.math.log10
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
class AudioPreprocessor : AudioPipeline.Preprocessing {
    private val logger = LogManager.getLogger("AudioPreprocessor")
    
    // 音频质量阈值配置
    private val config = PreprocessingConfig()
    
    // 统计信息
    private var totalProcessedFrames = 0
    private var filteredLowQualityFrames = 0
    
    /**
     * 处理音频数据
     * @param rawAudio 原始音频数据（字节数组）
     * @param length 数据长度
     * @return 处理结果，包含处理后的数据和音频指标
     */
    override fun process(rawAudio: ByteArray, length: Int): AudioPipeline.Preprocessing.ProcessResult {
        totalProcessedFrames++
        
        // 记录处理开始
        logger.info("【调试】预处理开始: 原始长度=${length}, 帧序号=${totalProcessedFrames}")
        
        // 转换为短整型数组
        val samples = convertBytesToShorts(rawAudio, length)
        
        // 计算音频指标
        val metrics = calculateAudioMetrics(samples)
        
        logger.info("【调试】预处理音频指标: RMS=${metrics.rms}, 最大振幅=${metrics.maxAmplitude}, 非零比例=${metrics.nonZeroRatio}")
        
        // 记录详细诊断
        if (totalProcessedFrames % 100 == 0) {
            logger.debug("音频预处理统计: 总处理帧=${totalProcessedFrames}, 过滤低质量帧=${filteredLowQualityFrames}, " +
                    "过滤率=${filteredLowQualityFrames.toFloat() / totalProcessedFrames.toFloat() * 100f}%")
        }
        
        // 质量检查
        val qualityCheckPassed = checkAudioQuality(metrics)
        logger.info("【调试】音频质量检查: 通过=${qualityCheckPassed}")
        
        if (!qualityCheckPassed) {
            filteredLowQualityFrames++
            if (filteredLowQualityFrames % 20 == 0) {
                logger.info("过滤低质量音频: RMS=${metrics.rms}, 最大振幅=${metrics.maxAmplitude}, " +
                        "非零比例=${metrics.nonZeroRatio}, 削波率=${metrics.clippingRatio}")
            }
            
            // 返回空数据，表示不应继续处理
            return AudioPipeline.Preprocessing.ProcessResult(
                processedAudio = ByteArray(0),
                processedLength = 0,
                metrics = metrics,
                shouldContinue = false
            )
        }
        
        // 进行降采样 (48kHz -> 16kHz, 3:1)
        val resampledData = resample(rawAudio, length)
        logger.info("【调试】预处理完成: 输出长度=${resampledData.size}")
        
        // 返回处理结果
        return AudioPipeline.Preprocessing.ProcessResult(
            processedAudio = resampledData,
            processedLength = resampledData.size,
            metrics = metrics,
            shouldContinue = true
        )
    }
    
    /**
     * 检查音频质量
     * @param metrics 音频指标
     * @return 是否通过质量检查
     */
    private fun checkAudioQuality(metrics: AudioMetrics): Boolean {
        // 记录每项检查结果
        val rmsCheck = metrics.rms >= config.minRmsThreshold
        val amplitudeCheck = metrics.maxAmplitude >= config.minAmplitudeThreshold
        val nonZeroCheck = metrics.nonZeroRatio >= config.minNonZeroRatio
        val clippingCheck = metrics.clippingRatio <= config.maxClippingRatio
        
        logger.info("【调试】质量检查详情: RMS检查=${rmsCheck}(${metrics.rms}/${config.minRmsThreshold}), " +
                "振幅检查=${amplitudeCheck}(${metrics.maxAmplitude}/${config.minAmplitudeThreshold}), " +
                "非零检查=${nonZeroCheck}(${metrics.nonZeroRatio}/${config.minNonZeroRatio}), " +
                "削波检查=${clippingCheck}(${metrics.clippingRatio}/${config.maxClippingRatio})")
        
        // 降低过滤条件，只要RMS或最大振幅满足条件就通过
        return rmsCheck || amplitudeCheck
    }
    
    /**
     * 计算音频指标
     * @param samples 短整型音频样本
     * @return 音频指标
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
     */
    private fun convertBytesToShorts(bytes: ByteArray, length: Int): ShortArray {
        val shorts = ShortArray(length / 2)
        for (i in shorts.indices) {
            shorts[i] = ((bytes[i * 2 + 1].toInt() and 0xFF) shl 8 or (bytes[i * 2].toInt() and 0xFF)).toShort()
        }
        return shorts
    }
    
    /**
     * 降采样 (48kHz -> 16kHz, 3:1)
     */
    private fun resample(audio: ByteArray, length: Int): ByteArray {
        // 对于16位音频，每个样本2字节
        val numSamples = length / 2
        val resampledLength = numSamples / 3 * 2 // 3:1 降采样
        val resampledAudio = ByteArray(resampledLength)
        
        var j = 0
        for (i in 0 until numSamples step 3) {
            if (i + 2 < numSamples) {
                // 复制每三个样本中的一个
                resampledAudio[j] = audio[i * 2]
                resampledAudio[j + 1] = audio[i * 2 + 1]
                j += 2
            }
        }
        
        return resampledAudio
    }
    
    /**
     * 预处理配置
     */
    data class PreprocessingConfig(
        val minRmsThreshold: Double = 100.0,          // 最小均方根阈值 - 从50.0提高到100.0
        val minAmplitudeThreshold: Int = 500,         // 最小振幅阈值 - 从200提高到500
        val minNonZeroRatio: Double = 0.3,            // 最小非零样本比例 - 从0.1提高到0.3
        val maxClippingRatio: Double = 0.1,           // 最大削波比例
        val vadThreshold: Double = 0.5,               // VAD能量阈值 - 从0.3提高到0.5
        val vadHoldTimeMs: Long = 300                 // VAD保持时间（毫秒）
    )
} 