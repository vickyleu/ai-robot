// 简化的 WebRtcApm.kt - 只使用标准WebRTC APM + SOXR
@file:OptIn(ExperimentalForeignApi::class)

package voice.audio.processing

import com.airobot.webrtcapminterop.*
import kotlinx.cinterop.*
import voice.util.LogManager
import kotlin.math.abs

class WebRtcApm {
    private val logger = LogManager.getLogger("WebRtcApm")
    private var apmHandle: CPointer<*>? = null

    // APM内部处理参数
    private var apmSampleRate: Int = 0
    private var apmChannels: Int = 0
    private var apmFrameSize: Int = 0 // 10ms frame size for APM

    // 音频缓冲区
    private var inputFloatBuffer: CPointer<FloatVar>? = null
    private var outputFloatBuffer: CPointer<FloatVar>? = null
    private var inputArrayPointer: CPointer<CPointerVar<FloatVar>>? = null
    private var outputArrayPointer: CPointer<CPointerVar<FloatVar>>? = null

    // SOXR重采样器
    private var soxrWrapper: CPointer<SoxWrapper>? = null
    private var resamplerInitialized = false
    private var currentInputRate: Int = 0

    // 输入参数
    private var actualInputSampleRate: Int = 0
    private var inputChannels: Int = 0

    // VAD参数
    private var vadThreshold: Float = 0.12f
    private var vadLogCounter: Int = 0
    private var consecutiveVadPositive: Int = 0
    private var lastVadResult: Boolean = false
    private var vadDebounceFrames: Int = 2

    fun initialize(sampleRate: Int, channels: Int): Boolean {
        if (apmHandle != null) {
            logger.warn("WebRTC APM 已经初始化，使用先前配置: ${this.apmSampleRate}Hz, ${this.apmChannels}ch")
            // 如果外部尝试用不同参数重复初始化，可以选择释放旧的或返回错误/false
            if (this.actualInputSampleRate != sampleRate || this.inputChannels != channels) {
                logger.warn("警告: 尝试使用不同的参数重新初始化已有的APM实例。旧参数: ${this.actualInputSampleRate}/${this.inputChannels}, 新参数: $sampleRate/$channels. 建议先释放再初始化。")
                // 或者可以先release()再继续
            }
            return true
        }

        // 保存实际输入参数（来自设备的原始采样率与通道数）
        this.actualInputSampleRate = sampleRate
        this.inputChannels = channels

        // WebRTC APM 配置参数 (例如，如果APM固定在16kHz处理，这里就需要决策)
        // 现在的逻辑是APM可以处理输入采样率，但内部转换和处理帧大小需要注意
        // 假设APM可以原生处理传入的sampleRate，或者我们总是重采样到特定速率
        // 决定APM实际工作的采样率和通道数
        // **重要决策点**：WebRTC APM通常推荐/固定工作在8, 16, 32, 48kHz。
        // 如果传入的sampleRate是APM支持的，就直接用。否则，需先重采样。
        // 此处我们假设APM将以传入的sampleRate运行，或者SOXR会处理到APM支持的速率。
        // 为了简化并遵循之前日志看到的 "WebRTC APM 统一以 16 kHz / 单声道 进行内部处理"
        // 我们将目标APM处理采样率固定为16kHz，通道固定为单声道。
        // 这意味着如果输入不是16kHz/1ch，则必须进行重采样和声道转换。

        this.apmSampleRate = 16000 // APM 内部固定处理16kHz
        this.apmChannels = 1     // APM 内部固定处理单声道
        this.apmFrameSize = this.apmSampleRate / 100  // 固定为 10 ms 帧 (160 样本 @ 16kHz)

        logger.info("APM配置: 目标处理采样率=${this.apmSampleRate}Hz, 目标处理通道数=${this.apmChannels}, 帧大小=${this.apmFrameSize}样本")

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

                // 启用所有主要功能
                config.noise_suppression.enabled = true
                config.noise_suppression.level = kNsHigh

                config.high_pass_filter.enabled = true

                config.gain_controller.enabled = true
                config.gain_controller.mode = kAgcAdaptiveDigital
                config.gain_controller.target_level_dbfs = 3
                config.gain_controller.compression_gain_db = 9
                config.gain_controller.enable_limiter = true

                config.pre_amplifier.enabled = false
                config.pre_amplifier.fixed_gain_factor = 1.0f

                config.voice_detection.enabled = true

                config.echo_canceller.enabled = true
                config.echo_canceller.mobile_mode = false
                config.echo_canceller.enforce_high_pass_filtering = true

                config.transient_suppression.enabled = true
                config.residual_echo_detector.enabled = true

                webrtc_apm_apply_config(apmHandle, config.ptr)
            }

            // 准备APM处理 - 使用APM配置的采样率和通道数
            webrtc_apm_prepare(apmHandle, this.apmSampleRate, this.apmChannels)

            // 分配缓冲区 - 根据APM的帧大小
            allocateBuffers()

            // 初始化重采样器 (根据当前实际输入参数和APM目标参数)
            initializeResampler()

            logger.info("WebRTC APM 初始化成功: 实际输入=$actualInputSampleRate Hz/$inputChannels ch, APM配置=${this.apmSampleRate} Hz/${this.apmChannels} ch")
            return true
        } catch (e: Exception) {
            logger.error("WebRTC APM 初始化失败: ${e.message}")
            release()
            return false
        }
    }

    private fun allocateBuffers() {
        // 缓冲区大小基于APM的处理帧大小 (apmFrameSize)
        inputFloatBuffer = nativeHeap.allocArray<FloatVar>(apmFrameSize)
        outputFloatBuffer = nativeHeap.allocArray<FloatVar>(apmFrameSize)
        inputArrayPointer = nativeHeap.allocArray<CPointerVar<FloatVar>>(this.apmChannels.coerceAtLeast(1)) // 支持多通道输入到APM（如果APM配置如此）
        outputArrayPointer = nativeHeap.allocArray<CPointerVar<FloatVar>>(this.apmChannels.coerceAtLeast(1)) // 支持多通道输出从APM

        // 当前APM固定为单声道，所以只设置第一个指针
        if (this.apmChannels == 1) {
            inputArrayPointer!![0] = inputFloatBuffer
            outputArrayPointer!![0] = outputFloatBuffer
        } else {
            // 如果APM配置为多通道，则需要为每个通道设置缓冲区指针
            // 这里简化为单通道逻辑，实际多通道APM需要更复杂处理
            logger.warn("当前APM配置为 ${this.apmChannels} 通道，但缓冲区分配仅完整支持单通道演示。")
            inputArrayPointer!![0] = inputFloatBuffer // 至少保证第一个通道
            outputArrayPointer!![0] = outputFloatBuffer
        }
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
            logger.info("SOXR重采样器初始化成功: ${actualInputSampleRate}Hz -> ${apmSampleRate}Hz")
        } catch (e: Exception) {
            logger.error("初始化SOXR重采样器失败: ${e.message}")
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
     * 标准音频处理流程：立体声->单声道->重采样->WebRTC APM
     */
    fun processFrame(audioData: ShortArray): ShortArray {
        if (apmHandle == null) {
            logger.error("WebRTC APM 未初始化")
            return audioData // 返回原始数据或空数组
        }

        if (audioData.isEmpty()) {
            return audioData
        }

        try {
            // 第1步：声道转换 (例如，如果输入是立体声，APM配置为单声道)
            val channelConvertedData = if (inputChannels > 1 && apmChannels == 1) {
                convertStereoToMono(audioData)
            } else if (inputChannels == 1 && apmChannels > 1) {
                // 如果APM需要多声道而输入是单声道，可能需要复制或特殊处理
                logger.warn("输入是单声道但APM配置为 ${apmChannels}声道，暂未实现此转换，将使用单声道数据。")
                audioData // 或进行转换
            } else if (inputChannels != apmChannels) {
                logger.warn("输入通道数($inputChannels)与APM配置通道数($apmChannels)不匹配且无标准转换，将尝试直接使用。")
                audioData
            }
            else { // 通道数匹配
                audioData
            }

            // 第2步：重采样 (如果实际输入采样率与APM目标采样率不同)
            // initializeResampler 会处理是否需要重采样
            // initializeResampler() // <--- 移除这里的无条件调用

            val dataToProcess: ShortArray
            val samplesToProcess: Int

            if (actualInputSampleRate != apmSampleRate) { // 条件：确实需要重采样
                if (!resamplerInitialized || soxrWrapper == null) { // 检查重采样器是否已准备好
                    logger.error("SOXR重采样器未初始化或无效，无法处理需要重采样的音频。输入: $actualInputSampleRate Hz, APM目标: $apmSampleRate Hz")
                    // 在这种意外情况下，可能需要返回原始数据或错误信号，而不是继续尝试处理
                    // 或者，如果这是一个可恢复的错误，可以在这里尝试重新初始化，但这通常指示上层逻辑问题
                    // 为安全起见，返回空数组表示处理失败
                    return ShortArray(0)
                }
                // 需要重采样 (重采样器已在上层逻辑中初始化)
                val expectedOutputSize = (channelConvertedData.size * apmSampleRate.toDouble() / actualInputSampleRate.toDouble()).toInt()
                val outputBufferSize = (expectedOutputSize * 12 / 10).coerceAtLeast(apmFrameSize * 2) // 至少能容纳几帧
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
                        // SOXR可能因内部缓冲在首帧返回0，或者数据不足以输出一个完整帧
                        // logger.debug("SOXR输出0帧，可能正在缓冲。输入大小: ${channelConvertedData.size}")
                        // 返回空数组或特定信号表示需要更多数据，而不是原始数据，避免后续处理错误尺寸数据
                        return ShortArray(0)
                    }
                    
                    // 将重采样后的 float 转换为 ShortArray (虽然APM内部用float，但后续步骤可能需要Short)
                    // 此处逻辑需要清晰：APM process_stream 输入是 float**
                    // 所以重采样后的 float可以直接送入APM的inputFloatBuffer
                    // 此处的 dataToProcess 和 samplesToProcess 应该是指向重采样后的浮点数据
                    // 为了简化，我们假设 process_stream 总是处理 apmFrameSize
                    // SOXR输出的可能是多帧，需要缓冲和分帧处理
                    // **这是个复杂点：SOXR输出的样本数不一定等于apmFrameSize**
                    // **正确的做法是，将SOXR的输出缓冲起来，然后按apmFrameSize喂给APM**
                    // 为了简化当前修改，我们假设SOXR的输出可以直接用，取apmFrameSize

                    if (outputFrames.toInt() == 0) return ShortArray(0) // 没有重采样输出

                    // 将 resampledBuffer (Float) 内容填入 inputFloatBuffer (Float)
                    val numSamplesFromSoxr = outputFrames.toInt()
                    samplesToProcess = minOf(numSamplesFromSoxr, apmFrameSize) // APM一次处理apmFrameSize

                    for (i in 0 until samplesToProcess) {
                        inputFloatBuffer!![i] = resampledBuffer[i].coerceIn(-1f, 1f)
                    }
                    // dataToProcess 不是 ShortArray, 而是已经准备好的 inputFloatBuffer
                    // 后续的 webrtc_apm_process_stream 会使用这个 inputFloatBuffer

                } finally {
                    nativeHeap.free(resampledBuffer.rawValue)
                }
            } else {
                // 无需重采样，或重采样器未初始化 (可能是因为采样率一致)
                // ShortArray -> FloatArray for APM
                samplesToProcess = minOf(channelConvertedData.size, apmFrameSize)
                for (i in 0 until samplesToProcess) {
                    inputFloatBuffer!![i] = (channelConvertedData[i] / 32768f).coerceIn(-1f, 1f)
                }
            }
            
            // 如果准备的样本数不足APM一帧，可以选择补零或等待更多数据
            if (samplesToProcess < apmFrameSize) {
                // logger.debug("样本数 ($samplesToProcess) 不足一帧 ($apmFrameSize)，将补零处理。")
                for (i in samplesToProcess until apmFrameSize) {
                    inputFloatBuffer!![i] = 0.0f
                }
            }


            // 第3步：调用WebRTC APM处理 (总是处理apmFrameSize个样本)
            // inputArrayPointer!![0] 已经指向 inputFloatBuffer
            // outputArrayPointer!![0] 已经指向 outputFloatBuffer
            webrtc_apm_process_stream(apmHandle, inputArrayPointer, outputArrayPointer)

            // 第4步：转换APM输出回ShortArray (outputFloatBuffer -> ShortArray)
            // APM输出也是apmFrameSize个样本
            return ShortArray(apmFrameSize) { i ->
                (outputFloatBuffer!![i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }

        } catch (e: Exception) {
            logger.error("音频处理失败: ${e.message}")
            // 在异常情况下，返回原始数据或空数组，避免崩溃
            return audioData // 或者 return ShortArray(0)
        }
    }

    /**
     * 标准立体声到单声道转换
     */
    private fun convertStereoToMono(stereoData: ShortArray): ShortArray {
        if (inputChannels <= 1) return stereoData

        val monoSize = stereoData.size / inputChannels
        val monoData = ShortArray(monoSize)

        for (i in 0 until monoSize) {
            var sum = 0
            for (c in 0 until inputChannels) {
                sum += stereoData[i * inputChannels + c]
            }
            monoData[i] = (sum / inputChannels).toShort()
        }

        return monoData
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
     * 标准VAD检测
     */
    fun isVoiceDetected(): Boolean {
        if (apmHandle == null) return false

        val vadResult = my_webrtc_apm_voice_detected(apmHandle) == 1

        // 去抖动逻辑
        if (vadResult) {
            consecutiveVadPositive++
        } else {
            consecutiveVadPositive = 0
        }

        val finalResult = consecutiveVadPositive >= vadDebounceFrames

        // 只在状态变化时记录
        if (finalResult != lastVadResult) {
            logger.debug("VAD状态变化: $lastVadResult -> $finalResult (连续帧: $consecutiveVadPositive)")
            lastVadResult = finalResult
        }

        return finalResult
    }

    // 标准配置方法
    fun setVadThreshold(threshold: Float) {
        this.vadThreshold = threshold.coerceIn(0.0f, 1.0f)
        logger.info("VAD阈值设置为: $vadThreshold")
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

    fun getActualInputSampleRate(): Int = actualInputSampleRate
    fun getInputChannels(): Int = inputChannels
    fun getApmSampleRate(): Int = apmSampleRate
    fun getApmChannels(): Int = apmChannels
    fun getApmFrameSize(): Int = apmFrameSize

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

            inputFloatBuffer?.let { nativeHeap.free(it.rawValue); inputFloatBuffer = null }
            outputFloatBuffer?.let { nativeHeap.free(it.rawValue); outputFloatBuffer = null }
            inputArrayPointer?.let { nativeHeap.free(it.rawValue); inputArrayPointer = null }
            outputArrayPointer?.let { nativeHeap.free(it.rawValue); outputArrayPointer = null }

            logger.info("WebRTC APM 资源已释放")
        } catch (e: Exception) {
            logger.error("释放资源失败: ${e.message}")
        }
    }
}