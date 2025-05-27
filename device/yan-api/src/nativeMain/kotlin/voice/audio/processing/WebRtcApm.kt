// 简化的 WebRtcApm.kt - 充分利用新的WebRTC APM功能
@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package voice.audio.processing

import com.airobot.core.utils.format
// 基本配置结构体
import com.airobot.webrtcapminterop.APMConfig
import com.airobot.webrtcapminterop.APMConfigEchoCanceller
import com.airobot.webrtcapminterop.APMConfigEchoCancellerAdvanced
import com.airobot.webrtcapminterop.APMConfigGainController
import com.airobot.webrtcapminterop.APMConfigGainController2
import com.airobot.webrtcapminterop.APMConfigAdaptiveDigital
import com.airobot.webrtcapminterop.APMConfigHighPassFilter
import com.airobot.webrtcapminterop.APMConfigNoiseSuppression
import com.airobot.webrtcapminterop.APMConfigPreAmplifier
import com.airobot.webrtcapminterop.APMConfigVoiceDetection
import com.airobot.webrtcapminterop.APMConfigVoiceDetectionAdvanced
import com.airobot.webrtcapminterop.APMConfigTransientSuppression
import com.airobot.webrtcapminterop.APMConfigResidualEchoDetector
import com.airobot.webrtcapminterop.APMConfigLevelEstimation
import com.airobot.webrtcapminterop.APMConfigVoiceProbability
import com.airobot.webrtcapminterop.APMConfigSaturationDetection
import com.airobot.webrtcapminterop.APMConfigNoiseEstimation

// 新增高级配置结构体
import com.airobot.webrtcapminterop.APMConfigMultiChannel
import com.airobot.webrtcapminterop.APMConfigPerformance
import com.airobot.webrtcapminterop.APMPreprocessingChain

// 枚举类型
import com.airobot.webrtcapminterop.APMAgcMode
import com.airobot.webrtcapminterop.APMNsLevel
import com.airobot.webrtcapminterop.APMPresetMode
import com.airobot.webrtcapminterop.APMRuntimeSettingType
import com.airobot.webrtcapminterop.APMErrorCode

// 统计和质量评估
import com.airobot.webrtcapminterop.APMStatistics
import com.airobot.webrtcapminterop.APMStatisticsExtended
import com.airobot.webrtcapminterop.APMAudioQuality
import com.airobot.webrtcapminterop.APMStreamAnalysis
import com.airobot.webrtcapminterop.APMRuntimeSetting

// 预设模式常量
import com.airobot.webrtcapminterop.APM_PRESET_DEFAULT
import com.airobot.webrtcapminterop.APM_PRESET_CONFERENCE
import com.airobot.webrtcapminterop.APM_PRESET_MUSIC
import com.airobot.webrtcapminterop.APM_PRESET_SPEECH
import com.airobot.webrtcapminterop.APM_PRESET_LOW_LATENCY
import com.airobot.webrtcapminterop.APM_PRESET_VOICE_ASSISTANT

// 级别常量
import com.airobot.webrtcapminterop.kAgcAdaptiveDigital
import com.airobot.webrtcapminterop.kNsVeryHigh

// SOXR相关
import com.airobot.webrtcapminterop.SOXR_FLOAT32_I
import com.airobot.webrtcapminterop.SOXR_INT16_I
import com.airobot.webrtcapminterop.SOXR_LQ
import com.airobot.webrtcapminterop.SoxWrapper
import com.airobot.webrtcapminterop.soxr_io_spec_create
import com.airobot.webrtcapminterop.soxr_quality_spec_create
import com.airobot.webrtcapminterop.soxr_runtime_spec_create
import com.airobot.webrtcapminterop.soxr_wrapper_create
import com.airobot.webrtcapminterop.soxr_wrapper_create_resampler
import com.airobot.webrtcapminterop.soxr_wrapper_destroy
import com.airobot.webrtcapminterop.soxr_wrapper_process

// WebRTC APM基础接口
import com.airobot.webrtcapminterop.webrtc_apm_create
import com.airobot.webrtcapminterop.webrtc_apm_destroy
import com.airobot.webrtcapminterop.webrtc_apm_apply_config
import com.airobot.webrtcapminterop.webrtc_apm_prepare
import com.airobot.webrtcapminterop.webrtc_apm_process_stream
import com.airobot.webrtcapminterop.webrtc_apm_process_reverse_stream

// 运行时接口
import com.airobot.webrtcapminterop.webrtc_apm_set_stream_analog_level
import com.airobot.webrtcapminterop.webrtc_apm_get_stream_analog_level
import com.airobot.webrtcapminterop.webrtc_apm_set_key_pressed
import com.airobot.webrtcapminterop.webrtc_apm_set_stream_delay_ms
import com.airobot.webrtcapminterop.webrtc_apm_get_stream_delay_ms

// VAD和语音检测
import com.airobot.webrtcapminterop.webrtc_apm_voice_detected
import com.airobot.webrtcapminterop.webrtc_apm_get_voice_probability

// 快捷开关接口
import com.airobot.webrtcapminterop.webrtc_apm_enable_aec
import com.airobot.webrtcapminterop.webrtc_apm_enable_ns
import com.airobot.webrtcapminterop.webrtc_apm_enable_agc
import com.airobot.webrtcapminterop.webrtc_apm_enable_vad

// 动态参数调节
import com.airobot.webrtcapminterop.webrtc_apm_set_ns_level
import com.airobot.webrtcapminterop.webrtc_apm_set_agc_target_level
import com.airobot.webrtcapminterop.webrtc_apm_set_pre_amplifier_gain

// 统计信息接口
import com.airobot.webrtcapminterop.webrtc_apm_get_statistics
import com.airobot.webrtcapminterop.webrtc_apm_get_extended_statistics
import com.airobot.webrtcapminterop.webrtc_apm_reset_statistics

// 高级接口
import com.airobot.webrtcapminterop.webrtc_apm_process_stream_with_result
import com.airobot.webrtcapminterop.webrtc_apm_is_saturated
import com.airobot.webrtcapminterop.webrtc_apm_get_speech_level_dbfs
import com.airobot.webrtcapminterop.webrtc_apm_get_noise_level_dbfs

// 调试和监控
import com.airobot.webrtcapminterop.webrtc_apm_enable_debug_recording
import com.airobot.webrtcapminterop.webrtc_apm_disable_debug_recording

// 配置管理
import com.airobot.webrtcapminterop.webrtc_apm_get_default_config
import com.airobot.webrtcapminterop.webrtc_apm_validate_config
import com.airobot.webrtcapminterop.webrtc_apm_apply_preset

// 新增高级功能接口
import com.airobot.webrtcapminterop.webrtc_apm_set_runtime_setting
import com.airobot.webrtcapminterop.webrtc_apm_get_linear_aec_output
import com.airobot.webrtcapminterop.webrtc_apm_set_multi_channel_config
import com.airobot.webrtcapminterop.webrtc_apm_process_multi_channel_stream
import com.airobot.webrtcapminterop.webrtc_apm_assess_audio_quality
import com.airobot.webrtcapminterop.webrtc_apm_enable_quality_monitoring
import com.airobot.webrtcapminterop.webrtc_apm_optimize_for_platform
import com.airobot.webrtcapminterop.webrtc_apm_set_performance_mode

// 错误处理
import com.airobot.webrtcapminterop.webrtc_apm_get_last_error
import com.airobot.webrtcapminterop.webrtc_apm_get_error_string

// 语音助手专用功能
import com.airobot.webrtcapminterop.webrtc_apm_set_voice_assistant_mode
import com.airobot.webrtcapminterop.webrtc_apm_detect_wake_word_environment
import com.airobot.webrtcapminterop.webrtc_apm_get_speech_clarity_score
import com.airobot.webrtcapminterop.webrtc_apm_optimize_for_far_field

// 动态配置
import com.airobot.webrtcapminterop.webrtc_apm_update_config_runtime
import com.airobot.webrtcapminterop.webrtc_apm_export_config_json

// 音频流分析
import com.airobot.webrtcapminterop.webrtc_apm_analyze_audio_stream

// 自适应处理
import com.airobot.webrtcapminterop.webrtc_apm_enable_adaptive_processing
import com.airobot.webrtcapminterop.webrtc_apm_set_adaptation_speed

// 预处理链
import com.airobot.webrtcapminterop.webrtc_apm_set_preprocessing_chain

// 扩展接口
import com.airobot.webrtcapminterop.webrtc_apm_get_voice_probability_ex
import com.airobot.webrtcapminterop.webrtc_apm_is_saturated_ex
import com.airobot.webrtcapminterop.webrtc_apm_get_noise_level_dbfs_ex

// 新增扩展功能
import com.airobot.webrtcapminterop.webrtc_apm_detect_double_talk_ex
import com.airobot.webrtcapminterop.webrtc_apm_estimate_reverberation_time_ex
import com.airobot.webrtcapminterop.webrtc_apm_get_frequency_response_ex
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.CVariable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.free
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.useContents
import kotlinx.cinterop.toKString
import kotlinx.cinterop.cstr
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import platform.posix.strlen
import voice.util.AudioDefaults
import voice.util.AudioUtils
import voice.util.LogManager
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.abs

/**
 * RAII资源管理器 - 确保资源总是被正确释放
 */
sealed class ManagedResource : AutoCloseable {
    abstract override fun close()

    /**
     * 管理WebRTC APM句柄
     */
    class ApmHandle(private val handle: CPointer<*>) : ManagedResource() {
        fun get(): CPointer<*> = handle

        override fun close() {
            try {
                webrtc_apm_destroy(handle)
            } catch (e: Exception) {
                LogManager.getLogger("ApmHandle").warn("释放APM句柄失败: ${e.message}")
            }
        }
    }


    /**
     * 管理原生堆分配的缓冲区
     */
    class NativeBuffer<T : CVariable>(
        private val buffer: CPointer<T>,
        private val name: String = "Buffer"
    ) : ManagedResource() {
        fun get(): CPointer<T> = buffer

        override fun close() {
            try {
                nativeHeap.free(buffer.rawValue)
            } catch (e: Exception) {
                LogManager.getLogger("NativeBuffer").warn("释放$name 失败: ${e.message}")
            }
        }
    }
}

/**
 * RAII资源管理器 - 自动管理多个资源的生命周期
 */
class ResourceManager : AutoCloseable {
    private val resources = mutableListOf<ManagedResource>()
    private val logger = LogManager.getLogger("ResourceManager")

    fun <T : ManagedResource> manage(resource: T): T {
        resources.add(resource)
        return resource
    }

    override fun close() {
        // 按LIFO顺序释放资源（后分配的先释放）
        resources.asReversed().forEach { resource ->
            try {
                resource.close()
            } catch (e: Exception) {
                logger.warn("释放资源失败: ${e.message}")
            }
        }
        resources.clear()
    }
}

/**
 * 安全的缓冲区管理器
 */
class BufferManager {
    private val logger = LogManager.getLogger("BufferManager")
    private val bufferMutex = Mutex()

    // 当前缓冲区资源
    private var currentResources: ResourceManager? = null
    private var currentBufferSize: Int = 0

    // 缓冲区指针
    private var inputFloatBuffer: CPointer<FloatVar>? = null
    private var outputFloatBuffer: CPointer<FloatVar>? = null
    private var inputArrayPointer: CPointer<CPointerVar<FloatVar>>? = null
    private var outputArrayPointer: CPointer<CPointerVar<FloatVar>>? = null

    suspend fun ensureBufferSize(requiredSize: Int, channels: Int): Boolean {
        if (requiredSize <= 0) {
            logger.error("无效的缓冲区大小: $requiredSize")
            return false
        }

        if (requiredSize > MAX_BUFFER_SIZE) {
            logger.error("缓冲区大小过大: $requiredSize > $MAX_BUFFER_SIZE")
            return false
        }

        bufferMutex.withLock {
            if (currentBufferSize == requiredSize && inputFloatBuffer != null) {
                return true // 已经分配了正确大小的缓冲区
            }

            // 释放旧资源
            currentResources?.close()
            currentResources = null
            clearBufferPointers()

            try {
                // 创建新的资源管理器
                val resources = ResourceManager()

                // 分配缓冲区
                val inputBuffer = resources.manage(
                    ManagedResource.NativeBuffer(
                        nativeHeap.allocArray<FloatVar>(requiredSize),
                        "InputFloatBuffer"
                    )
                )

                val outputBuffer = resources.manage(
                    ManagedResource.NativeBuffer(
                        nativeHeap.allocArray<FloatVar>(requiredSize),
                        "OutputFloatBuffer"
                    )
                )

                val safeChannels = channels.coerceAtLeast(1)
                val inputArray = resources.manage(
                    ManagedResource.NativeBuffer(
                        nativeHeap.allocArray<CPointerVar<FloatVar>>(safeChannels),
                        "InputArrayPointer"
                    )
                )

                val outputArray = resources.manage(
                    ManagedResource.NativeBuffer(
                        nativeHeap.allocArray<CPointerVar<FloatVar>>(safeChannels),
                        "OutputArrayPointer"
                    )
                )

                // 设置指针
                inputFloatBuffer = inputBuffer.get()
                outputFloatBuffer = outputBuffer.get()
                inputArrayPointer = inputArray.get()
                outputArrayPointer = outputArray.get()

                // 配置数组指针
                if (channels == 1) {
                    inputArrayPointer!![0] = inputFloatBuffer
                    outputArrayPointer!![0] = outputFloatBuffer
                } else {
                    logger.warn("当前APM配置为 $channels 通道，但缓冲区分配仅完整支持单通道")
                    inputArrayPointer!![0] = inputFloatBuffer
                    outputArrayPointer!![0] = outputFloatBuffer
                }

                // 保存资源管理器和大小
                currentResources = resources
                currentBufferSize = requiredSize

                logger.debug("成功分配APM缓冲区: 大小=$requiredSize")
                return true

            } catch (e: Exception) {
                logger.error("缓冲区分配失败: ${e.message}")
                currentResources?.close()
                currentResources = null
                clearBufferPointers()
                return false
            }
        }
    }

    private fun clearBufferPointers() {
        inputFloatBuffer = null
        outputFloatBuffer = null
        inputArrayPointer = null
        outputArrayPointer = null
        currentBufferSize = 0
    }

    fun getInputBuffer(): CPointer<FloatVar>? = inputFloatBuffer
    fun getOutputBuffer(): CPointer<FloatVar>? = outputFloatBuffer
    fun getInputArrayPointer(): CPointer<CPointerVar<FloatVar>>? = inputArrayPointer
    fun getOutputArrayPointer(): CPointer<CPointerVar<FloatVar>>? = outputArrayPointer
    fun getCurrentBufferSize(): Int = currentBufferSize

    fun release() {
        runBlocking {
            bufferMutex.withLock {
                currentResources?.close()
                currentResources = null
                clearBufferPointers()
            }
        }
    }

    companion object {
        private const val MAX_BUFFER_SIZE = 200000
    }
}

/**
 * 安全的SOXR重采样器封装类
 * 支持采样率和声道数转换，自动处理内存管理
 */
class SafeSoxrResampler(
    private val inputSampleRate: Int,
    private val outputSampleRate: Int,
    private val inputChannels: Int,
    private val outputChannels: Int,
    private val inputFormat: Int = 0u.toInt(), // SOXR_INT16_I
    private val outputFormat: Int = 0u.toInt(), // SOXR_INT16_I (输出也用INT16，避免精度损失)
    private val quality: Int = 1u.toInt() // SOXR_LQ (低质量，高稳定性)
) {
    private val logger = LogManager.getLogger("SafeSoxrResampler")
    private var soxrWrapper: CPointer<SoxWrapper>? = null
    private var isInitialized = false
    
    /**
     * 初始化重采样器
     */
    fun initialize(): Boolean {
        if (isInitialized) {
            return true
        }
        
        try {
            // 检查是否需要重采样
            if (inputSampleRate == outputSampleRate && inputChannels == outputChannels) {
                logger.debug("输入输出格式相同，无需重采样: ${inputSampleRate}Hz/${inputChannels}ch")
                isInitialized = true
                return true
            }
            
            // 创建SOXR包装器
            soxrWrapper = soxr_wrapper_create()
            if (soxrWrapper == null) {
                logger.error("无法创建SOXR包装器")
                return false
            }
            
            // 配置IO格式 - 根据参数动态配置
            soxr_io_spec_create(inputFormat.toUInt(), outputFormat.toUInt(), soxrWrapper)
            
            // 配置运行时参数 - 使用单线程确保稳定性
            soxr_runtime_spec_create(1u, soxrWrapper)
            
            // 配置质量参数
            soxr_quality_spec_create(quality.toUInt(), soxrWrapper)
            
            // 创建重采样器实例
            val result = soxr_wrapper_create_resampler(
                soxrWrapper,
                inputSampleRate.toDouble(),
                outputSampleRate.toDouble(),
                inputChannels.toUInt()
            )
            
            if (result != 0) {
                logger.error("创建重采样器失败，错误码: $result")
                release()
                return false
            }
            
            isInitialized = true
            logger.info("SafeSoxrResampler初始化成功: ${inputSampleRate}Hz/${inputChannels}ch -> ${outputSampleRate}Hz/${outputChannels}ch")
            return true
            
        } catch (e: Exception) {
            logger.error("SafeSoxrResampler初始化失败: ${e.message}")
            release()
            return false
        }
    }
    
    /**
     * 执行重采样和声道转换
     * @param inputData 输入音频数据
     * @return 重采样和声道转换后的音频数据
     */
    fun process(inputData: ShortArray): ShortArray {
        if (inputData.isEmpty()) {
            return inputData
        }
        
        if (!isInitialized) {
            logger.error("重采样器未初始化")
            return inputData
        }
        
        try {
            logger.info("开始处理音频数据: ${inputData.size}样本, 输入格式=${inputSampleRate}Hz/${inputChannels}ch, 输出格式=${outputSampleRate}Hz/${outputChannels}ch")
            
            // 输入音频质量检查
            val inputMaxAmp = inputData.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
            val inputNonZeroCount = inputData.count { it != 0.toShort() }
            val inputZeroRatio = (inputData.size - inputNonZeroCount).toFloat() / inputData.size
            logger.debug("输入音频统计: 最大振幅=$inputMaxAmp, 非零样本=${inputNonZeroCount}/${inputData.size}, 零值比例=${"%.4f".format(inputZeroRatio)}")
            
            // 第1步：声道转换（如果需要）
            val channelConvertedData = convertChannels(inputData, inputChannels, outputChannels)
            logger.info("声道转换完成: ${inputChannels}ch -> ${outputChannels}ch, 数据大小=${channelConvertedData.size}样本")
            
            // 声道转换后音频质量检查
            val channelMaxAmp = channelConvertedData.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
            val channelNonZeroCount = channelConvertedData.count { it != 0.toShort() }
            val channelZeroRatio = (channelConvertedData.size - channelNonZeroCount).toFloat() / channelConvertedData.size
            logger.debug("声道转换后音频统计: 最大振幅=$channelMaxAmp, 非零样本=${channelNonZeroCount}/${channelConvertedData.size}, 零值比例=${"%.4f".format(channelZeroRatio)}")
            
            // 第2步：采样率转换（如果需要）
            val finalData = if (inputSampleRate != outputSampleRate) {
                val resampledData = resampleAudio(channelConvertedData)
                
                // 重采样后音频质量检查
                val resampleMaxAmp = resampledData.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
                val resampleNonZeroCount = resampledData.count { it != 0.toShort() }
                val resampleZeroRatio = (resampledData.size - resampleNonZeroCount).toFloat() / resampledData.size
                logger.debug("重采样后音频统计: 最大振幅=$resampleMaxAmp, 非零样本=${resampleNonZeroCount}/${resampledData.size}, 零值比例=${"%.4f".format(resampleZeroRatio)}")
                
                if (resampleZeroRatio > 0.95f) {
                    logger.error("⚠️ 重采样后零值过多，可能存在严重问题！")
                    logger.error("重采样输入: 最大振幅=$channelMaxAmp, 零值比例=${"%.4f".format(channelZeroRatio)}")
                    logger.error("重采样输出: 最大振幅=$resampleMaxAmp, 零值比例=${"%.4f".format(resampleZeroRatio)}")
                }
                
                resampledData
            } else {
                channelConvertedData
            }
            
            // 最终输出质量检查
            val finalMaxAmp = finalData.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
            val finalNonZeroCount = finalData.count { it != 0.toShort() }
            val finalZeroRatio = (finalData.size - finalNonZeroCount).toFloat() / finalData.size
            logger.info("处理完成: 输入${inputData.size} -> 输出${finalData.size}样本, 最大振幅=$finalMaxAmp, 零值比例=${"%.4f".format(finalZeroRatio)}")
            
            if (finalZeroRatio > 0.9f) {
                logger.warn("⚠️ 最终输出零值过多，音频质量可能有问题")
            }
            
            return finalData
            
        } catch (e: Exception) {
            logger.error("SafeSoxrResampler处理失败: ${e.message}")
            return inputData
        }
    }
    
    /**
     * 声道转换
     */
    private fun convertChannels(inputData: ShortArray, fromChannels: Int, toChannels: Int): ShortArray {
        if (fromChannels == toChannels) {
            return inputData
        }
        
        return when {
            fromChannels == 1 && toChannels == 2 -> {
                // 单声道转立体声：每个样本重复为左右声道
                ShortArray(inputData.size * 2) { i ->
                    inputData[i / 2]
                }
            }
            fromChannels == 2 && toChannels == 1 -> {
                // 立体声转单声道：取左右声道平均值
                ShortArray(inputData.size / 2) { i ->
                    val left = inputData[i * 2].toInt()
                    val right = inputData[i * 2 + 1].toInt()
                    ((left + right) / 2).coerceIn(-32767, 32767).toShort()
                }
            }
            else -> {
                logger.warn("不支持的声道转换: ${fromChannels}ch -> ${toChannels}ch")
                inputData
            }
        }
    }
    
    /**
     * 使用SOXR进行采样率转换
     */
    private fun resampleAudio(inputData: ShortArray): ShortArray {
        if (soxrWrapper == null) {
            logger.error("SOXR包装器为空")
            return inputData
        }
        
        // 输入数据质量预检查
        val inputMaxAmp = inputData.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
        val inputNonZeroCount = inputData.count { it != 0.toShort() }
        val inputZeroRatio = (inputData.size - inputNonZeroCount).toFloat() / inputData.size
        
        // 如果输入数据质量太差，直接返回简单插值结果
        if (inputZeroRatio > 0.98f) {
            logger.warn("输入数据零值过多(${"%.4f".format(inputZeroRatio)})，使用简单插值避免SOXR失真")
            return simpleResample(inputData)
        }
        
        // 计算期望的输出大小
        val expectedOutputSize = (inputData.size * outputSampleRate.toDouble() / inputSampleRate.toDouble()).toInt()
        val outputBufferSize = (expectedOutputSize * 12 / 10).coerceAtLeast(inputData.size)
        
        // 安全性检查
        if (outputBufferSize <= 0) {
            logger.error("计算的输出缓冲区大小无效: $outputBufferSize")
            return inputData
        }
        
        // 如果缓冲区太大，使用简单重采样避免内存问题
        if (outputBufferSize > 500000) {  // 增加限制到500000，但超出时使用简单重采样
            logger.warn("计算的输出缓冲区大小过大($outputBufferSize)，使用简单插值重采样")
            return simpleResample(inputData)
        }
        
        // 增强的输入数据预处理：增益boost避免SOXR下溢
        val boostedInput = if (inputMaxAmp < 1000) {
            logger.debug("输入信号弱(最大振幅=$inputMaxAmp)，应用增益boost")
            val boostFactor = 4.0f // 增益4倍
            ShortArray(inputData.size) { i ->
                val boosted = (inputData[i] * boostFactor).coerceIn(-32767f, 32767f).toInt()
                boosted.toShort()
            }
        } else {
            inputData
        }
        
        val actualMaxAmp = boostedInput.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
        logger.debug("SOXR输入预处理: 原始最大振幅=$inputMaxAmp, 增强后最大振幅=$actualMaxAmp")
        
        var outputBuffer: CPointer<FloatVar>? = null
        logger.debug("开始SOXR重采样: 输入=${boostedInput.size}样本, 输出缓冲区大小=$outputBufferSize")
        
        // SOXR处理前输入数据质量检查
        val preNonZeroCount = boostedInput.count { it != 0.toShort() }
        val preZeroRatio = (boostedInput.size - preNonZeroCount).toFloat() / boostedInput.size
        logger.debug("SOXR预处理后输入统计: 最大振幅=$actualMaxAmp, 非零样本=${preNonZeroCount}/${boostedInput.size}, 零值比例=${"%.4f".format( preZeroRatio)}, 前5个样本=${boostedInput.take(5).joinToString(",")}")
        
        try {
            outputBuffer = nativeHeap.allocArray<FloatVar>(outputBufferSize)
            
            val outputFrames = soxr_wrapper_process(
                wrapper = soxrWrapper,
                in_data = boostedInput.refTo(0),
                in_size = boostedInput.size.toUInt(),
                out_data = outputBuffer,
                out_size = outputBufferSize.toUInt()
            )
            
            if (outputFrames == 0U && boostedInput.isNotEmpty()) {
                logger.error("SOXR重采样返回0帧，回退到简单插值")
                return simpleResample(inputData)
            }
            
            logger.debug("SOXR处理完成: 输出帧数=${outputFrames}")
            
            // 检查SOXR输出的float数据质量
            val floatSamples = (0 until outputFrames.toInt()).map { outputBuffer[it] }
            val floatMaxAmp = floatSamples.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
            val floatNonZeroCount = floatSamples.count { kotlin.math.abs(it) > 1e-6f }
            val floatZeroRatio = (floatSamples.size - floatNonZeroCount).toFloat() / floatSamples.size
            logger.debug("SOXR Float输出统计: 最大振幅=${"%.6f".format( floatMaxAmp)}, 非零样本=${floatNonZeroCount}/${floatSamples.size}, 零值比例=${"%.4f".format( floatZeroRatio)}, 前5个样本=${floatSamples.take(5).joinToString(",") { "%.6f".format( it) }}")
            
            // 检测SOXR输出异常
            if (floatZeroRatio > 0.95f && preZeroRatio < 0.5f) {
                logger.error("🚨 SOXR严重失真: 输入有效(零值${"%.4f".format( preZeroRatio)})但输出几乎全零(零值${"%.4f".format( floatZeroRatio)})")
                logger.error("回退到简单插值算法")
                return simpleResample(inputData)
            }
            
            // 将FLOAT32结果转换为INT16 - 考虑之前的增益boost
            val gainCorrection = if (inputMaxAmp < 1000) 1.0f else 1.0f  // 不需要减少增益，保持信号强度
            val result = ShortArray(outputFrames.toInt()) { i ->
                val floatSample = (outputBuffer[i] * gainCorrection).coerceIn(-1f, 1f)
                (floatSample * 32767f).toInt().toShort()
            }
            
            // 转换后INT16数据质量检查
            val postMaxAmp = result.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
            val postNonZeroCount = result.count { it != 0.toShort() }
            val postZeroRatio = (result.size - postNonZeroCount).toFloat() / result.size
            logger.debug("SOXR Int16输出统计: 最大振幅=$postMaxAmp, 非零样本=${postNonZeroCount}/${result.size}, 零值比例=${"%.4f".format( postZeroRatio)}, 前5个样本=${result.take(5).joinToString(",")}")
            
            // 最终质量检查
            if (postZeroRatio > 0.95f && preZeroRatio < 0.5f) {
                logger.error("🚨 SOXR Int16转换后严重失真，回退到简单插值")
                logger.error("输入参数: ${inputSampleRate}Hz -> ${outputSampleRate}Hz, ${inputChannels}ch -> ${outputChannels}ch")
                return simpleResample(inputData)
            }
            
            logger.debug("SOXR重采样成功: ${inputData.size} -> ${result.size}样本, 振幅变化: $inputMaxAmp -> $postMaxAmp")
            return result
            
        } finally {
            outputBuffer?.let { buffer ->
                try {
                    nativeHeap.free(buffer)
                } catch (e: Exception) {
                    logger.warn("释放SOXR输出缓冲区失败: ${e.message}")
                }
            }
        }
    }
    
    /**
     * 简单线性插值重采样 - 作为SOXR的备用方案
     * 注意：这个方法只处理采样率转换，不处理声道转换
     * 声道转换应该在调用此方法之前或之后单独处理
     */
    private fun simpleResample(inputData: ShortArray): ShortArray {
        if (inputSampleRate == outputSampleRate) {
            logger.debug("采样率相同，跳过重采样: ${inputSampleRate}Hz")
            return inputData
        }
        
        val ratio = outputSampleRate.toDouble() / inputSampleRate.toDouble()
        val outputSize = (inputData.size * ratio).toInt()
        
        logger.debug("使用简单插值重采样: ${inputData.size} -> ${outputSize}样本, 比例=${"%.3f".format( ratio)} (${inputSampleRate}Hz -> ${outputSampleRate}Hz)")
        
        val result = ShortArray(outputSize) { i ->
            val sourceIndex = i / ratio
            val lowerIndex = sourceIndex.toInt()
            val upperIndex = (lowerIndex + 1).coerceAtMost(inputData.size - 1)
            val fraction = sourceIndex - lowerIndex
            
            if (lowerIndex >= inputData.size) {
                0
            } else {
                val lower = inputData[lowerIndex].toFloat()
                val upper = inputData[upperIndex].toFloat()
                (lower + (upper - lower) * fraction).toInt().coerceIn(-32767, 32767).toShort()
            }
        }
        
        logger.debug("简单重采样完成: 输入${inputData.size}样本 -> 输出${result.size}样本")
        return result
    }
    
    /**
     * 释放资源
     */
    fun release() {
        soxrWrapper?.let {
            try {
                soxr_wrapper_destroy(it)
            } catch (e: Exception) {
                logger.warn("释放SOXR包装器失败: ${e.message}")
            }
            soxrWrapper = null
        }
        isInitialized = false
        logger.debug("SafeSoxrResampler资源已释放")
    }
    
    /**
     * 检查是否需要处理
     */
    fun needsProcessing(): Boolean {
        return inputSampleRate != outputSampleRate || inputChannels != outputChannels
    }
    
    companion object {

        
//        // SOXR质量常量
//        const val SOXR_QQ = 0u      // 快速质量
//        const val SOXR_LQ = 1u      // 低质量
//        const val SOXR_MQ = 2u      // 中等质量
//        const val SOXR_HQ = 3u      // 高质量
//        const val SOXR_VHQ = 4u     // 非常高质量
        
        /**
         * 创建用于输入重采样的实例（INT16输入，FLOAT32输出用于APM）
         */
        fun createForInput(inputSampleRate: Int, outputSampleRate: Int, channels: Int): SafeSoxrResampler {
            return SafeSoxrResampler(
                inputSampleRate = inputSampleRate,
                outputSampleRate = outputSampleRate,
                inputChannels = channels,
                outputChannels = channels,
                inputFormat = SOXR_INT16_I.toInt(),
                outputFormat = SOXR_FLOAT32_I.toInt(),
                quality = SOXR_LQ.toInt()
            )
        }
        
        /**
         * 创建用于输出重采样的实例（INT16输入，FLOAT32输出，内部转换为INT16）
         */
        fun createForOutput(inputSampleRate: Int, outputSampleRate: Int, inputChannels: Int, outputChannels: Int): SafeSoxrResampler {
            return SafeSoxrResampler(
                inputSampleRate = inputSampleRate,
                outputSampleRate = outputSampleRate,
                inputChannels = inputChannels,
                outputChannels = outputChannels,
                inputFormat = SOXR_INT16_I.toInt(),
                outputFormat = SOXR_FLOAT32_I.toInt(),  // 修复：统一使用FLOAT32避免类型错误
                quality = SOXR_LQ.toInt()
            )
        }
    }
}

/**
 * RAII模式重构的WebRtcApm类
 */
class WebRtcApm : AutoCloseable {
    private val logger = LogManager.getLogger("WebRtcApm")

    // 资源管理
    private var resources: ResourceManager? = null
    private var apmHandle: CPointer<*>? = null
    private val bufferManager = BufferManager()

    // 重采样器
    private var inputResampler: SafeSoxrResampler? = null

    // 音频格式配置
    private var inputFormat = AudioDefaults.Formats.INPUT_DEVICE
    private var apmFormat = AudioDefaults.Formats.WEBRTC_APM

    // VAD参数
    private var vadLogCounter: Int = 0
    private var consecutiveVadPositive: Int = 0
    private var lastVadResult: Boolean = false
    private var vadDebounceFrames: Int = 3

    // 状态标志
    @Volatile
    private var isFullyInitialized = false
    @Volatile
    private var processingEnabled = false

    fun initialize(sampleRate: Int, channels: Int): Boolean {
        processingEnabled = false
        isFullyInitialized = false

        if (apmHandle != null) {
            logger.warn("WebRTC APM 已经初始化，使用先前配置: $apmFormat")
            if (inputFormat.sampleRate != sampleRate || inputFormat.channels != channels) {
                logger.warn("警告: 尝试使用不同的参数重新初始化。旧参数: $inputFormat, 新参数: ${sampleRate}Hz/${channels}ch")
            }
            processingEnabled = true
            isFullyInitialized = true
            return true
        }

        // 设置音频格式
        inputFormat = AudioDefaults.AudioFormat(sampleRate, channels)
        apmFormat = AudioDefaults.Formats.WEBRTC_APM

        // 验证音频格式
        if (!AudioDefaults.isValidAudioFormat(inputFormat)) {
            logger.error("无效的输入音频格式: $inputFormat")
            return false
        }

        logger.info("APM配置: 输入=$inputFormat, APM内部处理=$apmFormat")
        logger.info("转换路径: ${AudioDefaults.getConversionPath(inputFormat, apmFormat)}")

        try {
            // 创建资源管理器
            val resourceManager = ResourceManager()

            // 创建APM实例
            val handle = webrtc_apm_create()
                ?: throw Exception("WebRTC APM 创建失败")

            resourceManager.manage(
                ManagedResource.ApmHandle(handle)
            )

            // 获取默认配置并进行高级定制
            memScoped {
                var config = webrtc_apm_get_default_config()
                config.useContents {
                    // === 回声消除配置 - 修复崩溃问题 ===
                    // 对于语音助手应用，只有输入流，没有渲染流，
                    // 因此禁用AEC3以避免BlockFramer崩溃
                    echo_canceller_advanced.basic.enabled = 0  // 禁用基础回声消除
                    echo_canceller_advanced.basic.mobile_mode = 0
                    echo_canceller_advanced.basic.export_linear_aec_output = 0
                    echo_canceller_advanced.basic.enforce_high_pass_filtering = 1

                    // AEC3 配置 - 完全禁用以避免崩溃
                    echo_canceller_advanced.aec3.enabled = 0  // 禁用AEC3
                    echo_canceller_advanced.aec3.echo_audibility_low_render_limit = 0.5f
                    echo_canceller_advanced.aec3.echo_audibility_normal_render_limit = 1.0f
                    echo_canceller_advanced.aec3.enable_shadow_filter_protection = 0
                    echo_canceller_advanced.aec3.enable_delay_agnostic_aec = 0
                    echo_canceller_advanced.aec3.filter_adaptation_speedup_factor = 1

                    // 回声消除性能调整 - 禁用以避免处理问题
                    echo_canceller_advanced.performance.aggressive_factor = 0.0f
                    echo_canceller_advanced.performance.enable_extended_filter = 0
                    echo_canceller_advanced.performance.max_echo_path_length_ms = 0
                    echo_canceller_advanced.performance.enable_refinement = 0

                    logger.info("APM配置: 已禁用AEC3回声消除以避免BlockFramer崩溃（语音助手模式）")

                    // === 噪声抑制配置 - 完全禁用避免过度处理 ===
                    noise_suppression.enabled = 0  // 完全禁用噪声抑制，避免音频失真
                    noise_suppression.level = 0u  // 设为最低级别
                    logger.info("APM配置: 噪声抑制完全禁用（避免机器人声音）")

                    // === 高通滤波配置 - 禁用避免音频失真 ===
                    high_pass_filter.enabled = 0  // 禁用高通滤波
                    logger.info("APM配置: 高通滤波禁用（保护音频质量）")

                    // === AGC1配置 - 完全禁用 ===
                    gain_controller.enabled = 0  // 完全禁用AGC1
                    gain_controller.mode = kAgcAdaptiveDigital
                    gain_controller.target_level_dbfs = 15  // 设置更高的目标电平
                    gain_controller.compression_gain_db = 0  // 完全禁用压缩
                    gain_controller.enable_limiter = 0  // 禁用限幅器
                    logger.info("APM配置: AGC1完全禁用（避免增益问题）")

                    // === AGC2高级自适应数字增益控制 - 完全禁用 ===
                    gain_controller2.enabled = 0  // 完全禁用AGC2
                    gain_controller2.adaptive_digital.enabled = 0
                    logger.info("APM配置: AGC2完全禁用")

                    // === 前置放大器配置 - 禁用避免过度放大 ===
                    pre_amplifier.enabled = 0  // 禁用前置放大器
                    pre_amplifier.fixed_gain_factor = 1.0f
                    logger.info("APM配置: 前置放大器禁用")

                    // === 高级语音检测配置 - 最小化处理 ===
                    voice_detection_advanced.basic.enabled = 1

                    // RNN-VAD配置 - 最低敏感度
                    voice_detection_advanced.rnn_vad.enabled = 0  // 禁用RNN-VAD
                    voice_detection_advanced.rnn_vad.probability_threshold = 0.05f  // 极低阈值
                    voice_detection_advanced.rnn_vad.use_spectral_features = 0  // 禁用频谱特征
                    voice_detection_advanced.rnn_vad.use_pitch_features = 0  // 禁用音调特征

                    // VAD优化配置 - 最保守设置
                    voice_detection_advanced.optimization.smoothing_window_ms = 1000  // 增加到1秒平滑
                    voice_detection_advanced.optimization.voice_trigger_threshold = 0.1f  // 极低触发阈值
                    voice_detection_advanced.optimization.silence_trigger_threshold = 0.9f  // 极高静音阈值
                    voice_detection_advanced.optimization.adaptive_threshold = 0  // 禁用自适应阈值

                    logger.info("APM配置: 语音检测最小化处理，保护音频质量")

                    // === 短暂噪声抑制配置 - 禁用 ===
                    transient_suppression.enabled = 0  // 禁用短暂噪声抑制
                    logger.info("APM配置: 短暂噪声抑制禁用")

                    // === 残余回声检测配置 - 禁用 ===
                    residual_echo_detector.enabled = 0  // 禁用残余回声检测
                    logger.info("APM配置: 残余回声检测禁用")

                    // === 电平估计配置 - 保持启用但最小化影响 ===
                    level_estimation.enabled = 1
                    logger.info("APM配置: 电平估计启用（最小影响）")

                    // === 语音概率配置 - 极度放宽阈值 ===
                    voice_probability.high_confidence_threshold = 0.1f  // 极低高置信度阈值
                    voice_probability.low_confidence_threshold = 0.01f   // 极低低置信度阈值
                    voice_probability.use_advanced_estimation = 0  // 禁用高级估算
                    logger.info("APM配置: 语音概率估算极度放宽（保护音频）")

                    // === 饱和检测配置 - 极度放宽 ===
                    saturation_detection.low_level_threshold = -80  // 极低阈值
                    saturation_detection.rms_threshold_dbfs = 10.0f  // 极高RMS阈值
                    saturation_detection.enable_multi_criteria_detection = 0  // 禁用多重标准
                    logger.info("APM配置: 饱和检测极度放宽")

                    // === 噪声估算配置 - 最保守 ===
                    noise_estimation.default_noise_level_dbfs = -80.0f  // 极低噪声电平
                    noise_estimation.estimation_window_ms = 5000  // 5秒估算窗口
                    noise_estimation.enable_adaptive_estimation = 0  // 禁用自适应估算
                    logger.info("APM配置: 噪声估算最保守设置")

                    // === 多通道配置 ===
                    multi_channel.enable_multi_channel_processing = if (apmFormat.channels > 1) 1 else 0
                    multi_channel.num_channels = apmFormat.channels
                    multi_channel.enable_channel_mixing = 1
                    multi_channel.enable_spatial_processing = 0  // 单声道处理时禁用

                    // 空间音频配置（仅多通道时有效）
                    multi_channel.spatial_audio.enabled = 0
                    multi_channel.spatial_audio.reference_channel_weight = 1.0f
                    multi_channel.spatial_audio.enable_beamforming = 0
                    multi_channel.spatial_audio.beam_width_degrees = 60.0f

                    logger.info("APM配置: 多通道处理配置，通道数=${apmFormat.channels}")

                    // === 性能优化配置 - 质量优先 ===
                    performance.enable_low_latency_mode = 0  // 禁用低延迟模式，质量优先
                    performance.enable_background_processing = 0
                    performance.processing_priority = 5  // 从8降低到5，中等优先级
                    performance.enable_simd_optimizations = 1
                    performance.max_processing_delay_ms = 100  // 从50增加到100，允许更多处理时间
                    logger.info("APM配置: 性能优化启用，质量优先模式")
                }


                // 验证配置
                val configValid = webrtc_apm_validate_config(config.ptr)
                if (configValid == 0) {
                    logger.warn("APM配置验证失败，但继续使用当前配置")
                } else {
                    logger.info("APM配置验证通过")
                }

                // 应用配置
                webrtc_apm_apply_config(handle, config.ptr)
                logger.info("APM高级配置应用完成")
            }

            // 准备APM处理
            webrtc_apm_prepare(handle, apmFormat.sampleRate, apmFormat.channels)
            logger.info("APM准备完成: ${apmFormat.sampleRate}Hz, ${apmFormat.channels}ch")

            // 启用语音助手模式（如果可用）
            try {
                webrtc_apm_set_voice_assistant_mode(handle, 1)
                logger.info("语音助手模式已启用")
            } catch (e: Exception) {
                logger.warn("启用语音助手模式失败（功能可能不可用）: ${e.message}")
            }

            // 优化远场处理（如果可用）
            try {
                webrtc_apm_optimize_for_far_field(handle, 1)
                logger.info("远场优化已启用")
            } catch (e: Exception) {
                logger.warn("启用远场优化失败（功能可能不可用）: ${e.message}")
            }

            // 启用自适应处理（如果可用）
            try {
                webrtc_apm_enable_adaptive_processing(handle, 1)
                webrtc_apm_set_adaptation_speed(handle, 0.7f)
                logger.info("自适应处理已启用，速度=0.7")
            } catch (e: Exception) {
                logger.warn("启用自适应处理失败（功能可能不可用）: ${e.message}")
            }

            // 启用音频质量监控（如果可用）
            try {
                webrtc_apm_enable_quality_monitoring(handle, 1)
                logger.info("音频质量监控已启用")
            } catch (e: Exception) {
                logger.warn("启用音频质量监控失败（功能可能不可用）: ${e.message}")
            }

            // 设置预处理链
            try {
                memScoped {
                    val preprocessingChain = alloc<APMPreprocessingChain>()
                    preprocessingChain.enable_dc_removal = 1
                    preprocessingChain.enable_wind_noise_reduction = 1
                    preprocessingChain.enable_click_removal = 1
                    preprocessingChain.enable_automatic_gain_normalization = 1
                    
                    // 自定义高通滤波器
                    preprocessingChain.custom_high_pass.enabled = 1
                    preprocessingChain.custom_high_pass.cutoff_frequency_hz = 80.0f
                    preprocessingChain.custom_high_pass.order = 2
                    
                    webrtc_apm_set_preprocessing_chain(handle, preprocessingChain.ptr)
                    logger.info("预处理链已配置：DC移除、风噪抑制、点击移除、自动增益标准化")
                }
            } catch (e: Exception) {
                logger.warn("设置预处理链失败（功能可能不可用）: ${e.message}")
            }

            // 平台优化（如果可用）
            try {
                webrtc_apm_optimize_for_platform(handle, "Darwin_ARM64")
                webrtc_apm_set_performance_mode(handle, 1, 0)
                logger.info("平台优化完成：Darwin ARM64，低延迟模式")
            } catch (e: Exception) {
                logger.warn("平台优化失败（功能可能不可用）: ${e.message}")
            }

            // 应用语音助手预设模式（如果可用）
            try {
                webrtc_apm_apply_preset(handle, APM_PRESET_VOICE_ASSISTANT)
                logger.info("语音助手预设模式已应用")
            } catch (e: Exception) {
                logger.warn("应用语音助手预设失败（功能可能不可用）: ${e.message}")
                // 尝试应用默认预设
                try {
                    webrtc_apm_apply_preset(handle, APM_PRESET_DEFAULT)
                    logger.info("默认预设模式已应用")
                } catch (e2: Exception) {
                    logger.warn("应用默认预设也失败（功能可能不可用）: ${e2.message}")
                }
            }

            // 设置键盘声检测
            try {
                webrtc_apm_set_key_pressed(handle, 0)
                logger.info("WebRTC APM 键盘声检测已设置")
            } catch (e: Exception) {
                logger.warn("设置键盘声检测失败: ${e.message}")
            }

            // 设置流延迟
            try {
                webrtc_apm_set_stream_delay_ms(handle, 0)
                logger.info("WebRTC APM 流延迟已设置为0ms")
            } catch (e: Exception) {
                logger.warn("设置流延迟失败: ${e.message}")
            }

            // 初始化输入重采样器
            if (AudioDefaults.needsSampleRateConversion(inputFormat.sampleRate, apmFormat.sampleRate)) {
                inputResampler = SafeSoxrResampler.createForInput(
                    inputSampleRate = inputFormat.sampleRate,
                    outputSampleRate = apmFormat.sampleRate,
                    channels = inputFormat.channels
                )
                if (!inputResampler!!.initialize()) {
                    throw Exception("输入重采样器初始化失败")
                }
            }

            // 保存资源
            resources = resourceManager
            apmHandle = handle

            // 标记为完全初始化并启用处理
            isFullyInitialized = true
            processingEnabled = true

            logger.info("WebRTC APM 高级功能初始化成功")
            return true

        } catch (e: Exception) {
            logger.error("WebRTC APM 初始化失败: ${e.message}")
            close()
            return false
        }
    }

    suspend fun processFrame(audioData: ShortArray): ShortArray {
        if (!isFullyInitialized || !processingEnabled || apmHandle == null) {
            logger.debug("APM未完全初始化或处理被屏蔽，返回原始数据")
            return audioData
        }

        if (audioData.isEmpty()) {
            return audioData
        }

        // 增强的音量和质量检查
        val maxAmplitude = audioData.maxOfOrNull { abs(it.toInt()) } ?: 0
        val nonZeroCount = audioData.count { it != 0.toShort() }
        val zeroRatio = (audioData.size - nonZeroCount).toFloat() / audioData.size
        val rmsEnergy = kotlin.math.sqrt(audioData.map { (it.toDouble() * it.toDouble()) }.average())
        
        if (vadLogCounter % 100 == 0) {
            logger.debug("🔍 输入音频质量检查: 最大振幅=$maxAmplitude, 非零样本=${nonZeroCount}/${audioData.size}, 零值比例=${"%.4f".format( zeroRatio)}, RMS能量=${"%.2f".format(rmsEnergy)}")
        }
        
        // 如果输入数据质量极差，跳过APM处理
        if (zeroRatio > 0.98f) {
            if (vadLogCounter % 100 == 0) {
                logger.warn("⚠️ 输入数据零值过多(${"%.4f".format( zeroRatio)})，跳过APM处理")
            }
            return audioData
        }
        
        if (maxAmplitude < 5) {
            if (vadLogCounter % 100 == 0) {
                logger.debug("⚠️ 输入信号过弱(最大振幅=$maxAmplitude)，跳过APM处理")
            }
            return audioData
        }

        try {
            // 输入音频质量分析
            val inputMaxAmp = audioData.maxOfOrNull { abs(it.toInt()) } ?: 0
            val inputNonZeroCount = audioData.count { it != 0.toShort() }
            val inputZeroRatio = (audioData.size - inputNonZeroCount).toFloat() / audioData.size
            
            if (vadLogCounter % 500 == 0) {
                logger.debug("=== APM处理输入分析 ===")
                logger.debug("输入大小: ${audioData.size}样本")
                logger.debug("输入最大振幅: $inputMaxAmp")
                logger.debug("输入非零样本: ${inputNonZeroCount}/${audioData.size}")
                logger.debug("输入零值比例: ${"%.4f".format( inputZeroRatio)}")
                logger.debug("输入前5个样本: ${audioData.take(5).joinToString(",")}")
                logger.debug("输入RMS能量: ${"%.2f".format(rmsEnergy)}")
            }

            // 第1步：声道转换到APM格式
            val channelConvertedData = if (AudioDefaults.needsChannelConversion(inputFormat.channels, apmFormat.channels)) {
                when {
                    inputFormat.channels == 2 && apmFormat.channels == 1 -> {
                        if (vadLogCounter % 1000 == 0) {
                            logger.debug("声道转换: 2ch -> 1ch")
                        }
                        val converted = AudioUtils.stereoToMono(audioData)
                        
                        if (vadLogCounter % 500 == 0) {
                            val convertedMaxAmp = converted.maxOfOrNull { abs(it.toInt()) } ?: 0
                            val convertedNonZero = converted.count { it != 0.toShort() }
                            val convertedZeroRatio = (converted.size - convertedNonZero).toFloat() / converted.size
                            logger.debug("声道转换完成: 最大振幅=$convertedMaxAmp, 样本数=${converted.size}, 零值比例=${"%.4f".format( convertedZeroRatio)}")
                        }
                        converted
                    }
                    inputFormat.channels == 1 && apmFormat.channels == 2 -> {
                        if (vadLogCounter % 1000 == 0) {
                            logger.debug("声道转换: 1ch -> 2ch")
                        }
                        ShortArray(audioData.size * 2) { i -> audioData[i / 2] }
                    }
                    else -> {
                        logger.warn("不支持的声道转换: ${inputFormat.channels}ch -> ${apmFormat.channels}ch")
                        audioData
                    }
                }
            } else {
                audioData
            }

            // 第2步：重采样到APM格式
            val resampledData = if (inputResampler != null) {
                if (vadLogCounter % 1000 == 0) {
                    logger.debug("输入重采样: ${inputFormat.sampleRate}Hz -> ${apmFormat.sampleRate}Hz")
                }
                val resampled = inputResampler!!.process(channelConvertedData)
                
                if (vadLogCounter % 500 == 0) {
                    val resampledMaxAmp = resampled.maxOfOrNull { abs(it.toInt()) } ?: 0
                    val resampledNonZero = resampled.count { it != 0.toShort() }
                    val resampledZeroRatio = (resampled.size - resampledNonZero).toFloat() / resampled.size
                    logger.debug("重采样完成: ${channelConvertedData.size} -> ${resampled.size}样本, 最大振幅=$resampledMaxAmp, 零值比例=${"%.4f".format( resampledZeroRatio)}")
                    
                    // 检查重采样质量
                    if (resampledZeroRatio > 0.9f && inputZeroRatio < 0.5f) {
                        logger.error("🚨 重采样质量严重下降: 输入零值比例=${"%.4f".format( inputZeroRatio)} -> 输出零值比例=${"%.4f".format( resampledZeroRatio)}")
                    }
                }
                resampled
            } else {
                channelConvertedData
            }

            // 第3步：APM处理前的准备和检查
            val dataSize = resampledData.size
            if (!bufferManager.ensureBufferSize(dataSize, apmFormat.channels)) {
                logger.error("缓冲区分配失败")
                return resampledData
            }

            val inputBuffer = bufferManager.getInputBuffer()
            val outputBuffer = bufferManager.getOutputBuffer()
            val inputArrayPointer = bufferManager.getInputArrayPointer()
            val outputArrayPointer = bufferManager.getOutputArrayPointer()

            if (inputBuffer == null || outputBuffer == null || inputArrayPointer == null || outputArrayPointer == null) {
                logger.error("APM缓冲区为空")
                return resampledData
            }

            // APM处理前数据质量检查
            val preApmMaxAmp = resampledData.maxOfOrNull { abs(it.toInt()) } ?: 0
            val preApmNonZeroCount = resampledData.count { it != 0.toShort() }
            val preApmZeroRatio = (resampledData.size - preApmNonZeroCount).toFloat() / resampledData.size
            
            if (vadLogCounter % 500 == 0) {
                logger.debug("=== APM处理前状态 ===")
                logger.debug("数据大小: ${dataSize}样本")
                logger.debug("最大振幅: $preApmMaxAmp")
                logger.debug("非零样本: ${preApmNonZeroCount}/${resampledData.size}")
                logger.debug("零值比例: ${"%.4f".format( preApmZeroRatio)}")
            }
            
            // 如果APM输入数据质量太差，跳过APM处理
            if (preApmZeroRatio > 0.95f) {
                if (vadLogCounter % 100 == 0) {
                    logger.warn("⚠️ APM输入数据零值过多(${"%.4f".format( preApmZeroRatio)})，跳过APM处理")
                }
                return resampledData
            }

            // 填充输入缓冲区 - 转换为float
            for (i in 0 until dataSize) {
                inputBuffer[i] = (resampledData[i] / 32768f).coerceIn(-1f, 1f)
            }

            // 检查float缓冲区质量
            if (vadLogCounter % 500 == 0) {
                var maxFloatSample = 0f
                var nonZeroFloatCount = 0
                for (i in 0 until dataSize) {
                    val absSample = abs(inputBuffer[i])
                    if (absSample > maxFloatSample) {
                        maxFloatSample = absSample
                    }
                    if (absSample > 1e-6f) {
                        nonZeroFloatCount++
                    }
                }
                val floatZeroRatio = (dataSize - nonZeroFloatCount).toFloat() / dataSize
                logger.debug("Float缓冲区质量: 最大值=${"%.4f".format( maxFloatSample)}, 零值比例=${"%.4f".format( floatZeroRatio)}")
            }

            // WebRTC APM处理
            try {
                // 获取处理前的统计信息
                if (vadLogCounter % 1000 == 0) {
                    try {
                        val preSpeechLevel = webrtc_apm_get_speech_level_dbfs(apmHandle)
                        val preNoiseLevel = webrtc_apm_get_noise_level_dbfs(apmHandle)
                        logger.debug("APM处理前: 语音电平=${"%.1f".format( preSpeechLevel)}dBFS, 噪声电平=${"%.1f".format( preNoiseLevel)}dBFS")
                    } catch (e: Exception) {
                        logger.debug("获取处理前统计信息失败: ${e.message}")
                    }
                }
                
                webrtc_apm_process_stream(apmHandle, inputArrayPointer, outputArrayPointer)
                
                // 获取处理后的统计信息
                if (vadLogCounter % 1000 == 0) {
                    try {
                        val postSpeechLevel = webrtc_apm_get_speech_level_dbfs(apmHandle)
                        val postNoiseLevel = webrtc_apm_get_noise_level_dbfs(apmHandle)
                        val isSaturated = webrtc_apm_is_saturated(apmHandle)
                        logger.debug("APM处理后: 语音电平=${"%.1f".format( postSpeechLevel)}dBFS, 噪声电平=${"%.1f".format( postNoiseLevel)}dBFS, 饱和=$isSaturated")
                    } catch (e: Exception) {
                        logger.debug("获取处理后统计信息失败: ${e.message}")
                    }
                }
                
            } catch (e: Exception) {
                logger.error("APM处理失败: ${e.message}")
                return resampledData
            }

            // 提取处理结果并检查质量
            val processedData = ShortArray(dataSize) { i ->
                val floatSample = outputBuffer[i].coerceIn(-1f, 1f)
                (floatSample * 32767f).toInt().toShort()
            }

            // 检查处理后的音频质量
            val maxAmp = processedData.maxOfOrNull { abs(it.toInt()) } ?: 0
            val processedNonZeroCount = processedData.count { it != 0.toShort() }
            val processedZeroRatio = (processedData.size - processedNonZeroCount).toFloat() / processedData.size
            
            if (vadLogCounter % 500 == 0) {
                logger.debug("=== APM处理结果 ===")
                logger.debug("输出最大振幅: $maxAmp")
                logger.debug("输出非零样本: ${processedNonZeroCount}/${processedData.size}")
                logger.debug("输出零值比例: ${"%.4f".format( processedZeroRatio)}")
                logger.debug("输出前5个样本: ${processedData.take(5).joinToString(",")}")
                
                // 质量对比
                val amplitudeRatio = if (preApmMaxAmp > 0) maxAmp.toFloat() / preApmMaxAmp else 0f
                logger.debug("振幅变化: $preApmMaxAmp -> $maxAmp (比例: ${"%.3f".format( amplitudeRatio)})")
                logger.debug("零值变化: ${"%.4f".format( preApmZeroRatio)} -> ${"%.4f".format( processedZeroRatio)}")
                
                // APM质量评估
                if (processedZeroRatio > preApmZeroRatio + 0.3f) {
                    logger.warn("⚠️ APM处理显著增加了零值比例，可能过度处理")
                }
                if (amplitudeRatio > 3.0f) {
                    logger.warn("⚠️ APM处理显著增加了振幅，可能有增益问题")
                } else if (amplitudeRatio < 0.3f && maxAmp > 0) {
                    logger.warn("⚠️ APM处理显著降低了振幅，可能信号被过度抑制")
                }
            }

            if (maxAmp == 0) {
                logger.warn("⚠️ APM处理后音频全为0，使用原始数据")
                logger.warn("原始数据最大振幅: $inputMaxAmp, APM输入最大振幅: $preApmMaxAmp")
                return resampledData
            }

            // 检查是否音频质量严重下降
            if (processedZeroRatio > 0.95f && preApmZeroRatio < 0.5f) {
                logger.error("🚨 APM处理严重失败: 输入有效但输出几乎全零！")
                logger.error("输入零值比例: ${"%.4f".format( preApmZeroRatio)}, 输出零值比例: ${"%.4f".format( processedZeroRatio)}")
                logger.error("建议: 检查APM配置，可能噪声抑制过强")
                return resampledData
            }

            if (vadLogCounter++ % 1000 == 0) {
                logger.debug("APM处理完成: 最大振幅=$maxAmp, 处理链路: 原始(${inputMaxAmp}) -> 声道转换 -> 重采样 -> APM($maxAmp)")
            }

            return processedData

        } catch (e: Exception) {
            logger.error("APM处理失败: ${e.message}")
            return audioData
        }
    }

    suspend fun processAndResample(
        audioData: ShortArray,
        outputSampleRate: Int = apmFormat.sampleRate,
        outputChannels: Int = apmFormat.channels
    ): ShortArray {
        // 诊断模式：跳过APM处理，直接重采样
        val apmProcessedData = if (AudioDefaults.ENABLE_APM_DIAGNOSTIC_MODE) {
            logger.warn("🔧 诊断模式启用：跳过APM处理，直接重采样")
            
            // 直接进行必要的格式转换到APM格式
            val channelConvertedData = if (AudioDefaults.needsChannelConversion(inputFormat.channels, apmFormat.channels)) {
                when {
                    inputFormat.channels == 2 && apmFormat.channels == 1 -> {
                        AudioUtils.stereoToMono(audioData)
                    }
                    inputFormat.channels == 1 && apmFormat.channels == 2 -> {
                        ShortArray(audioData.size * 2) { i -> audioData[i / 2] }
                    }
                    else -> audioData
                }
            } else {
                audioData
            }
            
            // 采样率转换到APM格式
            if (inputResampler != null) {
                inputResampler!!.process(channelConvertedData)
            } else {
                channelConvertedData
            }
        } else {
            // 正常APM处理
            processFrame(audioData)
        }

        if (apmProcessedData.isEmpty()) {
            return apmProcessedData
        }

        val targetFormat = AudioDefaults.AudioFormat(outputSampleRate, outputChannels)

        // 如果目标格式与APM格式相同，直接返回
        if (apmFormat.isSameAs(targetFormat)) {
            return apmProcessedData
        }

        // 第2步：输出重采样 - 使用SafeSoxrResampler
        val resampledData = if (AudioDefaults.needsSampleRateConversion(apmFormat.sampleRate, outputSampleRate) || 
                                AudioDefaults.needsChannelConversion(apmFormat.channels, outputChannels)) {
            
            if (vadLogCounter % 500 == 0) {
                logger.debug("输出重采样和声道转换: ${apmFormat.sampleRate}Hz/${apmFormat.channels}ch -> ${outputSampleRate}Hz/${outputChannels}ch")
            }

            // 使用SafeSoxrResampler处理采样率和声道转换
            val outputResampler = SafeSoxrResampler.createForOutput(
                inputSampleRate = apmFormat.sampleRate,
                outputSampleRate = outputSampleRate,
                inputChannels = apmFormat.channels,
                outputChannels = outputChannels
            )
            
            try {
                if (outputResampler.initialize()) {
                    val result = outputResampler.process(apmProcessedData)
                    
                    if (vadLogCounter % 500 == 0) {
                        val maxAmp = result.maxOfOrNull { abs(it.toInt()) } ?: 0
                        logger.debug("输出重采样完成: ${apmProcessedData.size} -> ${result.size}样本, 最大振幅=$maxAmp")
                    }
                    
                    result
                } else {
                    logger.error("输出重采样器初始化失败，使用原始数据")
                    apmProcessedData
                }
            } catch (e: Exception) {
                logger.error("输出重采样失败: ${e.message}，使用原始数据")
                apmProcessedData
            } finally {
                outputResampler.release()
            }
        } else {
            if (vadLogCounter % 1000 == 0) {
                logger.debug("格式相同，跳过输出重采样")
            }
            apmProcessedData
        }
        
        return resampledData
    }

    // VAD和其他方法保持不变...
    fun isVoiceDetected(): Boolean {
        if (!isFullyInitialized || !processingEnabled || apmHandle == null) {
            return false
        }

        try {
            // 基本能量检查
            val inputBuffer = bufferManager.getInputBuffer()
            val bufferSize = bufferManager.getCurrentBufferSize()

            var finalVadResult = false
            if (inputBuffer != null && bufferSize > 0) {
                var energy = 0.0f
                for (i in 0 until bufferSize) {
                    val sample = inputBuffer[i]
                    energy += sample * sample
                }

                val rmsEnergy = kotlin.math.sqrt(energy / bufferSize)
                finalVadResult = rmsEnergy >= AudioDefaults.MIN_RMS_ENERGY
                
                if (vadLogCounter % 1000 == 0) {
                    logger.debug("VAD能量检测: RMS=${"%.4f".format(rmsEnergy)}, 阈值=${AudioDefaults.MIN_RMS_ENERGY}, 结果=$finalVadResult")
                }
            }

            // VAD去抖动处理
            if (finalVadResult) {
                consecutiveVadPositive++
                val result = consecutiveVadPositive >= vadDebounceFrames

                if (result != lastVadResult) {
                    logger.debug("VAD状态变化: $lastVadResult -> $result (连续正检测: $consecutiveVadPositive)")
                    lastVadResult = result
                }

                return result
            } else {
                if (consecutiveVadPositive > 0) {
                    consecutiveVadPositive = kotlin.math.max(0, consecutiveVadPositive - 2)

                    if (consecutiveVadPositive == 0 && lastVadResult) {
                        logger.debug("VAD状态变化: true -> false")
                        lastVadResult = false
                    }
                }
                return consecutiveVadPositive >= vadDebounceFrames
            }
            
        } catch (e: Exception) {
            logger.error("VAD检测失败: ${e.message}")
            return false
        }
    }


    // 配置方法
    fun setVadThreshold(threshold: Float) {
        logger.info("VAD阈值设置请求: $threshold (注意: WebRTC APM内部VAD阈值不可直接设置)")
    }

    fun setVadDebounceFrames(frames: Int) {
        vadDebounceFrames = frames.coerceAtLeast(1)
        logger.info("VAD去抖动帧数设置为: $vadDebounceFrames")
    }

    // 注意：以下方法使用的API已被弃用，暂时移除

    // fun enableNoiseSuppression(enable: Boolean)  
    // fun enableAutomaticGainControl(enable: Boolean)
    // fun enableVoiceActivityDetection(enable: Boolean)
    // fun setKeyPressed(keyPressed: Boolean)

    // 获取器方法
    fun getActualInputSampleRate(): Int = inputFormat.sampleRate
    fun getInputChannels(): Int = inputFormat.channels
    fun getApmSampleRate(): Int = apmFormat.sampleRate
    fun getApmChannels(): Int = apmFormat.channels
    fun getApmHandle(): CPointer<*>? = apmHandle
    fun enableEchoCancellation(enable: Boolean){
        apmHandle?.let {
            if (enable && AudioDefaults.ENABLE_ECHO_CANCELLATION_SAFE_MODE) {
                // 安全模式下拒绝启用回声消除
                logger.error("🚫 拒绝启用回声消除：安全模式已启用")
                logger.error("🚫 原因：语音助手模式下AEC3会导致BlockFramer崩溃")
                logger.error("🚫 配置：ENABLE_ECHO_CANCELLATION_SAFE_MODE = ${AudioDefaults.ENABLE_ECHO_CANCELLATION_SAFE_MODE}")
                logger.error("🚫 建议：保持当前配置以确保系统稳定性")
                logger.info("回声消除保持禁用状态（安全模式）")
                return
            }
            
            if (enable) {
                // 非安全模式下的警告（但仍然允许启用）
                logger.warn("⚠️ 警告：语音助手模式下启用回声消除可能导致BlockFramer崩溃")
                logger.warn("⚠️ 原因：AEC3需要同时配置capture和render流，但语音助手只有capture流")
                logger.warn("⚠️ 建议：考虑启用ENABLE_ECHO_CANCELLATION_SAFE_MODE")
                
                webrtc_apm_enable_aec(it, 1)
                logger.warn("回声消除已启用（可能不稳定）")
            } else {
                webrtc_apm_enable_aec(it, 0)
                logger.info("回声消除已禁用")
            }
        } ?: run {
            logger.error("无法设置回声消除：APM句柄为空")
        }
    }
    fun updateInputParameters(sampleRate: Int, channels: Int) {
        val newFormat = AudioDefaults.AudioFormat(sampleRate, channels)
        if (!inputFormat.isSameAs(newFormat)) {
            logger.info("更新输入参数: 旧($inputFormat) -> 新($newFormat)")
            logger.info("转换路径变化: ${AudioDefaults.getConversionPath(newFormat, apmFormat)}")
            inputFormat = newFormat

            // 重新初始化输入重采样器
            inputResampler?.release()
            inputResampler = null

            if (AudioDefaults.needsSampleRateConversion(inputFormat.sampleRate, apmFormat.sampleRate)) {
                inputResampler = SafeSoxrResampler.createForInput(
                    inputSampleRate = inputFormat.sampleRate,
                    outputSampleRate = apmFormat.sampleRate,
                    channels = inputFormat.channels
                )
                if (!inputResampler!!.initialize()) {
                    logger.error("重新初始化输入重采样器失败")
                    inputResampler?.release()
                    inputResampler = null
                }
            }

            logger.info("输入参数已更新")
        }
    }

    override fun close() {
        try {
            // 停止处理
            processingEnabled = false
            isFullyInitialized = false

            // 释放重采样器
            inputResampler?.release()
            inputResampler = null

            // 释放缓冲区管理器
            bufferManager.release()

            // 释放主要资源
            resources?.close()
            resources = null
            apmHandle = null

            logger.info("WebRTC APM 资源已释放")
        } catch (e: Exception) {
            logger.error("释放资源失败: ${e.message}")
        }
    }

    // 为了向后兼容，保留release方法
    fun release() {
        close()
    }

    /**
     * 生成详细的APM诊断报告（利用所有新功能）
     */
    fun generateDiagnosticReport(): String {
        return buildString {
            appendLine("===== WebRTC APM 高级诊断报告 =====")
            appendLine("时间: ${Clock.System.now()}")
            appendLine()
            
            // 基本状态
            appendLine("=== 基本状态 ===")
            appendLine("初始化状态: $isFullyInitialized")
            appendLine("处理启用状态: $processingEnabled")
            appendLine("APM句柄有效: ${apmHandle != null}")
            appendLine()
            
            // 音频格式配置
            appendLine("=== 音频格式配置 ===")
            appendLine("输入格式: $inputFormat")
            appendLine("APM内部格式: $apmFormat")
            appendLine("需要输入重采样: ${inputResampler != null}")
            appendLine("格式转换路径: ${AudioDefaults.getConversionPath(inputFormat, apmFormat)}")
            appendLine()
            
            // VAD参数
            appendLine("=== VAD参数 ===")
            appendLine("VAD去抖动帧数: $vadDebounceFrames")
            appendLine("连续VAD正检测: $consecutiveVadPositive")
            appendLine("上次VAD结果: $lastVadResult")
            appendLine("VAD计数器: $vadLogCounter")
            appendLine()
            
            // 语音助手专用功能状态
            if (apmHandle != null) {
                try {
                    appendLine("=== 语音助手专用功能 ===")
                    val wakeWordEnv = detectWakeWordEnvironment()
                    val clarityScore = getSpeechClarityScore()
                    val doubleTalk = detectDoubleTalk()
                    val reverberationTime = estimateReverberationTime()
                    
                    appendLine("唤醒词环境适合性: ${if (wakeWordEnv) "适合" else "不适合"}")
                    appendLine("语音清晰度评分: ${"%.3f".format(clarityScore)}")
                    appendLine("双讲检测: ${if (doubleTalk) "检测到" else "无"}")
                    appendLine("混响时间估计: ${"%.2f".format(reverberationTime)}秒")
                    appendLine()
                    
                    // 音频质量评估
                    val audioQuality = assessAudioQuality()
                    if (audioQuality != null) {
                        appendLine(audioQuality)
                        appendLine()
                    }
                    
                    // 频率响应分析
                    val frequencyResponse = getFrequencyResponse()
                    if (frequencyResponse != null) {
                        val (magnitude, phase) = frequencyResponse
                        val avgMagnitude = magnitude.average()
                        val peakFreqIndex = magnitude.indices.maxByOrNull { magnitude[it] } ?: 0
                        val peakFrequency = peakFreqIndex * 24000f / magnitude.size  // 假设采样率48kHz
                        
                        appendLine("=== 频率响应分析 ===")
                        appendLine("平均幅度: ${"%.4f".format(avgMagnitude)}")
                        appendLine("峰值频率: ${"%.1f".format(peakFrequency)}Hz")
                        appendLine("频率响应点数: ${magnitude.size}")
                        appendLine()
                    }
                    
                    // 线性AEC输出
                    val linearAecOutput = getLinearAecOutput()
                    if (linearAecOutput != null) {
                        val aecAvg = linearAecOutput.average()
                        val aecMax = linearAecOutput.maxOrNull() ?: 0f
                        
                        appendLine("=== 线性AEC输出分析 ===")
                        appendLine("AEC输出平均值: ${"%.6f".format(aecAvg)}")
                        appendLine("AEC输出最大值: ${"%.6f".format(aecMax)}")
                        appendLine("AEC输出长度: ${linearAecOutput.size}")
                        appendLine()
                    }
                    
                } catch (e: Exception) {
                    appendLine("=== 高级功能检测失败 ===")
                    appendLine("错误: ${e.message}")
                    appendLine()
                }
            }
            
            // 扩展统计信息（使用新的高级方法）
            try {
                val extendedStats = getExtendedStatistics()
                appendLine(extendedStats)
                appendLine()
            } catch (e: Exception) {
                appendLine("=== 扩展统计信息获取失败 ===")
                appendLine("错误: ${e.message}")
                appendLine()
            }
            
            // 基本APM状态（保留向后兼容）
            if (apmHandle != null) {
                try {
                    appendLine("=== 基本APM状态 ===")
                    val analogLevel = webrtc_apm_get_stream_analog_level(apmHandle)
                    val currentDelay = webrtc_apm_get_stream_delay_ms(apmHandle)
                    
                    appendLine("流延迟: ${currentDelay}ms")
                    appendLine("模拟电平: $analogLevel")
                    
                    // 错误状态检查
                    val lastError = getLastErrorCode()
                    if (lastError != null) {
                        val errorString = getErrorString(lastError)
                        appendLine("最后错误码: $lastError")
                        appendLine("错误描述: ${errorString ?: "未知错误"}")
                    } else {
                        appendLine("错误状态: 无错误")
                    }
                    
                } catch (e: Exception) {
                    appendLine("=== 基本APM状态获取失败 ===")
                    appendLine("错误: ${e.message}")
                }
            } else {
                appendLine("=== APM未初始化 ===")
                appendLine("无法获取统计信息")
            }
            
            appendLine()
            
            // 缓冲区状态
            appendLine("=== 缓冲区状态 ===")
            appendLine("当前缓冲区大小: ${bufferManager.getCurrentBufferSize()}")
            appendLine("输入缓冲区: ${bufferManager.getInputBuffer() != null}")
            appendLine("输出缓冲区: ${bufferManager.getOutputBuffer() != null}")
            appendLine("输入数组指针: ${bufferManager.getInputArrayPointer() != null}")
            appendLine("输出数组指针: ${bufferManager.getOutputArrayPointer() != null}")
            appendLine()
            
            // 配置建议（更新版本）
            appendLine("=== 智能配置建议 ===")
            if (!isFullyInitialized) {
                appendLine("⚠️ APM未初始化，请检查初始化流程")
            }
            if (!processingEnabled) {
                appendLine("⚠️ APM处理被禁用，请检查processingEnabled标志")
            }
            if (inputResampler != null) {
                appendLine("✓ 输入重采样器已配置")
            } else if (AudioDefaults.needsSampleRateConversion(inputFormat.sampleRate, apmFormat.sampleRate)) {
                appendLine("⚠️ 需要重采样但重采样器未配置")
            }
            
            if (apmHandle != null) {
                try {
                    val analogLevel = webrtc_apm_get_stream_analog_level(apmHandle)
                    
                    if (analogLevel > 200) {
                        appendLine("⚠️ 模拟电平过高($analogLevel)，可能导致失真")
                        appendLine("   建议: 调用setStreamAnalogLevel()降低到150以下")
                    } else if (analogLevel < 50) {
                        appendLine("⚠️ 模拟电平过低($analogLevel)，信号可能不足")
                        appendLine("   建议: 调用setStreamAnalogLevel()提高到100以上")
                    } else {
                        appendLine("✓ 模拟电平正常: $analogLevel")
                    }
                    
                    // 语音助手环境建议
                    val wakeWordEnv = detectWakeWordEnvironment()
                    if (!wakeWordEnv) {
                        appendLine("⚠️ 当前环境不太适合唤醒词检测")
                        appendLine("   建议: 检查背景噪声，调整麦克风位置或增益")
                    } else {
                        appendLine("✓ 唤醒词检测环境良好")
                    }
                    
                    // 语音清晰度建议
                    val clarityScore = getSpeechClarityScore()
                    if (clarityScore < 0.5f) {
                        appendLine("⚠️ 语音清晰度较低(${"%.3f".format(clarityScore)})")
                        appendLine("   建议: 检查噪声抑制设置，考虑调整前置放大器增益")
                    } else {
                        appendLine("✓ 语音清晰度良好: ${"%.3f".format(clarityScore)}")
                    }
                    
                } catch (e: Exception) {
                    appendLine("⚠️ 无法获取实时数据进行智能建议: ${e.message}")
                }
            }
            
            appendLine()
            appendLine("=== 高级功能清单 ===")
            appendLine("✓ 高级回声消除 (AEC3 + 性能优化)")
            appendLine("✓ 高级语音检测 (RNN-VAD + 优化)")
            appendLine("✓ AGC2 自适应数字增益控制")
            appendLine("✓ 多通道处理支持")
            appendLine("✓ 音频质量实时评估")
            appendLine("✓ 语音助手专用优化")
            appendLine("✓ 远场处理优化")
            appendLine("✓ 自适应处理")
            appendLine("✓ 预处理链 (DC移除、风噪抑制等)")
            appendLine("✓ 平台特定优化")
            appendLine("✓ 实时参数调节")
            appendLine("✓ 线性AEC输出")
            appendLine("✓ 动态配置更新")
            appendLine("✓ 音频流分析")
            appendLine("✓ 双讲检测")
            appendLine("✓ 混响时间估计")
            appendLine("✓ 频率响应分析")
            appendLine("✓ 扩展统计信息")
            appendLine("✓ 错误处理和诊断")
            
            appendLine()
            appendLine("=== 诊断模式设置 ===")
            appendLine("播放确认功能: ${AudioDefaults.ENABLE_PLAYBACK_CONFIRMATION}")
            appendLine("APM诊断模式: ${AudioDefaults.ENABLE_APM_DIAGNOSTIC_MODE}")
            
            if (AudioDefaults.ENABLE_APM_DIAGNOSTIC_MODE) {
                appendLine("🔧 当前启用APM诊断模式，APM处理被跳过")
            }
            
            // 导出当前配置
            try {
                val configJson = exportConfigurationJson()
                if (configJson != null) {
                    appendLine()
                    appendLine("=== 当前配置JSON ===")
                    appendLine(configJson)
                }
            } catch (e: Exception) {
                appendLine()
                appendLine("=== 配置导出失败 ===")
                appendLine("错误: ${e.message}")
            }
        }
    }

    // 新增：动态参数调节接口
    fun setNoiseSuppressionLevel(level: APMNsLevel) {
        apmHandle?.let {
            webrtc_apm_set_ns_level(it, level)
            logger.info("动态调节噪声抑制级别: $level")
        }
    }

    fun setAgcTargetLevel(targetDbfs: Int) {
        apmHandle?.let {
            val clampedTarget = targetDbfs.coerceIn(0, 31)
            webrtc_apm_set_agc_target_level(it, clampedTarget)
            logger.info("动态调节AGC目标电平: ${clampedTarget}dBFS")
        }
    }

    fun setPreAmplifierGain(gainFactor: Float) {
        apmHandle?.let {
            val clampedGain = gainFactor.coerceIn(0.1f, 10.0f)
            webrtc_apm_set_pre_amplifier_gain(it, clampedGain)
            val gainDb = 20 * kotlin.math.log10(clampedGain)
            logger.info("动态调节前置放大器增益: ${"%.2f".format(clampedGain)} (${"%.1f".format(gainDb)}dB)")
        }
    }

    // 新增：模拟电平控制
    fun setStreamAnalogLevel(level: Int) {
        apmHandle?.let {
            val clampedLevel = level.coerceIn(0, 255)
            webrtc_apm_set_stream_analog_level(it, clampedLevel)
            if (vadLogCounter % 500 == 0) {
                logger.debug("设置模拟电平: $clampedLevel")
            }
        }
    }

    fun getStreamAnalogLevel(): Int {
        return apmHandle?.let {
            webrtc_apm_get_stream_analog_level(it)
        } ?: 0
    }

    // 新增：调试录制功能
    fun enableDebugRecording(filePath: String): Boolean {
        return apmHandle?.let {
            try {
                webrtc_apm_enable_debug_recording(it, filePath)
                logger.info("启用APM调试录制: $filePath")
                true
            } catch (e: Exception) {
                logger.error("启用调试录制失败: ${e.message}")
                false
            }
        } ?: false
    }

    fun disableDebugRecording() {
        apmHandle?.let {
            try {
                webrtc_apm_disable_debug_recording(it)
                logger.info("禁用APM调试录制")
            } catch (e: Exception) {
                logger.error("禁用调试录制失败: ${e.message}")
            }
        }
    }

    // 新增：统计信息重置
    fun resetStatistics() {
        apmHandle?.let {
            webrtc_apm_reset_statistics(it)
            logger.info("APM统计信息已重置")
        }
    }

    // === 新增高级功能方法 ===

    /**
     * 获取扩展统计信息（修复版本）
     */
    fun getExtendedStatistics(): String {
        return apmHandle?.let { handle ->
            try {
                // 使用基本统计信息，因为扩展结构体可能不可用
                buildString {
                    appendLine("=== APM统计信息 ===")
                    appendLine("时间: ${Clock.System.now()}")
                    appendLine()
                    
                    // 基础统计 - 使用individual API calls避免结构体访问问题
                    appendLine("--- 基础统计 ---")
                    try {
                        val voiceDetected = webrtc_apm_voice_detected(handle)
                        appendLine("语音检测: ${if (voiceDetected != 0) "是" else "否"}")
                    } catch (e: Exception) {
                        appendLine("语音检测: 不可用")
                    }
                    
                    try {
                        val voiceProbability = webrtc_apm_get_voice_probability(handle)
                        appendLine("语音概率: ${"%.3f".format(voiceProbability)}")
                    } catch (e: Exception) {
                        appendLine("语音概率: 不可用")
                    }
                    
                    try {
                        val isSaturated = webrtc_apm_is_saturated(handle)
                        appendLine("饱和检测: ${if (isSaturated != 0) "是" else "否"}")
                    } catch (e: Exception) {
                        appendLine("饱和检测: 不可用")
                    }
                    
                    try {
                        val analogLevel = webrtc_apm_get_stream_analog_level(handle)
                        appendLine("模拟电平: $analogLevel")
                    } catch (e: Exception) {
                        appendLine("模拟电平: 不可用")
                    }
                    
                    try {
                        val delay = webrtc_apm_get_stream_delay_ms(handle)
                        appendLine("流延迟: ${delay}ms")
                    } catch (e: Exception) {
                        appendLine("流延迟: 不可用")
                    }
                    
                    try {
                        val speechLevel = webrtc_apm_get_speech_level_dbfs(handle)
                        appendLine("语音电平: ${"%.2f".format(speechLevel)}dBFS")
                    } catch (e: Exception) {
                        appendLine("语音电平: 不可用")
                    }
                    
                    try {
                        val noiseLevel = webrtc_apm_get_noise_level_dbfs(handle)
                        appendLine("噪声电平: ${"%.2f".format(noiseLevel)}dBFS")
                    } catch (e: Exception) {
                        appendLine("噪声电平: 不可用")
                    }
                }
            } catch (e: Exception) {
                "获取统计信息失败: ${e.message}"
            }
        } ?: "APM实例不可用"
    }

    /**
     * 评估音频质量（简化版本）
     */
    fun assessAudioQuality(): String? {
        return apmHandle?.let { handle ->
            try {
                // 使用基本方法获取音频质量信息
                val speechLevel = webrtc_apm_get_speech_level_dbfs(handle)
                val noiseLevel = webrtc_apm_get_noise_level_dbfs(handle)
                val isSaturated = webrtc_apm_is_saturated(handle)
                val voiceProbability = webrtc_apm_get_voice_probability(handle)
                
                buildString {
                    appendLine("=== 音频质量评估 ===")
                    appendLine("语音电平: ${"%.2f".format(speechLevel)}dBFS")
                    appendLine("噪声电平: ${"%.2f".format(noiseLevel)}dBFS")
                    appendLine("信噪比: ${"%.2f".format(speechLevel - noiseLevel)}dB")
                    appendLine("语音概率: ${"%.3f".format(voiceProbability)}")
                    appendLine("饱和检测: ${if (isSaturated != 0) "检测到" else "无"}")
                }
            } catch (e: Exception) {
                logger.error("音频质量评估失败: ${e.message}")
                null
            }
        }
    }

    /**
     * 分析音频流（简化版本）
     */
    fun analyzeAudioStream(audioData: ShortArray): String? {
        if (audioData.isEmpty()) return null
        
        return try {
            // 简单的音频统计分析
            val maxAmplitude = audioData.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
            val rmsEnergy = kotlin.math.sqrt(audioData.map { (it.toDouble() * it.toDouble()) }.average())
            val zeroRate = audioData.count { it == 0.toShort() }.toFloat() / audioData.size
            
            buildString {
                appendLine("=== 音频流分析 ===")
                appendLine("最大振幅: $maxAmplitude")
                appendLine("RMS能量: ${"%.2f".format(rmsEnergy)}")
                appendLine("零值比例: ${"%.4f".format(zeroRate)}")
                appendLine("包含语音: ${if (maxAmplitude > 1000 && zeroRate < 0.5f) "是" else "否"}")
                appendLine("样本数量: ${audioData.size}")
            }
        } catch (e: Exception) {
            logger.error("音频流分析失败: ${e.message}")
            "音频流分析失败: ${e.message}"
        }
    }

    /**
     * 获取频率响应（简化版本）
     */
    fun getFrequencyResponse(numBins: Int = 256): Pair<FloatArray, FloatArray>? {
        return apmHandle?.let { handle ->
            try {
                memScoped {
                    val magnitude = allocArray<FloatVar>(numBins)
                    val phase = allocArray<FloatVar>(numBins)
                    
                    webrtc_apm_get_frequency_response_ex(handle, magnitude, phase, numBins)
                    
                    val magnitudeArray = FloatArray(numBins) { magnitude[it] }
                    val phaseArray = FloatArray(numBins) { phase[it] }
                    
                    Pair(magnitudeArray, phaseArray)
                }
            } catch (e: Exception) {
                logger.debug("获取频率响应失败（功能可能不可用）: ${e.message}")
                null
            }
        }
    }

    /**
     * 设置实时运行时参数
     */
    fun setRuntimeSetting(type: APMRuntimeSettingType, value: Float) {
        apmHandle?.let { handle ->
            try {
                memScoped {
                    val setting = alloc<APMRuntimeSetting>()
                    setting.type = type
                    setting.value.float_value = value
                    
                    webrtc_apm_set_runtime_setting(handle, setting.ptr)
                    logger.info("设置运行时参数: type=$type, value=$value")
                }
            } catch (e: Exception) {
                logger.debug("设置运行时参数失败（功能可能不可用）: ${e.message}")
            }
        }
    }

    /**
     * 获取线性AEC输出
     */
    fun getLinearAecOutput(): FloatArray? {
        return apmHandle?.let { handle ->
            try {
                memScoped {
                    val outputPointer = allocArray<CPointerVar<FloatVar>>(1)
                    val lengthPointer = allocArray<kotlinx.cinterop.IntVar>(1)
                    
                    val result = webrtc_apm_get_linear_aec_output(handle, outputPointer, lengthPointer)
                    
                    if (result != 0 && lengthPointer[0] > 0) {
                        val length = lengthPointer[0]
                        val output = outputPointer[0]
                        
                        FloatArray(length) { i -> output!![i] }
                    } else {
                        null
                    }
                }
            } catch (e: Exception) {
                logger.debug("获取线性AEC输出失败（功能可能不可用）: ${e.message}")
                null
            }
        }
    }

    /**
     * 动态更新配置（JSON格式）
     */
    fun updateConfigurationRuntime(configJson: String): Boolean {
        return apmHandle?.let { handle ->
            try {
                // 修复字符串转换问题 - 使用cstr
                webrtc_apm_update_config_runtime(handle, configJson)
                logger.info("动态配置更新成功")
                true
            } catch (e: Exception) {
                logger.debug("动态配置更新失败（功能可能不可用）: ${e.message}")
                false
            }
        } ?: false
    }

    /**
     * 导出当前配置为JSON
     */
    fun exportConfigurationJson(): String? {
        return apmHandle?.let { handle ->
            try {
                val configPtr = webrtc_apm_export_config_json(handle)
                // 修复字符串转换问题 - 直接使用toKString()
                configPtr?.toKString()
            } catch (e: Exception) {
                logger.debug("导出配置JSON失败（功能可能不可用）: ${e.message}")
                null
            }
        }
    }

    /**
     * 应用不同的APM预设模式
     */
    fun applyPresetMode(mode: APMPresetMode): Boolean {
        return apmHandle?.let { handle ->
            try {
                webrtc_apm_apply_preset(handle, mode)
                val modeName = when(mode) {
                    APM_PRESET_DEFAULT -> "默认"
                    APM_PRESET_CONFERENCE -> "会议" 
                    APM_PRESET_MUSIC -> "音乐"
                    APM_PRESET_SPEECH -> "语音通话"
                    APM_PRESET_LOW_LATENCY -> "低延迟"
                    APM_PRESET_VOICE_ASSISTANT -> "语音助手"
                    else -> "未知($mode)"
                }
                logger.info("应用APM预设模式: $modeName")
                true
            } catch (e: Exception) {
                logger.debug("应用预设模式失败（功能可能不可用）: ${e.message}")
                false
            }
        } ?: false
    }

    /**
     * 错误处理
     */
    fun getLastErrorCode(): APMErrorCode? {
        return apmHandle?.let { handle ->
            try {
                webrtc_apm_get_last_error(handle)
            } catch (e: Exception) {
                logger.debug("获取错误码失败（功能可能不可用）: ${e.message}")
                null
            }
        }
    }

    fun getErrorString(errorCode: APMErrorCode): String? {
        return try {
            val errorString = webrtc_apm_get_error_string(errorCode)
            // 修复字符串转换问题
            errorString?.let { ptr ->
                val length = strlen(ptr.toKString()).toInt()
                if (length > 0) {
                    buildString {
                        for (i in 0 until length) {
                            append(ptr[i].toInt().toChar())
                        }
                    }
                } else {
                    "未知错误"
                }
            }
        } catch (e: Exception) {
            logger.debug("获取错误字符串失败（功能可能不可用）: ${e.message}")
            null
        }
    }

    // === 基本功能方法 ===

    /**
     * 检测唤醒词环境是否适合
     */
    fun detectWakeWordEnvironment(): Boolean {
        return apmHandle?.let { handle ->
            try {
                val result = webrtc_apm_detect_wake_word_environment(handle)
                logger.debug("唤醒词环境检测结果: ${if (result != 0) "适合" else "不适合"}")
                result != 0
            } catch (e: Exception) {
                logger.debug("唤醒词环境检测不可用: ${e.message}")
                false
            }
        } ?: false
    }

    /**
     * 获取语音清晰度评分
     */
    fun getSpeechClarityScore(): Float {
        return apmHandle?.let { handle ->
            try {
                val score = webrtc_apm_get_speech_clarity_score(handle)
                logger.debug("语音清晰度评分: ${"%.3f".format(score)}")
                score
            } catch (e: Exception) {
                logger.debug("语音清晰度评分不可用: ${e.message}")
                0.0f
            }
        } ?: 0.0f
    }

    /**
     * 检测双讲
     */
    fun detectDoubleTalk(): Boolean {
        return apmHandle?.let { handle ->
            try {
                val result = webrtc_apm_detect_double_talk_ex(handle)
                result != 0
            } catch (e: Exception) {
                logger.debug("双讲检测不可用: ${e.message}")
                false
            }
        } ?: false
    }

    /**
     * 估计混响时间
     */
    fun estimateReverberationTime(): Float {
        return apmHandle?.let { handle ->
            try {
                webrtc_apm_estimate_reverberation_time_ex(handle)
            } catch (e: Exception) {
                logger.debug("混响时间估计不可用: ${e.message}")
                0.0f
            }
        } ?: 0.0f
    }
}