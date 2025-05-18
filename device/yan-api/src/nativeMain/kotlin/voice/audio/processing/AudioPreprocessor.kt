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
    
    // 音频质量阈值配置 - 提高检测阈值
    private val config = PreprocessingConfig(
        minRmsThreshold = 150.0,           // 提高均方根阈值
        minAmplitudeThreshold = 700,       // 提高振幅阈值
        minNonZeroRatio = 0.4              // 提高非零样本比例要求
    )
    
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
        
        // 降低日志频率：仅每10帧记录一次处理开始
        if (totalProcessedFrames % 10 == 0) {
            logger.debug("预处理开始: 原始长度=${length}, 帧序号=${totalProcessedFrames}")
        }
        
        // 转换为短整型数组，检测并处理立体声数据
        val samples = convertBytesToShorts(rawAudio, length)
        
        // 计算音频指标
        val metrics = calculateAudioMetrics(samples)
        
        // 降低日志频率：仅每20帧记录一次音频指标
        if (totalProcessedFrames % 20 == 0) {
            logger.debug("预处理音频指标: RMS=${metrics.rms}, 最大振幅=${metrics.maxAmplitude}, 非零比例=${metrics.nonZeroRatio}")
        }
        
        // 降低日志频率：每200帧记录一次统计信息
        if (totalProcessedFrames % 200 == 0) {
            logger.debug("音频预处理统计: 总处理帧=${totalProcessedFrames}, 过滤低质量帧=${filteredLowQualityFrames}, " +
                    "过滤率=${filteredLowQualityFrames.toFloat() / totalProcessedFrames.toFloat() * 100f}%")
        }
        
        // 质量检查
        val qualityCheckPassed = checkAudioQuality(metrics)
        
        // 降低日志频率：仅在每50帧或质量检查失败时记录
        if (totalProcessedFrames % 50 == 0 || !qualityCheckPassed) {
            logger.debug("音频质量检查: 通过=${qualityCheckPassed}")
        }
        
        if (!qualityCheckPassed) {
            filteredLowQualityFrames++
            
            // 降低日志频率：每50帧记录一次过滤情况
            if (filteredLowQualityFrames % 50 == 0) {
                logger.debug("过滤低质量音频: RMS=${metrics.rms}, 最大振幅=${metrics.maxAmplitude}, " +
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
        
        // 降低日志频率：仅每20帧记录一次完成信息
        if (totalProcessedFrames % 20 == 0) {
            logger.debug("预处理完成: 输出长度=${resampledData.size}")
        }
        
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
        
        // 降低日志频率：仅在质量检查失败时记录详细信息
        if (!rmsCheck || !amplitudeCheck || !nonZeroCheck || !clippingCheck) {
            logger.debug("质量检查详情: RMS检查=${rmsCheck}(${metrics.rms}/${config.minRmsThreshold}), " +
                    "振幅检查=${amplitudeCheck}(${metrics.maxAmplitude}/${config.minAmplitudeThreshold}), " +
                    "非零检查=${nonZeroCheck}(${metrics.nonZeroRatio}/${config.minNonZeroRatio}), " +
                    "削波检查=${clippingCheck}(${metrics.clippingRatio}/${config.maxClippingRatio})")
        }
        
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
     * 支持立体声转单声道处理
     */
    private fun convertBytesToShorts(bytes: ByteArray, length: Int): ShortArray {
        // 检测是否为立体声数据（4字节表示2个样本）
        val isStereo = length % 4 == 0 && length > 4
        
        if (isStereo) {
            // 立体声转单声道处理，只保留左声道或对两个声道进行混合
            val monoLength = length / 4  // 一个立体声样本占4字节，转换后的单声道数组长度
            val shorts = ShortArray(monoLength)
            
            for (i in 0 until monoLength) {
                // 取左声道数据（立体声中的第一个通道）
                val leftChannel = ((bytes[i * 4 + 1].toInt() and 0xFF) shl 8 or (bytes[i * 4].toInt() and 0xFF)).toShort()
                
                // 取右声道数据（立体声中的第二个通道）
                val rightChannel = ((bytes[i * 4 + 3].toInt() and 0xFF) shl 8 or (bytes[i * 4 + 2].toInt() and 0xFF)).toShort()
                
                // 混合左右声道，避免溢出
                shorts[i] = ((leftChannel.toInt() + rightChannel.toInt()) / 2).toShort()
            }
            
            return shorts
        } else {
            // 单声道数据，直接转换
            val shorts = ShortArray(length / 2)
            for (i in shorts.indices) {
                shorts[i] = ((bytes[i * 2 + 1].toInt() and 0xFF) shl 8 or (bytes[i * 2].toInt() and 0xFF)).toShort()
            }
            return shorts
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