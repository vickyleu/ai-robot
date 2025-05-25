// 简化的 WebRtcApm.kt - 只使用标准WebRTC APM + SOXR
@file:OptIn(ExperimentalForeignApi::class)

package voice.audio.processing

import com.airobot.webrtcapminterop.*
import kotlinx.cinterop.*
import voice.util.AudioDefaults
import voice.util.AudioUtils
import voice.util.LogManager
import kotlin.math.abs

class WebRtcApm {
    private val logger = LogManager.getLogger("WebRtcApm")
    private var apmHandle: CPointer<*>? = null

    // APM内部处理参数
    private var apmSampleRate: Int = 0
    private var apmChannels: Int = 0

    // 音频缓冲区 - 动态分配，不再固定大小
    private var inputFloatBuffer: CPointer<FloatVar>? = null
    private var outputFloatBuffer: CPointer<FloatVar>? = null
    private var inputArrayPointer: CPointer<CPointerVar<FloatVar>>? = null
    private var outputArrayPointer: CPointer<CPointerVar<FloatVar>>? = null
    private var currentBufferSize: Int = 0

    // SOXR重采样器
    private var soxrWrapper: CPointer<SoxWrapper>? = null
    private var resamplerInitialized = false
    private var currentInputRate: Int = 0

    // 输出重采样器
    private var outputSoxrWrapper: CPointer<SoxWrapper>? = null
    private var outputResamplerInitialized = false
    private var outputSampleRate: Int = 48000

    // 输入参数
    private var actualInputSampleRate: Int = 0
    private var inputChannels: Int = 0

    // VAD参数
    private var vadLogCounter: Int = 0
    private var consecutiveVadPositive: Int = 0
    private var lastVadResult: Boolean = false
    private var vadDebounceFrames: Int = 3  // 减少到3帧，快速响应

    fun initialize(sampleRate: Int, channels: Int): Boolean {
        if (apmHandle != null) {
            logger.warn("WebRTC APM 已经初始化，使用先前配置: ${this.apmSampleRate}Hz, ${this.apmChannels}ch")
            if (this.actualInputSampleRate != sampleRate || this.inputChannels != channels) {
                logger.warn("警告: 尝试使用不同的参数重新初始化已有的APM实例。旧参数: ${this.actualInputSampleRate}/${this.inputChannels}, 新参数: $sampleRate/$channels. 建议先释放再初始化。")
            }
            return true
        }

        // 保存实际输入参数（来自设备的原始采样率与通道数）
        this.actualInputSampleRate = sampleRate
        this.inputChannels = channels

        // WebRTC APM 内部固定处理参数
        this.apmSampleRate = AudioDefaults.WEBRTC_APM_SAMPLE_RATE // APM 内部固定处理16kHz
        this.apmChannels = AudioDefaults.WEBRTC_APM_CHANNELS     // APM 内部固定处理单声道

        logger.info("APM配置: 输入=${this.actualInputSampleRate}Hz/${this.inputChannels}ch, APM内部处理=${this.apmSampleRate}Hz/${this.apmChannels}ch")

        try {
            // 创建APM实例
            apmHandle = webrtc_apm_create()
            if (apmHandle == null) {
                logger.error("WebRTC APM 创建失败")
                return false
            }

            // 配置APM
            memScoped {
                val config = alloc<APMConfig>()

                // 只保留VAD和键盘检测功能，启用瞬态抑制
                config.noise_suppression.enabled = false
                config.noise_suppression.level = kNsLow

                config.high_pass_filter.enabled = false

                config.gain_controller.enabled = false
                config.gain_controller.mode = kAgcAdaptiveDigital
                config.gain_controller.target_level_dbfs = 6
                config.gain_controller.compression_gain_db = 3
                config.gain_controller.enable_limiter = false

                config.pre_amplifier.enabled = true
                config.pre_amplifier.fixed_gain_factor = 1.5f // 进一步降低放大倍数，避免过度放大

                config.voice_detection.enabled = true // 保留VAD功能

                config.echo_canceller.enabled = false
                config.transient_suppression.enabled = true // 启用瞬态抑制，有助于屏蔽键盘声
                config.residual_echo_detector.enabled = false

                webrtc_apm_apply_config(apmHandle, config.ptr)
            }

            // 准备APM处理
            webrtc_apm_prepare(apmHandle, this.apmSampleRate, this.apmChannels)

            // 启用键盘声检测
            try {
                my_webrtc_apm_set_key_pressed(apmHandle, 0)
                logger.info("WebRTC APM 键盘声检测已启用")
            } catch (e: Exception) {
                logger.warn("启用键盘声检测失败: ${e.message}")
            }

            // 分配缓冲区
            allocateBuffers()

            // 初始化重采样器
            initializeResampler()

            logger.info("WebRTC APM 初始化成功")
            return true
        } catch (e: Exception) {
            logger.error("WebRTC APM 初始化失败: ${e.message}")
            release()
            return false
        }
    }

    private fun allocateBuffers() {
        // 缓冲区大小基于当前数据大小
        inputFloatBuffer = nativeHeap.allocArray<FloatVar>(currentBufferSize)
        outputFloatBuffer = nativeHeap.allocArray<FloatVar>(currentBufferSize)
        inputArrayPointer = nativeHeap.allocArray<CPointerVar<FloatVar>>(this.apmChannels.coerceAtLeast(1))
        outputArrayPointer = nativeHeap.allocArray<CPointerVar<FloatVar>>(this.apmChannels.coerceAtLeast(1))

        // 当前APM固定为单声道，所以只设置第一个指针
        if (this.apmChannels == 1) {
            inputArrayPointer!![0] = inputFloatBuffer
            outputArrayPointer!![0] = outputFloatBuffer
        } else {
            // 如果APM配置为多通道，则需要为每个通道设置缓冲区指针
            logger.warn("当前APM配置为 ${this.apmChannels} 通道，但缓冲区分配仅完整支持单通道演示。")
            inputArrayPointer!![0] = inputFloatBuffer
            outputArrayPointer!![0] = outputFloatBuffer
        }
    }

    private fun releaseBuffers() {
        inputFloatBuffer?.let { nativeHeap.free(it.rawValue); inputFloatBuffer = null }
        outputFloatBuffer?.let { nativeHeap.free(it.rawValue); outputFloatBuffer = null }
        inputArrayPointer?.let { nativeHeap.free(it.rawValue); inputArrayPointer = null }
        outputArrayPointer?.let { nativeHeap.free(it.rawValue); outputArrayPointer = null }
    }

    private fun initializeResampler() {
        // 重采样器仅在实际输入采样率与APM目标采样率不同时才需要
        if (actualInputSampleRate == apmSampleRate) {
            releaseResampler() // 如果不需要，确保释放已有的
            resamplerInitialized = false
            logger.debug("输入采样率 ($actualInputSampleRate Hz) 与 APM目标采样率 ($apmSampleRate Hz) 相同，无需重采样器。")
            return
        }

        if (resamplerInitialized && currentInputRate == actualInputSampleRate) {
            return // 已正确初始化且参数未变，直接返回，不打印重复日志
        }

        // 释放旧的重采样器
        releaseResampler()

        try {
            soxrWrapper = soxr_wrapper_create() ?: throw Exception("无法创建SOXR包装器")

            // 配置SOXR: 输入16位PCM，输出32位浮点
            soxr_io_spec_create(0u, 1u, soxrWrapper) // SOXR_INT16_I, SOXR_FLOAT32_I
            soxr_runtime_spec_create(1u, soxrWrapper) // 单线程
            soxr_quality_spec_create(2u, soxrWrapper) // 高质量

            // 创建重采样器
            val result = soxr_wrapper_create_resampler(
                soxrWrapper,
                actualInputSampleRate.toDouble(),
                apmSampleRate.toDouble() // 目标是APM的采样率
            )

            if (result != 0) {
                throw Exception("创建重采样器失败，错误码: $result")
            }

            currentInputRate = actualInputSampleRate
            resamplerInitialized = true
            logger.info("输入SOXR重采样器初始化成功: ${actualInputSampleRate}Hz -> ${apmSampleRate}Hz (INT16->FLOAT32)")
        } catch (e: Exception) {
            logger.error("初始化输入SOXR重采样器失败: ${e.message}")
            releaseResampler()
            throw e
        }
    }

    private fun releaseResampler() {
        soxrWrapper?.let {
            soxr_wrapper_destroy(it)
            soxrWrapper = null
        }
        resamplerInitialized = false
    }

    /**
     * 带输出重采样的音频处理流程 - 修复SOXR崩溃和饱和问题
     */
    fun processFrameWithOutputResampling(
        audioData: ShortArray, 
        inputSampleRate: Int = this.actualInputSampleRate,
        inputChannels: Int = this.inputChannels,
        targetOutputSampleRate: Int, 
        targetOutputChannels: Int = AudioDefaults.OUTPUT_DEVICE_CHANNELS
    ): ShortArray {
        if (apmHandle == null) {
            logger.error("WebRTC APM 未初始化")
            return audioData
        }

        if (audioData.isEmpty()) {
            return audioData
        }

        try {
            // 记录完整的处理链路参数
            if (vadLogCounter++ % 1000 == 0) {
                val inputMaxAmp = audioData.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
                logger.debug("音频处理链路: 输入=${inputSampleRate}Hz/${inputChannels}ch(振幅=$inputMaxAmp) -> APM=${apmSampleRate}Hz/${apmChannels}ch -> 输出=${targetOutputSampleRate}Hz/${targetOutputChannels}ch")
            }
            
            // 第1步：声道转换到APM格式
            val channelConvertedData = if (inputChannels != apmChannels) {
                if (inputChannels == 2 && apmChannels == 1) {
                    // 立体声转单声道：取平均值，避免音量损失
                    ShortArray(audioData.size / 2) { i ->
                        val left = audioData[i * 2].toInt()
                        val right = audioData[i * 2 + 1].toInt()
                        ((left + right) / 2).coerceIn(-32767, 32767).toShort()
                    }
                } else if (inputChannels == 1 && apmChannels == 2) {
                    ShortArray(audioData.size * 2) { i -> audioData[i / 2] }
                } else {
                    logger.warn("不支持的声道转换: ${inputChannels}ch -> ${apmChannels}ch")
                    audioData
                }
            } else {
                audioData
            }

            // 第2步：输入重采样到APM格式  
            val resampledData = if (inputSampleRate != apmSampleRate) {
                if (!resamplerInitialized || soxrWrapper == null) {
                    logger.error("SOXR重采样器未初始化")
                    return ShortArray(0)
                }
                
                // 确保输入数据大小合理，避免SOXR崩溃
                if (channelConvertedData.size > 32000) { // 限制最大2秒@16kHz
                    logger.warn("输入数据过大，截取前32000样本: ${channelConvertedData.size}")
                    channelConvertedData.copyOfRange(0, 32000)
                } else {
                    channelConvertedData
                }
                
                val safeInputData = if (channelConvertedData.size > 32000) {
                    channelConvertedData.copyOfRange(0, 32000)
                } else {
                    channelConvertedData
                }
                
                val expectedOutputSize = (safeInputData.size * apmSampleRate.toDouble() / inputSampleRate.toDouble()).toInt()
                val outputBufferSize = (expectedOutputSize * 15 / 10).coerceAtLeast(expectedOutputSize + 1000)
                
                val resampledBuffer = nativeHeap.allocArray<FloatVar>(outputBufferSize)
                
                try {
                    // 验证SOXR输入参数
                    if (safeInputData.size == 0) {
                        logger.warn("SOXR输入数据为空")
                        return ShortArray(0)
                    }
                    
                    val outputFrames = soxr_wrapper_process(
                        wrapper = soxrWrapper,
                        in_data = safeInputData.refTo(0),
                        in_size = safeInputData.size.toUInt(),
                        out_data = resampledBuffer,
                        out_size = outputBufferSize.toUInt()
                    )

                    if (outputFrames == 0U && safeInputData.isNotEmpty()) {
                        logger.error("SOXR重采样失败，输出帧数为0")
                        return ShortArray(0)
                    }
                    
                    // 检查SOXR输出数据的有效性，防止崩溃
                    var hasValidOutput = true
                    for (i in 0 until minOf(outputFrames.toInt(), 100)) {
                        val sample = resampledBuffer[i]
                        if (sample.isNaN() || sample.isInfinite() || kotlin.math.abs(sample) > 2.0f) {
                            hasValidOutput = false
                            logger.error("SOXR输出无效数据: index=$i, value=$sample")
                            break
                        }
                    }
                    
                    if (!hasValidOutput) {
                        logger.error("SOXR输出包含无效数据，跳过本次处理")
                        return ShortArray(0)
                    }
                    
                    ShortArray(outputFrames.toInt()) { i ->
                        val floatValue = resampledBuffer[i].coerceIn(-1f, 1f)
                        (floatValue * 32767f).toInt().toShort()
                    }

                } finally {
                    nativeHeap.free(resampledBuffer.rawValue)
                }
            } else {
                channelConvertedData
            }

            // 第3步：APM处理
            val processedData = try {
                val dataSize = resampledData.size
                if (dataSize != currentBufferSize) {
                    releaseBuffers()
                    currentBufferSize = dataSize
                    allocateBuffers()
                }
                
                if (inputFloatBuffer == null || outputFloatBuffer == null) {
                    logger.error("APM缓冲区分配失败")
                    resampledData
                } else {
                    // 填充输入缓冲区，确保数据范围正确
                    for (i in 0 until dataSize) {
                        val normalizedValue = (resampledData[i].toFloat() / 32768f).coerceIn(-1f, 1f)
                        inputFloatBuffer!![i] = normalizedValue
                    }
            
                    // WebRTC APM处理
                    webrtc_apm_process_stream(apmHandle, inputArrayPointer, outputArrayPointer)

                    // 提取处理结果
                    val apmResult = ShortArray(dataSize) { i ->
                        val floatSample = outputFloatBuffer!![i].coerceIn(-1f, 1f)
                        (floatSample * 32767f).toInt().toShort()
                    }

                    val maxAmp = apmResult.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
                    if (maxAmp == 0) {
                        logger.warn("APM处理后音频全为0，使用原始数据")
                        resampledData
                    } else {
                        apmResult
                    }
                }
            } catch (e: Exception) {
                logger.error("APM处理失败: ${e.message}")
                resampledData
            }

            // 第4步：输出重采样 - 修复饱和和崩溃问题
            val outputResampledData = if (targetOutputSampleRate != apmSampleRate) {
                try {
                    initializeOutputResampler(targetOutputSampleRate)
                    
                    if (!outputResamplerInitialized || outputSoxrWrapper == null) {
                        logger.error("输出重采样器初始化失败")
                        processedData
                    } else {
                        // 检查输入数据质量
                        val inputMaxAmp = processedData.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
                        if (inputMaxAmp == 0) {
                            logger.warn("输入数据全为0，跳过输出重采样")
                            processedData
                        } else {
                            val expectedOutputSize = (processedData.size * targetOutputSampleRate.toDouble() / apmSampleRate.toDouble()).toInt()
                            val outputBufferSize = (expectedOutputSize * 15 / 10).coerceAtLeast(expectedOutputSize + 1000)
                            
                            val inputFloatBuffer = nativeHeap.allocArray<FloatVar>(processedData.size)
                            val outputFloatBuffer = nativeHeap.allocArray<FloatVar>(outputBufferSize)

                            try {
                                // 安全的Float转换，避免饱和
                                for (i in processedData.indices) {
                                    val normalizedValue = (processedData[i].toFloat() / 32768f).coerceIn(-1f, 1f)
                                    inputFloatBuffer[i] = normalizedValue
                                }
                                
                                // 验证输出重采样器参数
                                if (processedData.size == 0) {
                                    logger.warn("输出重采样输入数据为空")
                                    processedData
                                } else {
                                    val outputFrames = soxr_wrapper_process_float_to_float(
                                        wrapper = outputSoxrWrapper,
                                        in_data = inputFloatBuffer,
                                        in_size = processedData.size.toUInt(),
                                        out_data = outputFloatBuffer,
                                        out_size = outputBufferSize.toUInt()
                                    )

                                    if (outputFrames == 0U) {
                                        logger.warn("输出重采样失败，输出帧数为0")
                                        processedData
                                    } else {
                                        // 检查输出数据有效性
                                        var maxOutputAmp = 0f
                                        var hasValidData = true
                                        
                                        for (i in 0 until minOf(outputFrames.toInt(), 100)) {
                                            val sample = outputFloatBuffer[i]
                                            if (sample.isNaN() || sample.isInfinite()) {
                                                hasValidData = false
                                                logger.error("输出重采样数据异常: index=$i, value=$sample")
                                                break
                                            }
                                            val absValue = kotlin.math.abs(sample)
                                            if (absValue > maxOutputAmp) maxOutputAmp = absValue
                                        }
                                        
                                        if (!hasValidData) {
                                            logger.error("输出重采样数据包含无效值")
                                            processedData
                                        } else {
                                            // 防止饱和的转换，限制最大振幅
                                            val maxAllowedAmp = 0.8f // 限制在80%以防削波
                                            val scaleFactor = if (maxOutputAmp > maxAllowedAmp) {
                                                maxAllowedAmp / maxOutputAmp
                                            } else {
                                                1.0f
                                            }
                                            
                                            if (scaleFactor < 1.0f) {
                                                logger.debug("输出重采样防饱和: 原始峰值=$maxOutputAmp, 缩放比例=$scaleFactor")
                                            }
                                            
                                            ShortArray(outputFrames.toInt()) { i ->
                                                val scaledSample = outputFloatBuffer[i] * scaleFactor
                                                val clampedSample = scaledSample.coerceIn(-1f, 1f)
                                                (clampedSample * 32767f).toInt().toShort()
                                            }
                                        }
                                    }
                                }
                            } finally {
                                nativeHeap.free(inputFloatBuffer.rawValue)
                                nativeHeap.free(outputFloatBuffer.rawValue)
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.error("输出重采样异常: ${e.message}")
                    processedData
                }
            } else {
                processedData
            }

            // 第5步：输出声道转换
            val finalResult = if (apmChannels != targetOutputChannels) {
                if (apmChannels == 1 && targetOutputChannels == 2) {
                    // 单声道转立体声：直接复制，保持音质
                    ShortArray(outputResampledData.size * 2) { i ->
                        outputResampledData[i / 2]
                    }
                } else if (apmChannels == 2 && targetOutputChannels == 1) {
                    // 立体声转单声道：取平均值
                    ShortArray(outputResampledData.size / 2) { i ->
                        val left = outputResampledData[i * 2].toInt()
                        val right = outputResampledData[i * 2 + 1].toInt()
                        ((left + right) / 2).coerceIn(-32767, 32767).toShort()
                    }
                } else {
                    logger.warn("不支持的输出声道转换: ${apmChannels}ch -> ${targetOutputChannels}ch")
                    outputResampledData
                }
            } else {
                outputResampledData
            }

            // 验证最终输出质量
            val finalMaxAmp = finalResult.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
            if (vadLogCounter % 1000 == 0) {
                logger.debug("处理完成: 最终振幅=$finalMaxAmp, 数据大小=${finalResult.size}")
            }

            return finalResult

        } catch (e: Exception) {
            logger.error("音频处理失败: ${e.message}")
            return audioData
        }
    }

    /**
     * 标准能量计算
     */
    fun calculateEnergy(audioData: ShortArray): Double {
        if (audioData.isEmpty()) return 0.0

        var sum = 0.0
        var maxAbs = 0

        for (sample in audioData) {
            val sampleAbs = abs(sample.toInt())
            if (sampleAbs > maxAbs) maxAbs = sampleAbs
            sum += (sample * sample).toDouble()
        }

        val rms = kotlin.math.sqrt(sum / audioData.size) / Short.MAX_VALUE

        // 简化日志 - 只在有问题时记录
        if (maxAbs >= 32767 && vadLogCounter++ % 50 == 0) {
            logger.warn("检测到音频饱和: maxAbs=$maxAbs, rms=$rms")
        } else if (vadLogCounter++ % 500 == 0) {
            logger.debug("Energy: maxAbs=$maxAbs, rms=$rms")
        }

        return rms
    }

    /**
     * 标准VAD检测 - 修复频繁跳跃问题
     */
    fun isVoiceDetected(): Boolean {
        if (apmHandle == null) return false

        // 获取APM内部的VAD结果
        val apmVadResult = my_webrtc_apm_voice_detected(apmHandle) == 1
        
        var finalVadResult = apmVadResult
        
        // 增强键盘检测逻辑 - 检查APM是否正确设置了键盘状态
        try {
            // 获取当前帧的能量和特征
            var energy = 0.0f
            var hasSharpTransients = false
            
            if (inputFloatBuffer != null && currentBufferSize > 0) {
                var sum = 0.0f
                var maxChange = 0.0f
                var lastSample = 0.0f
                
                for (i in 0 until currentBufferSize) {
                    val sample = inputFloatBuffer!![i]
                    sum += sample * sample
                    
                    // 检测尖锐变化（键盘特征）
                    if (i > 0) {
                        val change = kotlin.math.abs(sample - lastSample)
                        if (change > maxChange) maxChange = change
                    }
                    lastSample = sample
                }
                
                energy = kotlin.math.sqrt(sum / currentBufferSize)
                hasSharpTransients = maxChange > 0.5f // 检测尖锐的振幅变化
                
                // 如果检测到键盘特征，通知APM
                if (hasSharpTransients && energy > 0.01f) {
                    my_webrtc_apm_set_key_pressed(apmHandle, 1)
                    if (vadLogCounter % 100 == 0) {
                        logger.debug("检测到键盘特征: 能量=$energy, 最大变化=$maxChange")
                    }
                } else {
                    my_webrtc_apm_set_key_pressed(apmHandle, 0)
                }
                
                // 大幅降低能量阈值，但对键盘声音进行特殊处理
                val minVoiceEnergy = if (hasSharpTransients) {
                    0.005f // 键盘声时提高阈值
                } else {
                    0.0005f // 正常语音保持低阈值
                }
                
                if (energy < minVoiceEnergy) {
                    finalVadResult = false
                    if (vadLogCounter % 500 == 0) {
                        logger.debug("能量过低: energy=$energy (阈值=$minVoiceEnergy, 键盘=$hasSharpTransients)")
                    }
                }
            }
        } catch (e: Exception) {
            // 忽略键盘检测错误
        }

        // 修复VAD去抖动 - 增加稳定性
        if (finalVadResult) {
            consecutiveVadPositive++
            val result = consecutiveVadPositive >= vadDebounceFrames
            
            if (result != lastVadResult) {
                logger.debug("VAD状态变化: $lastVadResult -> $result (连续帧: $consecutiveVadPositive)")
                lastVadResult = result
            }
            
            return result
        } else {
            // 语音结束，但增加延迟重置，避免频繁跳跃
            if (consecutiveVadPositive > 0) {
                consecutiveVadPositive = kotlin.math.max(0, consecutiveVadPositive - 2) // 逐渐减少而不是立即重置
                
                if (consecutiveVadPositive == 0 && lastVadResult) {
                    logger.debug("VAD状态变化: true -> false")
                    lastVadResult = false
                }
            }
            return consecutiveVadPositive >= vadDebounceFrames
        }
    }

    // 标准配置方法
    fun setVadThreshold(threshold: Float) {
        // WebRTC APM没有直接设置VAD阈值的接口，所以此方法仅记录日志
        logger.info("VAD阈值设置请求: $threshold (注意: WebRTC APM内部VAD阈值不可直接设置)")
    }

    fun setVadDebounceFrames(frames: Int) {
        this.vadDebounceFrames = frames.coerceAtLeast(1)
        logger.info("VAD去抖动帧数设置为: $vadDebounceFrames")
    }

    fun enableEchoCancellation(enable: Boolean) {
        apmHandle?.let {
            my_webrtc_apm_enable_aec(it, if (enable) 1 else 0)
            logger.info("回声消除${if (enable) "启用" else "禁用"}")
        }
    }

    /**
     * 设置键盘按键状态，用于抑制键盘声
     * @param keyPressed 是否有键盘按下
     */
    fun setKeyPressed(keyPressed: Boolean) {
        apmHandle?.let {
            my_webrtc_apm_set_key_pressed(it, if (keyPressed) 1 else 0)
            if (vadLogCounter % 100 == 0) {
                logger.debug("键盘状态设置: ${if (keyPressed) "按下" else "释放"}")
            }
        }
    }

    fun getActualInputSampleRate(): Int = actualInputSampleRate
    fun getInputChannels(): Int = inputChannels
    fun getApmSampleRate(): Int = apmSampleRate
    fun getApmChannels(): Int = apmChannels

    /**
     * 获取APM实例句柄 - 新增
     * @return APM实例句柄，可能为null
     */
    fun getApmHandle(): CPointer<*>? = apmHandle
    
    /**
     * 处理音频并重采样到指定输出采样率的便捷方法
     * @param audioData 输入音频数据
     * @param outputSampleRate 输出采样率，默认为48kHz
     * @param outputChannels 输出声道数，默认为双声道
     * @return 处理并重采样后的音频数据
     */
    fun processAndResample(
        audioData: ShortArray, 
        outputSampleRate: Int = AudioDefaults.OUTPUT_DEVICE_SAMPLE_RATE,
        outputChannels: Int = AudioDefaults.OUTPUT_DEVICE_CHANNELS
    ): ShortArray {
        return processFrameWithOutputResampling(
            audioData = audioData,
            inputSampleRate = this.actualInputSampleRate,
            inputChannels = this.inputChannels,
            targetOutputSampleRate = outputSampleRate,
            targetOutputChannels = outputChannels
        )
    }

    fun updateInputParameters(sampleRate: Int, channels: Int) {
        if (sampleRate != actualInputSampleRate || channels != inputChannels) {
            logger.info("更新输入参数: 旧($actualInputSampleRate Hz/$inputChannels ch) -> 新($sampleRate Hz/$channels ch)")
            actualInputSampleRate = sampleRate
            inputChannels = channels
            // 当输入参数变化时，重采样器可能需要重新初始化
            resamplerInitialized = false // 标记为未初始化，以便initializeResampler正确执行
            logger.info("输入参数已更新，SOXR重采样器将在下次需要时根据新输入参数 ($actualInputSampleRate Hz) 重新初始化。")
            initializeResampler() // 主动重新评估并初始化重采样器
        }
    }

    fun release() {
        try {
            apmHandle?.let {
                webrtc_apm_destroy(it)
                apmHandle = null
            }

            releaseResampler()
            releaseOutputResampler()
            releaseBuffers()

            logger.info("WebRTC APM 资源已释放")
        } catch (e: Exception) {
            logger.error("释放资源失败: ${e.message}")
        }
    }

    /**
     * 初始化输出重采样器 - 修复SOXR配置
     */
    private fun initializeOutputResampler(targetOutputSampleRate: Int) {
        if (targetOutputSampleRate == apmSampleRate) {
            releaseOutputResampler()
            outputResamplerInitialized = false
            logger.debug("输出采样率相同，无需输出重采样器")
            return
        }

        if (outputResamplerInitialized && outputSampleRate == targetOutputSampleRate) {
            return
        }

        releaseOutputResampler()

        try {
            outputSoxrWrapper = soxr_wrapper_create() ?: throw Exception("无法创建输出SOXR包装器")

            // 修复：使用Float->Float配置，避免类型转换问题
            soxr_io_spec_create(1u, 1u, outputSoxrWrapper) // FLOAT32->FLOAT32
            soxr_runtime_spec_create(1u, outputSoxrWrapper)
            soxr_quality_spec_create(2u, outputSoxrWrapper)

            val result = soxr_wrapper_create_resampler(
                outputSoxrWrapper,
                apmSampleRate.toDouble(),
                targetOutputSampleRate.toDouble()
            )

            if (result != 0) {
                throw Exception("创建输出重采样器失败，错误码: $result")
            }

            outputSampleRate = targetOutputSampleRate
            outputResamplerInitialized = true
            logger.info("输出SOXR重采样器初始化成功: ${apmSampleRate}Hz -> ${targetOutputSampleRate}Hz (FLOAT32->FLOAT32)")
        } catch (e: Exception) {
            logger.error("初始化输出SOXR重采样器失败: ${e.message}")
            releaseOutputResampler()
            throw e
        }
    }

    /**
     * 释放输出重采样器
     */
    private fun releaseOutputResampler() {
        outputSoxrWrapper?.let {
            soxr_wrapper_destroy(it)
            outputSoxrWrapper = null
        }
        outputResamplerInitialized = false
    }

    /**
     * 标准音频处理流程：输入->APM处理->输出APM格式
     * 这个方法只做APM内部处理，不做最终输出转换
     */
    fun processFrame(audioData: ShortArray): ShortArray {
        if (apmHandle == null) {
            logger.error("WebRTC APM 未初始化")
            return audioData
        }

        if (audioData.isEmpty()) {
            return audioData
        }

        // 简单的音量检查，保持较低阈值
        var maxAmplitude = 0
        for (i in 0 until minOf(audioData.size, 100)) {
            val amplitude = kotlin.math.abs(audioData[i].toInt())
            if (amplitude > maxAmplitude) maxAmplitude = amplitude
        }
        
        if (maxAmplitude < 1) {
            logger.debug("跳过极低能量音频: 最大振幅=$maxAmplitude")
            return audioData
        }

        try {
            // 记录输入音频信息
            val inputMaxAmp = audioData.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
            if (vadLogCounter++ % 1000 == 0) {
                logger.debug("processFrame输入: 最大振幅=$inputMaxAmp, 输入参数=${actualInputSampleRate}Hz/${inputChannels}ch, APM目标=${apmSampleRate}Hz/${apmChannels}ch")
            }
            
            // 第1步：声道转换到APM格式 - 只在需要时转换
            val channelConvertedData = if (inputChannels != apmChannels) {
                if (inputChannels == 2 && apmChannels == 1) {
                    if (vadLogCounter % 1000 == 0) {
                        logger.debug("声道转换: ${inputChannels}ch -> ${apmChannels}ch (立体声转单声道)")
                    }
                    AudioUtils.stereoToMono(audioData)
                } else if (inputChannels == 1 && apmChannels == 2) {
                    if (vadLogCounter % 1000 == 0) {
                        logger.debug("声道转换: ${inputChannels}ch -> ${apmChannels}ch (单声道转立体声)")
                    }
                    ShortArray(audioData.size * 2) { i -> audioData[i / 2] }
                } else {
                    logger.warn("不支持的声道转换: ${inputChannels}ch -> ${apmChannels}ch，直接使用原数据")
                    audioData
                }
            } else {
                if (vadLogCounter % 2000 == 0) {
                    logger.debug("声道数相同(${inputChannels}ch)，跳过声道转换")
                }
                audioData
            }
            
            val channelConvertedMaxAmp = channelConvertedData.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
            if (vadLogCounter % 1000 == 0) {
                logger.debug("声道转换后: 最大振幅=$channelConvertedMaxAmp")
            }

            // 第2步：重采样到APM格式 - 只在需要时重采样
            val resampledData = if (actualInputSampleRate != apmSampleRate) {
                if (vadLogCounter % 1000 == 0) {
                    logger.debug("输入重采样: ${actualInputSampleRate}Hz -> ${apmSampleRate}Hz")
                }
                
                if (!resamplerInitialized || soxrWrapper == null) {
                    logger.error("SOXR重采样器未初始化")
                    return ShortArray(0)
                }
                
                val expectedOutputSize = (channelConvertedData.size * apmSampleRate.toDouble() / actualInputSampleRate.toDouble()).toInt()
                val outputBufferSize = (expectedOutputSize * 12 / 10).coerceAtLeast(channelConvertedData.size)
                val resampledBuffer = nativeHeap.allocArray<FloatVar>(outputBufferSize)

                try {
                    val outputFrames = soxr_wrapper_process(
                        wrapper = soxrWrapper,
                        in_data = channelConvertedData.refTo(0),
                        in_size = channelConvertedData.size.toUInt(),
                        out_data = resampledBuffer,
                        out_size = outputBufferSize.toUInt()
                    )

                    if (outputFrames == 0U && channelConvertedData.isNotEmpty()) {
                        return ShortArray(0)
                    }
                    
                    ShortArray(outputFrames.toInt()) { i ->
                        (resampledBuffer[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    }
                } finally {
                    nativeHeap.free(resampledBuffer.rawValue)
                }
            } else {
                if (vadLogCounter % 2000 == 0) {
                    logger.debug("采样率相同(${actualInputSampleRate}Hz)，跳过输入重采样")
                }
                channelConvertedData
            }
            
            val resampledMaxAmp = resampledData.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
            if (vadLogCounter % 1000 == 0) {
                logger.debug("输入重采样后: 最大振幅=$resampledMaxAmp")
            }
            
            // 第3步：APM处理
            val dataSize = resampledData.size
            if (dataSize != currentBufferSize) {
                releaseBuffers()
                currentBufferSize = dataSize
                allocateBuffers()
            }
            
            // 填充输入缓冲区
            for (i in 0 until dataSize) {
                inputFloatBuffer!![i] = (resampledData[i] / 32768f).coerceIn(-1f, 1f)
            }

            // WebRTC APM处理
            webrtc_apm_process_stream(apmHandle, inputArrayPointer, outputArrayPointer)

            // 提取处理结果 - 输出APM格式（16kHz/1ch）
            val processedData = ShortArray(dataSize) { i ->
                val floatSample = outputFloatBuffer!![i].coerceIn(-1f, 1f)
                (floatSample * 32767f).toInt().toShort()
            }

            // 检查处理后的音频质量
            val maxAmp = processedData.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
            
            if (maxAmp == 0) {
                logger.warn("APM处理后音频全为0，使用原始数据")
                return resampledData
            }
            
            if (maxAmp > 0) {
                if (vadLogCounter % 1000 == 0) {
                    logger.debug("APM处理后音频质量: 最大振幅=$maxAmp")
                }
            }

            return processedData

        } catch (e: Exception) {
            logger.error("APM处理失败: ${e.message}")
            return audioData
        }
    }
}