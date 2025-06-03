@file:OptIn(ExperimentalForeignApi::class)

package voice.audio.processing

import com.airobot.core.utils.format
import com.airobot.speexdspinterop.SPEEX_PREPROCESS_SET_AGC
import com.airobot.speexdspinterop.SPEEX_PREPROCESS_SET_AGC_LEVEL
import com.airobot.speexdspinterop.SPEEX_PREPROCESS_SET_DENOISE
import com.airobot.speexdspinterop.SPEEX_PREPROCESS_SET_NOISE_SUPPRESS
import com.airobot.speexdspinterop.SPEEX_PREPROCESS_SET_VAD
import com.airobot.speexdspinterop.SpeexPreprocessState_
import com.airobot.speexdspinterop.speex_preprocess_ctl
import com.airobot.speexdspinterop.speex_preprocess_run
import com.airobot.speexdspinterop.speex_preprocess_state_destroy
import com.airobot.speexdspinterop.speex_preprocess_state_init
import com.airobot.webrtcapminterop.RNNoiseWrapper
import com.airobot.webrtcapminterop.SOXR_INT16_I
import com.airobot.webrtcapminterop.SoxWrapper
import com.airobot.webrtcapminterop.rnnoise_wrapper_create
import com.airobot.webrtcapminterop.rnnoise_wrapper_destroy
import com.airobot.webrtcapminterop.rnnoise_wrapper_process
import com.airobot.webrtcapminterop.rnnoise_wrapper_set_gain
import com.airobot.webrtcapminterop.rnnoise_wrapper_set_vad_threshold
import com.airobot.webrtcapminterop.soxr_io_spec_create
import com.airobot.webrtcapminterop.soxr_quality_spec_create
import com.airobot.webrtcapminterop.soxr_runtime_spec_create
import com.airobot.webrtcapminterop.soxr_wrapper_create
import com.airobot.webrtcapminterop.soxr_wrapper_create_resampler
import com.airobot.webrtcapminterop.soxr_wrapper_destroy
import com.airobot.webrtcapminterop.soxr_wrapper_process_short_to_short
import kotlinx.cinterop.*
import voice.util.AudioDefaults
import voice.util.LogManager
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.sqrt



/**
 * 第三方音频处理器 - 统一管理RNNoise、SpeexDSP等模块
 * 替换WebRTC APM，避免其内部无法禁用的处理逻辑
 */
class ThirdPartyAudioProcessor {
    private val logger = LogManager.getLogger("ThirdPartyAudioProcessor")
    
    // 处理器状态
    private var isInitialized = false
    private var processingEnabled = true
    
    // 音频格式配置
    private var inputFormat = AudioDefaults.AudioFormat(
        AudioDefaults.INPUT_DEVICE_SAMPLE_RATE,
        AudioDefaults.INPUT_DEVICE_CHANNELS
    )
    private var outputFormat = AudioDefaults.AudioFormat(
        AudioDefaults.WEBRTC_APM_SAMPLE_RATE,
        AudioDefaults.WEBRTC_APM_CHANNELS
    )
    
    // RNNoise降噪器
    private var rnnoiseWrapper: CPointer<RNNoiseWrapper>? = null
    
    // SpeexDSP预处理器
    private var speexPreprocessor: CPointer<SpeexPreprocessState_>? = null
    
    // SoXR重采样器
    private var soxrWrapper: CPointer<SoxWrapper>? = null
    
    // 处理配置
    data class ProcessingConfig(
        // RNNoise配置
        val enableRNNoise: Boolean = AudioDefaults.ENABLE_RNNOISE,
        val rnnoiseVadThreshold: Float = AudioDefaults.RNNOISE_VAD_THRESHOLD,
        val rnnoiseGain: Float = AudioDefaults.RNNOISE_GAIN,
        
        // SpeexDSP配置
        val enableSpeexAGC: Boolean = AudioDefaults.ENABLE_SPEEX_AGC,
        val enableSpeexVAD: Boolean = AudioDefaults.ENABLE_SPEEX_VAD,
        val enableSpeexDenoise: Boolean = AudioDefaults.ENABLE_SPEEX_DENOISE,
        val speexAgcLevel: Float = AudioDefaults.SPEEX_AGC_LEVEL,
        val speexNoiseSuppress: Int = AudioDefaults.SPEEX_NOISE_SUPPRESS_DB,
        
        // 重采样配置
        val enableResampling: Boolean = true,
        val resamplingQuality: Int = AudioDefaults.SOXR_QUALITY,
        
        // 通用配置
        val frameSize: Int = AudioDefaults.AUDIO_FRAME_SIZE,
        val enableQualityMonitoring: Boolean = AudioDefaults.ENABLE_QUALITY_MONITORING
    )
    
    private var config = ProcessingConfig()
    
    // 统计信息
    data class ProcessingStats(
        var framesProcessed: Long = 0,
        var voiceFramesDetected: Long = 0,
        var lastVadProbability: Float = 0.0f,
        var lastRmsLevel: Float = 0.0f,
        var lastMaxAmplitude: Int = 0,
        var lastZeroRatio: Float = 0.0f,
        // 🔧 新增：SpeexDSP VAD统计
        var speexVadFrames: Long = 0,
        var lastSpeexVadResult: Boolean = false
    )
    
    private val stats = ProcessingStats()
    
    /**
     * 初始化音频处理器
     */
    fun initialize(
        inputSampleRate: Int,
        inputChannels: Int,
        outputSampleRate: Int = AudioDefaults.WEBRTC_APM_SAMPLE_RATE,
        outputChannels: Int = AudioDefaults.WEBRTC_APM_CHANNELS,
        processingConfig: ProcessingConfig = ProcessingConfig()
    ): Boolean {
        return memScoped {
            try {
                logger.info("初始化第三方音频处理器...")
                
                inputFormat = AudioDefaults.AudioFormat(inputSampleRate, inputChannels)
                outputFormat = AudioDefaults.AudioFormat(outputSampleRate, outputChannels)
                config = processingConfig
                
                logger.info("音频格式: 输入=${inputSampleRate}Hz/${inputChannels}ch, 输出=${outputSampleRate}Hz/${outputChannels}ch")
                
                // 1. 初始化RNNoise（如果启用）
                if (config.enableRNNoise) {
                    if (!initializeRNNoise()) {
                        logger.error("RNNoise初始化失败")
                        return@memScoped false
                    }
                }
                
                // 2. 初始化SpeexDSP（如果启用）
                if (config.enableSpeexAGC || config.enableSpeexVAD || config.enableSpeexDenoise) {
                    if (!initializeSpeexDSP()) {
                        logger.error("SpeexDSP初始化失败")
                        return@memScoped false
                    }
                }
                
                // 3. 初始化SoXR重采样器（如果需要）
                if (config.enableResampling && needsResampling()) {
                    if (!initializeSoXR()) {
                        logger.error("SoXR重采样器初始化失败")
                        return@memScoped false
                    }
                }
                
                isInitialized = true
                logger.info("✅ 第三方音频处理器初始化成功")
                logConfiguration()
                
                true
            } catch (e: Exception) {
                logger.error("初始化失败: ${e.message}")
                cleanup()
                false
            }
        }
    }
    
    /**
     * 初始化RNNoise
     */
    private fun initializeRNNoise(): Boolean {
        // 🔧 完全禁用RNNoise，避免音频质量下降
        if (AudioDefaults.DISABLE_RNNOISE_VAD) {
            logger.info("✅ RNNoise已完全禁用 (避免音频质量下降)")
            return true
        }
        
        return try {
            rnnoiseWrapper = rnnoise_wrapper_create()
            if (rnnoiseWrapper == null) {
                logger.error("RNNoise创建失败")
                return false
            }
            
            // 配置RNNoise参数
            rnnoise_wrapper_set_vad_threshold(rnnoiseWrapper, config.rnnoiseVadThreshold)
            rnnoise_wrapper_set_gain(rnnoiseWrapper, config.rnnoiseGain)
            
            logger.info("✅ RNNoise初始化成功 (VAD阈值=${config.rnnoiseVadThreshold}, 增益=${config.rnnoiseGain})")
            true
        } catch (e: Exception) {
            logger.error("RNNoise初始化异常: ${e.message}")
            false
        }
    }
    
    /**
     * 初始化SpeexDSP
     */
    private fun initializeSpeexDSP(): Boolean {
        return memScoped {
            try {
                // SpeexDSP使用固定的帧大小：160样本 (10ms @ 16kHz)
                val speexFrameSize = 160
                
                // 创建SpeexDSP预处理器
                speexPreprocessor = speex_preprocess_state_init(speexFrameSize, outputFormat.sampleRate)
                if (speexPreprocessor == null) {
                    logger.error("SpeexDSP预处理器创建失败")
                    return@memScoped false
                }
                
                // 配置降噪
                if (config.enableSpeexDenoise) {
                    val enableDenoise = alloc<IntVar>()
                    enableDenoise.value = 1
                    speex_preprocess_ctl(speexPreprocessor, SPEEX_PREPROCESS_SET_DENOISE, enableDenoise.ptr)
                    
                    val noiseSuppress = alloc<IntVar>()
                    noiseSuppress.value = config.speexNoiseSuppress
                    speex_preprocess_ctl(speexPreprocessor, 18, noiseSuppress.ptr)  // SPEEX_PREPROCESS_SET_NOISE_SUPPRESS
                    logger.info("✅ SpeexDSP降噪已启用 (抑制=${config.speexNoiseSuppress}dB)")
                } else {
                    val disableDenoise = alloc<IntVar>()
                    disableDenoise.value = 0
                    speex_preprocess_ctl(speexPreprocessor, SPEEX_PREPROCESS_SET_DENOISE, disableDenoise.ptr)
                }
                
                // 配置AGC
                if (config.enableSpeexAGC) {
                    // 🔧 重新启用AGC，但使用保守设置
                    val enableAgc = alloc<IntVar>()
                    enableAgc.value = 1  // 重新启用AGC
                    speex_preprocess_ctl(speexPreprocessor, SPEEX_PREPROCESS_SET_AGC, enableAgc.ptr)
                    
                    val agcLevel = alloc<FloatVar>()
                    agcLevel.value = 1000.0f  // 🔧 从1500进一步降低到1000，大幅减少对微弱声音的放大
                    speex_preprocess_ctl(speexPreprocessor, SPEEX_PREPROCESS_SET_AGC_LEVEL, agcLevel.ptr)
                    logger.info("✅ SpeexDSP AGC已启用 (超保守电平=1000)")
                } else {
                    val disableAgc = alloc<IntVar>()
                    disableAgc.value = 0
                    speex_preprocess_ctl(speexPreprocessor, SPEEX_PREPROCESS_SET_AGC, disableAgc.ptr)
                }
                
                // 配置VAD
                if (config.enableSpeexVAD) {
                    val enableVad = alloc<IntVar>()
                    enableVad.value = 1
                    val vadResult = speex_preprocess_ctl(speexPreprocessor, SPEEX_PREPROCESS_SET_VAD, enableVad.ptr)
                    logger.info("🔧 VAD启用结果: $vadResult")
                    
                    // 🔧 修复：提高VAD阈值，减少误触发
                    val vadProbStart = alloc<IntVar>()
                    vadProbStart.value = 85  // VAD开始概率阈值：85% (从60%大幅提高，需要很确定才认为是语音)
                    val startResult = speex_preprocess_ctl(speexPreprocessor, 14, vadProbStart.ptr)  // SPEEX_PREPROCESS_SET_PROB_START
                    logger.info("🔧 VAD开始阈值设置结果: $startResult (值=85%)")
                    
                    val vadProbContinue = alloc<IntVar>()
                    vadProbContinue.value = 70  // VAD继续概率阈值：70% (从40%大幅提高，严格要求)
                    val continueResult = speex_preprocess_ctl(speexPreprocessor, 16, vadProbContinue.ptr)  // SPEEX_PREPROCESS_SET_PROB_CONTINUE
                    logger.info("🔧 VAD继续阈值设置结果: $continueResult (值=70%)")
                    
                    logger.info("✅ SpeexDSP VAD已启用 (开始阈值=85%, 继续阈值=70%)")
                } else {
                    val disableVad = alloc<IntVar>()
                    disableVad.value = 0
                    speex_preprocess_ctl(speexPreprocessor, SPEEX_PREPROCESS_SET_VAD, disableVad.ptr)
                }
                
                logger.info("✅ SpeexDSP初始化成功 (帧大小=${speexFrameSize}样本)")
                true
            } catch (e: Exception) {
                logger.error("SpeexDSP初始化异常: ${e.message}")
                false
            }
        }
    }
    
    /**
     * 初始化SoXR重采样器
     */
    private fun initializeSoXR(): Boolean {
        return try {
            soxrWrapper = soxr_wrapper_create()
            if (soxrWrapper == null) {
                logger.error("SoXR包装器创建失败")
                return false
            }
            
            // 配置SoXR
            soxr_io_spec_create(SOXR_INT16_I, SOXR_INT16_I, soxrWrapper)
            soxr_runtime_spec_create(1u, soxrWrapper) // 单线程
            soxr_quality_spec_create(config.resamplingQuality.toUInt(), soxrWrapper)
            
            // 创建重采样器
            val result = soxr_wrapper_create_resampler(
                soxrWrapper,
                inputFormat.sampleRate.toDouble(),
                outputFormat.sampleRate.toDouble(),
                outputFormat.channels.toUInt()
            )
            
            if (result != 0) {
                logger.error("SoXR重采样器创建失败: $result")
                return false
            }
            
            logger.info("✅ SoXR重采样器初始化成功 (${inputFormat.sampleRate}Hz -> ${outputFormat.sampleRate}Hz, 质量=${config.resamplingQuality})")
            true
        } catch (e: Exception) {
            logger.error("SoXR初始化异常: ${e.message}")
            false
        }
    }
    
    /**
     * 处理音频帧
     */
    fun processFrame(audioData: ShortArray): ShortArray {
        if (!isInitialized || !processingEnabled) {
            return audioData
        }
        
        if (audioData.isEmpty()) {
            return audioData
        }
        
        return try {
            var processedData = audioData
            
            // 更新统计信息
            updateInputStats(processedData)
            
            // 1. 声道转换（如果需要）
            if (inputFormat.channels != outputFormat.channels) {
                processedData = convertChannels(processedData, inputFormat.channels, outputFormat.channels)
                
                // 检查声道转换后的质量
                val channelZeroCount = processedData.count { it == 0.toShort() }
                val channelZeroRatio = channelZeroCount.toFloat() / processedData.size
                
                if (channelZeroRatio > 0.5f) {
                    logger.error("❌ 声道转换导致音频质量严重下降！零值比例: ${channelZeroRatio}")
                }
            }
            
            // 2. 重采样（如果需要）
            if (needsResampling() && soxrWrapper != null) {
                val beforeResample = processedData
                processedData = resampleAudio(processedData)
                
                // 检查重采样后的质量
                val resampleZeroCount = processedData.count { it == 0.toShort() }
                val resampleZeroRatio = resampleZeroCount.toFloat() / processedData.size
                
                if (resampleZeroRatio > 0.5f) {
                    logger.error("❌ 重采样导致音频质量严重下降！零值比例: ${resampleZeroRatio}")
                }
            }
            
            // 3. RNNoise降噪（如果启用）- 只做降噪，不做VAD
            if (config.enableRNNoise && rnnoiseWrapper != null && !AudioDefaults.DISABLE_RNNOISE_VAD) {
                processedData = processWithRNNoise(processedData)
                
                // 检查RNNoise后的质量
                val rnnoiseZeroCount = processedData.count { it == 0.toShort() }
                val rnnoiseZeroRatio = rnnoiseZeroCount.toFloat() / processedData.size
                
                if (rnnoiseZeroRatio > 0.5f) {
                    logger.error("❌ RNNoise导致音频质量严重下降！零值比例: ${rnnoiseZeroRatio}")
                }
            }
            
            // 4. SpeexDSP处理（如果启用）- 包含VAD功能
            if ((config.enableSpeexAGC || config.enableSpeexVAD || config.enableSpeexDenoise) && speexPreprocessor != null) {
                processedData = processWithSpeexDSP(processedData)
                
                // 检查SpeexDSP后的质量
                val speexZeroCount = processedData.count { it == 0.toShort() }
                val speexZeroRatio = speexZeroCount.toFloat() / processedData.size
                
                if (speexZeroRatio > 0.5f) {
                    logger.error("❌ SpeexDSP导致音频质量严重下降！零值比例: ${speexZeroRatio}")
                }
            }
            
            // 更新输出统计信息
            updateOutputStats(processedData)
            stats.framesProcessed++
            
            processedData
        } catch (e: Exception) {
            logger.error("音频处理失败: ${e.message}")
            audioData // 返回原始数据
        }
    }
    
    /**
     * 声道转换
     */
    private fun convertChannels(input: ShortArray, inputChannels: Int, outputChannels: Int): ShortArray {
        return when {
            inputChannels == outputChannels -> input
            inputChannels == 2 && outputChannels == 1 -> {
                // 立体声转单声道：取平均值
                ShortArray(input.size / 2) { i ->
                    val left = input[i * 2].toInt()
                    val right = input[i * 2 + 1].toInt()
                    ((left + right) / 2).coerceIn(-32768, 32767).toShort()
                }
            }
            inputChannels == 1 && outputChannels == 2 -> {
                // 单声道转立体声：复制到两个声道
                ShortArray(input.size * 2) { i ->
                    input[i / 2]
                }
            }
            else -> {
                logger.warn("不支持的声道转换: ${inputChannels}ch -> ${outputChannels}ch")
                input
            }
        }
    }
    
    /**
     * 音频重采样
     */
    private fun resampleAudio(input: ShortArray): ShortArray {
        return memScoped {
            try {
                val outputSize = (input.size * outputFormat.sampleRate / inputFormat.sampleRate * 1.1).toInt()
                val outputBuffer = allocArray<ShortVar>(outputSize)
                
                val processedSamples = soxr_wrapper_process_short_to_short(
                    soxrWrapper,
                    input.refTo(0),
                    input.size.toUInt(),
                    outputBuffer,
                    outputSize.toUInt()
                )
                
                if (processedSamples > 0u) {
                    ShortArray(processedSamples.toInt()) { i -> outputBuffer[i] }
                } else {
                    logger.warn("SoXR重采样失败，使用原始数据")
                    input
                }
            } catch (e: Exception) {
                logger.error("重采样异常: ${e.message}")
                input
            }
        }
    }
    
    /**
     * RNNoise处理
     */
    private fun processWithRNNoise(input: ShortArray): ShortArray {
        return memScoped {
            try {
                val outputBuffer = allocArray<ShortVar>(input.size)
                
                // 🔧 只进行降噪处理，完全跳过VAD功能
                val result = rnnoise_wrapper_process(
                    rnnoiseWrapper,
                    input.refTo(0),
                    outputBuffer,
                    input.size,
                    null, // 不获取VAD概率
                    0     // 不处理VAD帧
                )
                
                if (result >= 0) {
                    // 返回降噪后的音频，VAD完全由SpeexDSP处理
                    return@memScoped ShortArray(input.size) { i -> outputBuffer[i] }
                } else {
                    logger.warn("RNNoise降噪处理失败")
                    return@memScoped input
                }
            } catch (e: Exception) {
                logger.error("RNNoise处理异常: ${e.message}")
                input
            }
        }
    }
    
    /**
     * SpeexDSP处理
     */
    private fun processWithSpeexDSP(input: ShortArray): ShortArray {
        return memScoped {
            try {
                val speexFrameSize = 160  // SpeexDSP固定帧大小
                val outputBuffer = allocArray<ShortVar>(input.size)
                
                // 复制输入数据到输出缓冲区
                for (i in input.indices) {
                    outputBuffer[i] = input[i]
                }
                
                var totalVadFrames = 0
                var voiceFrameCount = 0
                var hasAnyVoiceFrame = false  // 🔧 新增：检测是否有任何语音帧
                
                // 按帧处理音频数据
                var offset = 0
                while (offset + speexFrameSize <= input.size) {
                    // 获取当前帧的指针
                    val framePtr = outputBuffer + offset
                    
                    // 🔧 调试：检查当前帧的音频数据
                    val frameMaxAmp = (0 until speexFrameSize).maxOfOrNull { abs(outputBuffer[offset + it].toInt()) } ?: 0
                    val frameRms = sqrt((0 until speexFrameSize).map { outputBuffer[offset + it].toDouble() * outputBuffer[offset + it].toDouble() }.average())
                    
                    // SpeexDSP处理当前帧（就地处理），返回值是VAD结果
                    val vadResult = speex_preprocess_run(speexPreprocessor, framePtr)
                    
                    // 🔧 详细调试：每帧的VAD结果
                    if (stats.framesProcessed % 100 == 0L && totalVadFrames < 5) {  // 只显示前5帧
                        logger.debug("SpeexDSP帧调试[$totalVadFrames]: 振幅=$frameMaxAmp, RMS=${"%.0f".format(frameRms)}, VAD返回值=$vadResult")
                    }
                    
                    // 🔧 根据文档：返回值是VAD结果 (1 for speech, 0 for noise/silence)
                    if (config.enableSpeexVAD) {
                        totalVadFrames++
                        if (vadResult > 0) {
                            voiceFrameCount++
                            hasAnyVoiceFrame = true
                        }
                    }
                    
                    offset += speexFrameSize
                }
                
                // 处理剩余的不完整帧（如果有）
                val remainingSamples = input.size - offset
                if (remainingSamples > 0) {
                    // 为不完整帧创建临时缓冲区，补零到完整帧大小
                    val tempFrame = allocArray<ShortVar>(speexFrameSize)
                    
                    // 复制剩余样本
                    for (i in 0 until remainingSamples) {
                        tempFrame[i] = outputBuffer[offset + i]
                    }
                    // 剩余位置补零
                    for (i in remainingSamples until speexFrameSize) {
                        tempFrame[i] = 0
                    }
                    
                    // 处理临时帧
                    val vadResult = speex_preprocess_run(speexPreprocessor, tempFrame)
                    if (config.enableSpeexVAD) {
                        totalVadFrames++
                        if (vadResult > 0) {
                            voiceFrameCount++
                            hasAnyVoiceFrame = true
                        }
                    }
                    
                    // 将处理后的有效样本复制回输出缓冲区
                    for (i in 0 until remainingSamples) {
                        outputBuffer[offset + i] = tempFrame[i]
                    }
                }
                
                // 🔧 修复：使用更严格的VAD逻辑
                if (config.enableSpeexVAD && totalVadFrames > 0) {
                    stats.speexVadFrames += totalVadFrames.toLong()
                    val voiceRatio = voiceFrameCount.toFloat() / totalVadFrames
                    
                    // 🔧 关键修复：要求更严格的语音判断条件
                    // 1. 至少需要3个语音帧（避免偶然噪音）
                    // 2. 语音比例至少75%（确保大部分帧都是语音）
                    // 3. 总帧数至少5帧（确保有足够的样本）
                    val minVoiceFrames = 3
                    val minVoiceRatio = 0.75f
                    val minTotalFrames = 5
                    
                    stats.lastSpeexVadResult = totalVadFrames >= minTotalFrames && 
                                               voiceFrameCount >= minVoiceFrames && 
                                               voiceRatio >= minVoiceRatio
                    
                    // 🔧 调试：显示SpeexDSP VAD检测详情
                    if (stats.framesProcessed % 1000 == 0L) {  // 从每100帧减少到每1000帧记录一次
                        logger.debug("SpeexDSP VAD: 总帧=${totalVadFrames}(≥$minTotalFrames), 语音帧=${voiceFrameCount}(≥$minVoiceFrames), 比例=${"%.3f".format(voiceRatio)}(≥$minVoiceRatio), 最终结果=${stats.lastSpeexVadResult}")
                    }
                    
                    // 🔧 简化：直接使用SpeexDSP VAD结果，不再fallback
                    stats.lastVadProbability = if (stats.lastSpeexVadResult) 0.8f else 0.0f
                    
                    if (stats.lastSpeexVadResult) {
                        stats.voiceFramesDetected++
                    }
                }
                
                // 返回处理后的数据
                ShortArray(input.size) { i -> outputBuffer[i] }
            } catch (e: Exception) {
                logger.error("SpeexDSP处理异常: ${e.message}")
                input
            }
        }
    }
    
    /**
     * 更新输入统计信息
     */
    private fun updateInputStats(data: ShortArray) {
        if (!config.enableQualityMonitoring) return
        
        val maxAmp = data.maxOfOrNull { abs(it.toInt()) } ?: 0
        val nonZeroCount = data.count { it != 0.toShort() }
        val zeroRatio = (data.size - nonZeroCount).toFloat() / data.size
        val rms = kotlin.math.sqrt(data.map { it.toDouble() * it.toDouble() }.average()).toFloat()
        
        stats.lastMaxAmplitude = maxAmp
        stats.lastZeroRatio = zeroRatio
        stats.lastRmsLevel = rms
    }
    
    /**
     * 更新输出统计信息
     */
    private fun updateOutputStats(data: ShortArray) {
        if (!config.enableQualityMonitoring) return
        
        val maxAmp = data.maxOfOrNull { abs(it.toInt()) } ?: 0
        val nonZeroCount = data.count { it != 0.toShort() }
        val zeroRatio = (data.size - nonZeroCount).toFloat() / data.size
        
        // 只在严重质量问题时才警告
        if (zeroRatio > 0.9f && stats.lastZeroRatio < 0.1f) {
            logger.warn("⚠️ 检测到严重音频质量下降: 零值比例从${stats.lastZeroRatio}增加到${zeroRatio}")
        }
        
        if (maxAmp < stats.lastMaxAmplitude / 20) {
            logger.warn("⚠️ 检测到严重振幅衰减: ${stats.lastMaxAmplitude} -> ${maxAmp}")
        }
    }
    
    /**
     * 是否需要重采样
     */
    private fun needsResampling(): Boolean {
        return inputFormat.sampleRate != outputFormat.sampleRate
    }
    
    /**
     * 记录配置信息
     */
    private fun logConfiguration() {
        logger.info("=== 第三方音频处理器配置 ===")
        logger.info("RNNoise: ${if (config.enableRNNoise && !AudioDefaults.DISABLE_RNNOISE_VAD) "启用(仅降噪)" else "禁用"}")
        if (config.enableRNNoise && !AudioDefaults.DISABLE_RNNOISE_VAD) {
            logger.info("  - 功能: 仅降噪处理，VAD功能已禁用")
            logger.info("  - 增益: ${config.rnnoiseGain}")
        }
        
        logger.info("SpeexDSP降噪: ${if (config.enableSpeexDenoise) "启用" else "禁用"}")
        if (config.enableSpeexDenoise) {
            logger.info("  - 噪声抑制: ${config.speexNoiseSuppress}dB")
        }
        
        logger.info("SpeexDSP AGC: ${if (config.enableSpeexAGC) "启用" else "禁用"}")
        if (config.enableSpeexAGC) {
            logger.info("  - 目标电平: 1000 (超保守设置，大幅降低敏感度)")
        }
        
        logger.info("SpeexDSP VAD: ${if (config.enableSpeexVAD) "启用(主要VAD)" else "禁用"}")
        if (config.enableSpeexVAD) {
            logger.info("  - 功能: 语音活动检测的唯一来源")
            logger.info("  - 阈值: 开始85%, 继续70%, 语音比例≥50% (严格设置，大幅降低敏感度)")
        }
        
        logger.info("重采样: ${if (needsResampling()) "需要" else "跳过"}")
        if (needsResampling()) {
            logger.info("  - 质量: ${config.resamplingQuality}")
        }
        logger.info("质量监控: ${if (config.enableQualityMonitoring) "启用" else "禁用"}")
        
        logger.info("VAD策略: 完全依赖SpeexDSP，RNNoise VAD已禁用")
    }
    
    /**
     * 获取统计信息
     */
    fun getStats(): ProcessingStats = stats.copy()
    
    /**
     * 获取当前配置
     */
    fun getConfig(): ProcessingConfig = config
    
    /**
     * 更新配置
     */
    fun updateConfig(newConfig: ProcessingConfig): Boolean {
        return memScoped {
            try {
                config = newConfig
                
                // 重新配置RNNoise
                if (config.enableRNNoise && rnnoiseWrapper != null) {
                    rnnoise_wrapper_set_vad_threshold(rnnoiseWrapper, config.rnnoiseVadThreshold)
                    rnnoise_wrapper_set_gain(rnnoiseWrapper, config.rnnoiseGain)
                }
                
                // 重新配置SpeexDSP
                if (speexPreprocessor != null) {
                    val enableDenoise = alloc<IntVar>()
                    enableDenoise.value = if (config.enableSpeexDenoise) 1 else 0
                    val enableAgc = alloc<IntVar>()
                    enableAgc.value = if (config.enableSpeexAGC) 1 else 0
                    val enableVad = alloc<IntVar>()
                    enableVad.value = if (config.enableSpeexVAD) 1 else 0
                    
                    speex_preprocess_ctl(speexPreprocessor, SPEEX_PREPROCESS_SET_DENOISE, enableDenoise.ptr)
                    speex_preprocess_ctl(speexPreprocessor, SPEEX_PREPROCESS_SET_AGC, enableAgc.ptr)
                    speex_preprocess_ctl(speexPreprocessor, SPEEX_PREPROCESS_SET_VAD, enableVad.ptr)
                    
                    if (config.enableSpeexDenoise) {
                        val noiseSuppress = alloc<IntVar>()
                        noiseSuppress.value = config.speexNoiseSuppress
                        speex_preprocess_ctl(speexPreprocessor, 18, noiseSuppress.ptr)  // SPEEX_PREPROCESS_SET_NOISE_SUPPRESS
                    }
                    if (config.enableSpeexAGC) {
                        val agcLevel = alloc<FloatVar>()
                        agcLevel.value = config.speexAgcLevel
                        speex_preprocess_ctl(speexPreprocessor, SPEEX_PREPROCESS_SET_AGC_LEVEL, agcLevel.ptr)
                    }
                }
                
                logger.info("配置更新成功")
                logConfiguration()
                true
            } catch (e: Exception) {
                logger.error("配置更新失败: ${e.message}")
                false
            }
        }
    }
    
    /**
     * 启用/禁用处理
     */
    fun setProcessingEnabled(enabled: Boolean) {
        processingEnabled = enabled
        logger.info("音频处理${if (enabled) "已启用" else "已禁用"}")
    }
    
    /**
     * 检查是否检测到语音
     */
    fun isVoiceDetected(): Boolean {
        // 🔧 简化：只使用SpeexDSP VAD
        return if (config.enableSpeexVAD) {
            stats.lastSpeexVadResult
        } else {
            false
        }
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        try {
            rnnoiseWrapper?.let {
                rnnoise_wrapper_destroy(it)
                rnnoiseWrapper = null
                logger.info("RNNoise资源已释放")
            }
            
            speexPreprocessor?.let {
                speex_preprocess_state_destroy(it)
                speexPreprocessor = null
                logger.info("SpeexDSP资源已释放")
            }
            
            soxrWrapper?.let {
                soxr_wrapper_destroy(it)
                soxrWrapper = null
                logger.info("SoXR资源已释放")
            }
            
            isInitialized = false
            logger.info("第三方音频处理器资源清理完成")
        } catch (e: Exception) {
            logger.error("资源清理异常: ${e.message}")
        }
    }
} 