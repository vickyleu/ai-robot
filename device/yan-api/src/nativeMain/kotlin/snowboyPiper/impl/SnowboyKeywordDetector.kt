@file:OptIn(
    ExperimentalForeignApi::class, ExperimentalTime::class, ExperimentalTime::class,
    ExperimentalForeignApi::class
)

package snowboyPiper.impl

import com.airobot.device.yanapi.snowboyPiper.config.VoiceAssistantConfig
import com.airobot.device.yanapi.snowboyPiper.interfaces.AudioAnalyzer
import com.airobot.device.yanapi.snowboyPiper.interfaces.VoiceStateManager
import com.airobot.rnnoiseinterop.RNNoiseWrapper
import com.airobot.rnnoiseinterop.SOXR_FLOAT32_I
import com.airobot.rnnoiseinterop.rnnoise_wrapper_create
import com.airobot.rnnoiseinterop.rnnoise_wrapper_destroy
import com.airobot.rnnoiseinterop.rnnoise_wrapper_process
import com.airobot.rnnoiseinterop.rnnoise_wrapper_process_batch
import com.airobot.rnnoiseinterop.rnnoise_wrapper_set_gain
import com.airobot.rnnoiseinterop.rnnoise_wrapper_set_vad_threshold
import com.airobot.rnnoiseinterop.soxr_io_spec_create
import com.airobot.rnnoiseinterop.soxr_quality_spec_create
import com.airobot.rnnoiseinterop.soxr_wrapper_create
import com.airobot.rnnoiseinterop.soxr_wrapper_create_resampler
import com.airobot.rnnoiseinterop.soxr_wrapper_destroy
import com.airobot.rnnoiseinterop.soxr_wrapper_process
import com.airobot.snowboyinterop.SnowboyDetectWrapper
import com.airobot.snowboyinterop.snowboy_apply_frontend
import com.airobot.snowboyinterop.snowboy_bits_per_sample
import com.airobot.snowboyinterop.snowboy_create
import com.airobot.snowboyinterop.snowboy_free
import com.airobot.snowboyinterop.snowboy_num_channels
import com.airobot.snowboyinterop.snowboy_run_detection_int16
import com.airobot.snowboyinterop.snowboy_sample_rate
import com.airobot.snowboyinterop.snowboy_set_audio_gain
import com.airobot.snowboyinterop.snowboy_set_sensitivity
import kotlinx.cinterop.CArrayPointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import snowboyPiper.impl.VoskSpeechService.Companion.executeCommand
import snowboyPiper.interfaces.AudioPlayer
import snowboyPiper.interfaces.KeywordDetector
import snowboyPiper.interfaces.KeywordDetector.DetectorState
import snowboyPiper.interfaces.KeywordDetector.DetectorState.ERROR
import snowboyPiper.interfaces.KeywordDetector.DetectorState.NoEvent
import snowboyPiper.interfaces.KeywordDetector.DetectorState.Silence
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Snowboy关键词检测器实现
 * 负责初始化和运行Snowboy关键词检测
 */
class SnowboyKeywordDetector(
    private val audioAnalyzer: AudioAnalyzer,
    private val voiceStateManager: VoiceStateManager
) : KeywordDetector {


    // 检测器实例
    private var snowboyDetector: CPointer<SnowboyDetectWrapper>? = null

    // 检测状态
    private val _detectionState = MutableStateFlow(KeywordDetector.DetectionState.IDLE)
    override val detectionState: StateFlow<KeywordDetector.DetectionState> =
        _detectionState.asStateFlow()

    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Default)

    // 去抖动控制
    private var lastDetectionTime = 0L
    private val debounceTimeMs = 500L // 0.5秒去抖动时间

    // 回声消除相关变量
    private var lastPlaybackTime = 0L
    private val echoSuppressionTime = 1500L // 延长回声抑制时间，1.5秒
    
    // 音频活动检测变量
    private var lastVoiceActivityTime = 0L
    private var isInContinuousSpeech = false
    private val voiceContinuityThreshold = 800L // 800毫秒内的声音视为连续语音
    private val silencePauseThreshold = 1000L // 1秒无声视为停顿

    // 音频累积缓冲
    private val audioAccumulationWindow = 4000 // 扩大到4秒的音频数据
    private val audioBuffer = ShortArray(audioAccumulationWindow)
    private var audioBufferPosition = 0
    private var audioBufferFilled = false
    private var pendingAudio = false
    private var lastAudioHighEnergy = 0L

    // 存储初始化参数，用于可能的重新初始化
    private var lastResourcePath = ""
    private var lastModelPath = ""
    private var lastSensitivity = VoiceAssistantConfig.snowboySensitivity

    // 缓存的RNNoise包装器
    private var cachedRNNoiseWrapper: CPointer<RNNoiseWrapper>? = null
    private var lastRNNoiseUseTime = 0L
    private val rnnoiseCacheTimeout = 30000L // 30秒超时，避免长时间占用资源

    /**
     * 初始化检测器
     * @param resourcePath 资源文件路径
     * @param modelPath 模型文件路径
     * @param sensitivity 灵敏度，范围0-1
     * @return 初始化是否成功
     */
    override fun initialize(resourcePath: String, modelPath: String, sensitivity: Float): Boolean {
        // 保存参数以便重新初始化
        lastResourcePath = resourcePath
        lastModelPath = modelPath
        lastSensitivity = sensitivity

        _detectionState.value = KeywordDetector.DetectionState.INITIALIZING

        try {
            snowboyDetector = snowboy_create(resourcePath, modelPath)
            if (snowboyDetector == null) {
                println("[ERROR] Snowboy检测器创建失败")
                _detectionState.value = KeywordDetector.DetectionState.ERROR
                return false
            }
            
            // 使用高灵敏度，确保能捕获唤醒词
            val actualSensitivity = 0.6f
            
            println("[INFO] 设置灵敏度 ${actualSensitivity}")
            snowboy_set_sensitivity(snowboyDetector, actualSensitivity.toString())
            
            // 设置适中的音频增益
            snowboy_set_audio_gain(snowboyDetector, 2.0f)
            
            // 启用前端处理
            snowboy_apply_frontend(snowboyDetector, 1)
            
            println("[DEBUG] 灵敏度设置完成，准备进行模型验证")

            // 检查模型是否正确加载
            scope.launch {
                // 验证模型文件大小和权限
                val checkModelSizeCmd = "ls -la $modelPath"
                val modelSizeInfo = executeCommand(checkModelSizeCmd).trim()
                println("[INFO] 模型文件信息: $modelSizeInfo")
                // 检查模型文件内容格式
                val checkModelFormatCmd = "file $modelPath"
                val modelFormatInfo = executeCommand(checkModelFormatCmd).trim()
                println("[INFO] 模型文件格式: $modelFormatInfo")
            }
            println("[INFO] Snowboy检测器初始化成功")

            _detectionState.value = KeywordDetector.DetectionState.LISTENING
            return true
        } catch (e: Exception) {
            println("[ERROR] Snowboy初始化异常: ${e.message}")
            e.printStackTrace()
            _detectionState.value = KeywordDetector.DetectionState.ERROR
            return false
        }
    }

    /**
     * 检测关键词
     * @param buffer 音频数据缓冲区
     * @param frameCount 帧数
     * @return 检测结果，大于0表示检测到关键词，0表示未检测到，负值表示错误
     */
    override fun detect(
        player: AudioPlayer,
        buffer: ShortArray,
        frameCount: Int,
        sampleRate: Int,
        channels: Int
    ): DetectorState {
        if (snowboyDetector == null) {
            println("[ERROR] Snowboy检测器未初始化")
            return ERROR
        }

        if (_detectionState.value != KeywordDetector.DetectionState.LISTENING) {
            _detectionState.value = KeywordDetector.DetectionState.LISTENING
        }

        try {
            // 检查是否在回声抑制时间内
            val currentTime = Clock.System.now().toEpochMilliseconds()
            if (currentTime - lastPlaybackTime < echoSuppressionTime) {
                // 在回声抑制时间内直接忽略，不做任何处理
                return NoEvent
            }
            
            // 计算音频能量
            var sumSquares = 0.0
            var maxSample = 0.0
            for (sample in buffer) {
                val sampleValue = sample.toDouble()
                sumSquares += (sampleValue * sampleValue)
                maxSample = maxOf(maxSample, abs(sampleValue))
            }
            val rms = kotlin.math.sqrt(sumSquares / frameCount)
            
            // 有声音活动时，更新时间戳
            val hasCurrentVoiceActivity = rms >= 30.0
            
            // 管理连续语音状态
            if (hasCurrentVoiceActivity) {
                // 更新高能量音频时间戳
                lastAudioHighEnergy = currentTime
                
                // 如果时间足够近，判定为连续语音
                if (currentTime - lastVoiceActivityTime < voiceContinuityThreshold) {
                    isInContinuousSpeech = true
                } else if (currentTime - lastVoiceActivityTime > silencePauseThreshold) {
                    // 如果间隔过长，认为是新的语音开始
                    isInContinuousSpeech = false
                    // 重置音频缓冲区
                    if (audioBufferPosition > 0) {
                        // 保留少量历史数据
                        val preserveAmount = min(500, audioBufferPosition)
                        // 使用Kotlin Native兼容的数组复制方法
                        audioBuffer.copyInto(
                            destination = audioBuffer,
                            destinationOffset = 0,
                            startIndex = audioBufferPosition - preserveAmount,
                            endIndex = audioBufferPosition
                        )
                        audioBufferPosition = preserveAmount
                    }
                }
                
                lastVoiceActivityTime = currentTime
            }
            
            // 最低能量阈值检查
            if (rms < 30.0) { // 极低能量阈值
                // 长时间静音，重置音频累积，但不要太快重置
                val currentTimeCheck = Clock.System.now().toEpochMilliseconds()
                if (pendingAudio && (currentTimeCheck - lastAudioHighEnergy > silencePauseThreshold)) {
                    // 在静音期间，保留部分音频以便处理
                    if (audioBufferPosition > 0) {
                        val preserveAmount = min(500, audioBufferPosition)
                        // 使用Kotlin Native兼容的数组复制方法
                        audioBuffer.copyInto(
                            destination = audioBuffer,
                            destinationOffset = 0,
                            startIndex = audioBufferPosition - preserveAmount,
                            endIndex = audioBufferPosition
                        )
                        audioBufferPosition = preserveAmount
                        audioBufferFilled = false
                        pendingAudio = false
                    }
                }
                return NoEvent
            }
            
            // 计算过零率
            var zeroCrossings = 0
            for (i in 1 until frameCount) {
                if ((buffer[i] > 0 && buffer[i-1] <= 0) ||
                    (buffer[i] <= 0 && buffer[i-1] > 0)) {
                    zeroCrossings++
                }
            }
            val zcr = zeroCrossings.toDouble() / frameCount
            
            // 过零率检查 - 仍然过滤极端情况
            if (zcr > 0.95) {
                return NoEvent
            }
            
            // 使用RNNoise进行初步VAD检测
            // 只在连续语音停顿或音频能量较高时进行检测，减少不必要的处理
            val isHumanVoice = if (!isInContinuousSpeech || rms > 100.0) {
                checkVoiceWithRNNoise(buffer)
            } else {
                // 如果是连续语音中，直接假设是人声
                true
            }
            
            // 首先进行语音活动检测，确保是人声
            // 在连续语音中，不要重复检查语音有效性
            val isVoiceActivity = isHumanVoice || isInContinuousSpeech || audioAnalyzer.hasVoiceActivity(buffer)
            val containsValidVoice = isHumanVoice || isInContinuousSpeech || audioAnalyzer.containsValidVoice(buffer)
            
            // 只有当检测到真实人声或连续语音时才继续处理
            if (!isVoiceActivity && !containsValidVoice && rms < 80.0 && !isInContinuousSpeech) {
                return NoEvent
            }
            
            // 累积音频数据到缓冲区 - 保持连续性
            if (frameCount < audioAccumulationWindow) {
                // 复制新的音频数据到缓冲区
                val remainingSpace = audioAccumulationWindow - audioBufferPosition
                val copyLength = minOf(frameCount, remainingSpace)
                
                if (copyLength > 0) {
                    buffer.copyInto(audioBuffer, audioBufferPosition, 0, copyLength)
                    audioBufferPosition += copyLength
                }
                
                // 如果缓冲区已满或者积累了足够的数据，开始处理
                if (audioBufferPosition >= audioAccumulationWindow * 0.5) { // 50%就可以开始检测
                    audioBufferFilled = true
                } else {
                    pendingAudio = true
                    return NoEvent
                }
            } else {
                // 输入帧数足够大，保留历史数据
                if (audioBufferPosition > 0) {
                    // 保留部分历史数据
                    val preserveAmount = min(audioBufferPosition, audioAccumulationWindow / 4)
                    // 使用Kotlin Native兼容的数组复制方法
                    audioBuffer.copyInto(
                        destination = audioBuffer,
                        destinationOffset = 0,
                        startIndex = audioBufferPosition - preserveAmount,
                        endIndex = audioBufferPosition
                    )
                    audioBufferPosition = preserveAmount
                }
                
                // 复制新数据
                val availableSpace = audioAccumulationWindow - audioBufferPosition
                val copyLength = minOf(frameCount, availableSpace)
                if (copyLength > 0) {
                    buffer.copyInto(audioBuffer, audioBufferPosition, 0, copyLength)
                    audioBufferPosition += copyLength
                }
                
                audioBufferFilled = true
            }
            
            // 只在缓冲区已经累积了足够的数据时才执行关键词检测
            if (!audioBufferFilled) {
                return NoEvent
            }
            
            // 将音频数据转换为C指针
            val bufferPtr = nativeHeap.allocArray<ShortVar>(audioBufferPosition)
            
            // 复制音频数据，应用轻微增益
            val gain = 1.5f // 适当增益以增强信号
            for (i in 0 until audioBufferPosition) {
                val ampValue = audioBuffer[i].toInt() * gain
                bufferPtr[i] = kotlin.math.max(-32768, kotlin.math.min(32767, ampValue.toInt())).toShort()
            }

            // 检查需要的音频格式
            val requiredSampleRate = snowboy_sample_rate(snowboyDetector)
            val requiredChannels = snowboy_num_channels(snowboyDetector)
            
            // 执行转码（如果需要）- 只在音频停顿时执行降噪操作，减少处理
            val currentSilenceTime = currentTime - lastVoiceActivityTime
            val shouldApplyNoiseReduction = currentSilenceTime > silencePauseThreshold || !isInContinuousSpeech
            
            val (finalBufferPtr, outputSize) = if (sampleRate != requiredSampleRate || channels != requiredChannels) {
                val bufferTrans = fixedTranscoding(audioBufferPosition, bufferPtr, sampleRate, requiredSampleRate, shouldApplyNoiseReduction)
                if (bufferTrans == null) {
                    nativeHeap.free(bufferPtr.rawValue)
                    
                    // 保留部分缓冲区以备后续使用
                    if (audioBufferPosition > 0) {
                        val preserveAmount = min(500, audioBufferPosition)
                        // 使用Kotlin Native兼容的数组复制方法
                        audioBuffer.copyInto(
                            destination = audioBuffer,
                            destinationOffset = 0,
                            startIndex = audioBufferPosition - preserveAmount,
                            endIndex = audioBufferPosition
                        )
                        audioBufferPosition = preserveAmount
                        audioBufferFilled = false
                    }
                    
                    return ERROR
                }
                bufferTrans
            } else {
                // 如果格式已匹配但需要降噪，仍然应用降噪处理
                if (shouldApplyNoiseReduction) {
                    val denoised = applyNoiseReduction(bufferPtr, audioBufferPosition)
                    if (denoised != null) {
                        denoised to audioBufferPosition
                    } else {
                        bufferPtr to audioBufferPosition
                    }
                } else {
                    bufferPtr to audioBufferPosition
                }
            }

            // 执行关键词检测
            val result = snowboy_run_detection_int16(snowboyDetector, finalBufferPtr, outputSize, 0)

            // 根据检测结果决定是否保留音频数据
            if (result > 0) {
                // 检测到关键词，通知分析器即将播放音频
                audioAnalyzer.notifyAudioPlayback(buffer)
                // 播放音频并记录时间
                player.playAudio(finalBufferPtr, outputSize)
                lastPlaybackTime = currentTime
                
                // 完全重置音频缓冲区
                audioBufferPosition = 0
                audioBufferFilled = false
                pendingAudio = false
                isInContinuousSpeech = false
            } else if (isInContinuousSpeech) {
                // 连续语音中，保留部分数据
                if (audioBufferPosition > 0) {
                    val preserveAmount = min(500, audioBufferPosition)
                    // 使用Kotlin Native兼容的数组复制方法
                    audioBuffer.copyInto(
                        destination = audioBuffer,
                        destinationOffset = 0,
                        startIndex = audioBufferPosition - preserveAmount,
                        endIndex = audioBufferPosition
                    )
                    audioBufferPosition = preserveAmount
                    audioBufferFilled = false
                }
            } else {
                // 非连续语音且未检测到关键词，重置缓冲区
                audioBufferPosition = 0
                audioBufferFilled = false
                pendingAudio = false
            }

            // 释放缓冲区
            if (finalBufferPtr != bufferPtr) {
                nativeHeap.free(finalBufferPtr.rawValue)
            }
            nativeHeap.free(bufferPtr.rawValue)
            
            // 处理检测结果
            if (result > 0) {
                // 去抖动
                val currentTimestamp = Clock.System.now().toEpochMilliseconds()
                if (currentTimestamp - lastDetectionTime < debounceTimeMs) {
                    return NoEvent
                }
                
                // 更新状态
                lastDetectionTime = currentTimestamp
                _detectionState.value = KeywordDetector.DetectionState.DETECTED
                
                // 返回检测结果
                return DetectorState.fromValue(result)
            }
            
            // 处理其他结果
            return when (result) {
                -2 -> Silence
                -1 -> ERROR
                0 -> NoEvent
                else -> NoEvent
            }
        } catch (e: Exception) {
            println("[ERROR] 关键词检测异常: ${e.message}")
            _detectionState.value = KeywordDetector.DetectionState.ERROR
            
            // 重置音频累积
            audioBufferPosition = 0
            audioBufferFilled = false
            pendingAudio = false
            
            return ERROR
        }
    }

    private fun fixedTranscoding(
        frameCount: Int,
        bufferPtr: CArrayPointer<ShortVar>,
        sampleRate: Int,
        requiredSampleRate: Int,
        applyNoiseReduction: Boolean = true
    ): Pair<CArrayPointer<ShortVar>,Int>? {
        // 第1步：如果需要，应用RNNoise降噪处理
        val denoisedBufferPtr = if (applyNoiseReduction) {
            applyNoiseReduction(bufferPtr, frameCount) ?: bufferPtr
        } else {
            bufferPtr
        }
        
        // 计算输出缓冲区大小
        val outputSize = ((frameCount.toDouble() * requiredSampleRate) / sampleRate).toInt()
        
        // 创建输出缓冲区
        val floatOutput = nativeHeap.allocArray<FloatVar>(outputSize)
        
        // 创建soxr实例
        val wrapper = soxr_wrapper_create()
        if (wrapper == null) {
            if (denoisedBufferPtr != bufferPtr) {
                nativeHeap.free(denoisedBufferPtr.rawValue)
            }
            nativeHeap.free(floatOutput.rawValue)
            return null
        }
        
        // 配置输入输出格式
        soxr_io_spec_create(SOXR_FLOAT32_I, SOXR_FLOAT32_I, wrapper)
        soxr_quality_spec_create(SOXR_FLOAT32_I, wrapper)
        
        // 创建重采样器
        soxr_wrapper_create_resampler(
            wrapper, sampleRate.toDouble(), requiredSampleRate.toDouble()
        )
        
        if (wrapper.pointed.soxr == null) {
            if (denoisedBufferPtr != bufferPtr) {
                nativeHeap.free(denoisedBufferPtr.rawValue)
            }
            nativeHeap.free(floatOutput.rawValue)
            soxr_wrapper_destroy(wrapper)
            return null
        }
        
        // 执行重采样
        val processResult = soxr_wrapper_process(
            wrapper,
            in_data = denoisedBufferPtr,
            in_size = frameCount.toUInt(),
            out_data = floatOutput,
            out_size = outputSize.toUInt(),
        )
        
        // 如果降噪后的缓冲区不是原始缓冲区，释放它
        if (denoisedBufferPtr != bufferPtr) {
            nativeHeap.free(denoisedBufferPtr.rawValue)
        }
        
        // 获取处理后实际输出的样本数
        val actualOutputSize = processResult.toInt()
        if (actualOutputSize <= 0) {
            nativeHeap.free(floatOutput.rawValue)
            soxr_wrapper_destroy(wrapper)
            return null
        }
        
        // 创建输出short数组
        val resampledBuffer = nativeHeap.allocArray<ShortVar>(actualOutputSize)
        
        // 转换为输出格式
        for (i in 0 until actualOutputSize) {
            var sample = floatOutput[i]
            // 限制在[-1.0, 1.0]范围内
            if (sample > 1.0f) sample = 1.0f
            if (sample < -1.0f) sample = -1.0f
            resampledBuffer[i] = (sample * 32767.0f).toInt().toShort()
        }
        
        // 清理资源
        nativeHeap.free(floatOutput.rawValue)
        soxr_wrapper_destroy(wrapper)
        
        return resampledBuffer to actualOutputSize
    }

    /**
     * 获取RNNoise包装器，使用缓存避免频繁创建销毁
     */
    private fun getRNNoiseWrapper(): CPointer<RNNoiseWrapper>? {
        val currentTime = Clock.System.now().toEpochMilliseconds()
        
        // 检查缓存是否超时
        if (cachedRNNoiseWrapper != null && currentTime - lastRNNoiseUseTime > rnnoiseCacheTimeout) {
            // 超时释放资源
            rnnoise_wrapper_destroy(cachedRNNoiseWrapper)
            cachedRNNoiseWrapper = null
        }
        
        if (cachedRNNoiseWrapper == null) {
            cachedRNNoiseWrapper = rnnoise_wrapper_create()
        }
        
        // 更新最后使用时间
        if (cachedRNNoiseWrapper != null) {
            lastRNNoiseUseTime = currentTime
        }
        
        return cachedRNNoiseWrapper
    }

    /**
     * 使用RNNoise进行语音活动检测
     * @param buffer 音频数据
     * @return 是否检测到人声
     */
    private fun checkVoiceWithRNNoise(buffer: ShortArray): Boolean {
        try {
            // 创建输入和输出缓冲区
            val frameCount = buffer.size
            val inputBuffer = nativeHeap.allocArray<ShortVar>(frameCount)
            val outputBuffer = nativeHeap.allocArray<ShortVar>(frameCount)
            
            // 复制音频数据到输入缓冲区
            for (i in 0 until frameCount) {
                inputBuffer[i] = buffer[i]
            }
            
            // 使用缓存的RNNoise包装器
            val rnnWrapper = getRNNoiseWrapper()
            if (rnnWrapper == null) {
                nativeHeap.free(inputBuffer.rawValue)
                nativeHeap.free(outputBuffer.rawValue)
                return true // 出错时默认接受
            }
            
            // 配置RNNoise - 使用极低的VAD阈值以提高灵敏度
            rnnoise_wrapper_set_vad_threshold(rnnWrapper, 0.05f) // 极低阈值
            rnnoise_wrapper_set_gain(rnnWrapper, 3.0f) // 高增益
            
            // 创建VAD概率数组
            val maxVadValues = frameCount / 480 + 1 // 每480样本一个VAD值
            val vadProbabilitiesPtr = nativeHeap.allocArray<FloatVar>(maxVadValues)
            
            // 处理音频数据
            val processResult = rnnoise_wrapper_process(
                rnnWrapper,
                inputBuffer,
                outputBuffer,
                frameCount,
                vadProbabilitiesPtr,
                maxVadValues
            )
            
            // 检查处理结果
            if (processResult <= 0) {
                nativeHeap.free(inputBuffer.rawValue)
                nativeHeap.free(outputBuffer.rawValue)
                nativeHeap.free(vadProbabilitiesPtr.rawValue)
                return true // 出错时默认接受
            }
            
            // 分析VAD概率
            var voiceFrames = 0
            var totalFrames = minOf(processResult, maxVadValues)
            var maxProb = 0.0f
            
            for (i in 0 until totalFrames) {
                val prob = vadProbabilitiesPtr[i]
                maxProb = max(maxProb, prob)
                if (prob >= 0.05f) { // 极低阈值
                    voiceFrames++
                }
            }
            
            // 释放资源
            nativeHeap.free(inputBuffer.rawValue)
            nativeHeap.free(outputBuffer.rawValue)
            nativeHeap.free(vadProbabilitiesPtr.rawValue)
            
            // 判断是否检测到足够的人声帧 - 极低阈值
            val voiceRatio = if (totalFrames > 0) voiceFrames.toFloat() / totalFrames else 0f
            val isHumanVoice = voiceRatio >= 0.05f || maxProb >= 0.1f // 极低阈值
            
            // 只在检测到人声时输出日志
            if (isHumanVoice) {
                println("[DEBUG] RNNoise VAD结果: 语音帧比例=$voiceRatio, 最高概率=$maxProb, 是人声=$isHumanVoice")
            }
            
            return isHumanVoice
        } catch (e: Exception) {
            println("[ERROR] RNNoise VAD检测异常: ${e.message}")
            return true // 出错时默认接受，避免错误地过滤掉语音
        }
    }

    private fun applyNoiseReduction(
        inputBuffer: CArrayPointer<ShortVar>,
        frameCount: Int
    ): CArrayPointer<ShortVar>? {
        try {
            // 创建输出缓冲区
            val outputBuffer = nativeHeap.allocArray<ShortVar>(frameCount)
            
            // 创建或获取RNNoise包装器
            val rnnWrapper = getRNNoiseWrapper()
            if (rnnWrapper == null) {
                nativeHeap.free(outputBuffer.rawValue)
                return null
            }
            
            // 配置RNNoise - 极低阈值，高增益
            rnnoise_wrapper_set_vad_threshold(rnnWrapper, 0.05f) // 极低阈值
            rnnoise_wrapper_set_gain(rnnWrapper, 3.0f) // 高增益
            
            // 处理音频数据
            val voiceFramesDetectedPtr = nativeHeap.alloc<IntVar>()
            voiceFramesDetectedPtr.value = 0
            
            val processResult = rnnoise_wrapper_process_batch(
                rnnWrapper,
                inputBuffer,
                outputBuffer,
                frameCount,
                voiceFramesDetectedPtr.ptr
            )
            
            // 获取检测到的语音帧数
            val voiceFramesDetected = voiceFramesDetectedPtr.value
            nativeHeap.free(voiceFramesDetectedPtr.rawPtr)
            
            // 检查处理结果
            if (processResult <= 0) {
                nativeHeap.free(outputBuffer.rawValue)
                return null
            }
            
            // 只在检测到语音帧时输出日志
            if (voiceFramesDetected > 0) {
                println("[DEBUG] RNNoise处理完成，检测到语音帧: $voiceFramesDetected")
            }
            
            return outputBuffer
        } catch (e: Exception) {
            println("[ERROR] RNNoise处理异常: ${e.message}")
            return null
        }
    }

    /**
     * 计算音频的能量模式得分，用于判断是否符合唤醒词特征
     * 唤醒词通常有明显的能量变化模式，如"小度小度"有明显的两段能量峰值
     */
    private fun calculateEnergyPattern(audioData: ShortArray): Double {
        // 将音频分成多个小段，计算每段的能量
        val segmentCount = 12 // 分12段，可以捕捉"小度小度"的音节变化
        val segmentSize = audioData.size / segmentCount
        val segmentEnergies = DoubleArray(segmentCount)
        
        // 计算每段的能量
        for (i in 0 until segmentCount) {
            var energy = 0.0
            val start = i * segmentSize
            val end = minOf((i + 1) * segmentSize, audioData.size)
            
            for (j in start until end) {
                energy += audioData[j] * audioData[j]
            }
            segmentEnergies[i] = energy / (end - start)
        }
        
        // 标准化能量值
        val maxEnergy = segmentEnergies.maxOrNull() ?: 1.0
        if (maxEnergy > 0) {
            for (i in segmentEnergies.indices) {
                segmentEnergies[i] = segmentEnergies[i] / maxEnergy
            }
        }
        
        // 检查能量模式是否符合唤醒词特征 - "小度小度"通常有两个能量峰值
        // 计算能量峰值数量和位置
        var peakCount = 0
        val peakPositions = mutableListOf<Int>()
        
        for (i in 1 until segmentCount - 1) {
            if (segmentEnergies[i] > 0.6 && // 能量峰值必须足够高
                segmentEnergies[i] > segmentEnergies[i-1] && 
                segmentEnergies[i] > segmentEnergies[i+1]) {
                peakCount++
                peakPositions.add(i)
            }
        }
        
        // 计算模式匹配得分
        var patternScore = 0.0
        
        // 理想的"小度小度"应该有2-4个能量峰值，且峰值之间有一定间隔
        if (peakCount >= 2 && peakCount <= 4) {
            patternScore += 0.5 // 基础分
            
            // 检查峰值间隔是否合理
            if (peakPositions.size >= 2) {
                for (i in 0 until peakPositions.size - 1) {
                    val gap = peakPositions[i+1] - peakPositions[i]
                    // 合理的间隔应该在1-5段之间
                    if (gap >= 1 && gap <= 5) {
                        patternScore += 0.25
                    }
                }
            }
        }
        
        // 记录能量模式信息
        println("[DEBUG] 能量模式分析: 峰值数=$peakCount, 峰值位置=$peakPositions, 匹配得分=$patternScore")
        return patternScore
    }
    
    /**
     * 根据能量模式判断是否符合唤醒词特征
     */
    private fun hasWakewordEnergyPattern(audioData: ShortArray): Boolean {
        val energyPatternScore = calculateEnergyPattern(audioData)
        // 要求至少达到0.7的匹配度
        return energyPatternScore >= 0.7
    }

    /**
     * 检查音频是否具有人声的频谱特征模式
     */
    private fun hasHumanVoicePattern(buffer: ShortArray, frameCount: Int): Boolean {
        // 暂时放宽检测标准，默认接受所有音频
        println("[DEBUG] 人声模式检测：暂时放宽标准，接受所有音频")
        return true;
        
        /*
        // 将音频分为多个子帧
        val subFrameSize = 1024
        val subFrameCount = frameCount / subFrameSize
        
        if (subFrameCount < 3) {
            return true // 帧太短，无法进行充分分析
        }
        
        // 计算每个子帧的能量
        val subFrameEnergies = DoubleArray(subFrameCount)
        for (i in 0 until subFrameCount) {
            var energy = 0.0
            val startIdx = i * subFrameSize
            val endIdx = minOf((i + 1) * subFrameSize, frameCount)
            
            for (j in startIdx until endIdx) {
                energy += buffer[j] * buffer[j]
            }
            subFrameEnergies[i] = energy / (endIdx - startIdx)
        }
        
        // 计算能量变化模式 - 人声通常有明显的能量波动
        var energyVariations = 0
        for (i in 1 until subFrameCount) {
            val ratio = subFrameEnergies[i] / subFrameEnergies[i-1]
            if (ratio < 0.5 || ratio > 2.0) {
                energyVariations++
            }
        }
        
        // 人声通常有足够的能量变化
        val hasEnoughVariations = energyVariations >= subFrameCount / 5
        
        // 人声通常不会有突然的能量峰值
        var hasSuddenPeaks = false
        for (i in 1 until subFrameCount - 1) {
            val peakRatio1 = subFrameEnergies[i] / subFrameEnergies[i-1]
            val peakRatio2 = subFrameEnergies[i] / subFrameEnergies[i+1]
            
            if (peakRatio1 > 10.0 && peakRatio2 > 10.0) {
                hasSuddenPeaks = true
                break
            }
        }
        
        // 键盘敲击通常有突然的能量峰值，然后快速下降
        // 人声通常有更平滑的能量变化
        return hasEnoughVariations && !hasSuddenPeaks
        */
    }

    /**
     * 释放资源
     */
    override fun release() {
        try {
            snowboyDetector?.let {
                snowboy_free(it)
            }
            snowboyDetector = null
            
            // 释放RNNoise包装器
            if (cachedRNNoiseWrapper != null) {
                rnnoise_wrapper_destroy(cachedRNNoiseWrapper)
                cachedRNNoiseWrapper = null
            }
            
            _detectionState.value = KeywordDetector.DetectionState.IDLE
        } catch (e: Exception) {
            println("[WARN] 释放资源时出错: ${e.message}")
            _detectionState.value = KeywordDetector.DetectionState.ERROR
        }
    }
}