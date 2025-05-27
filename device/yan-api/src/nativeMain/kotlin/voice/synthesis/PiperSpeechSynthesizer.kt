@file:OptIn(ExperimentalForeignApi::class)

package voice.synthesis

import com.airobot.core.utils.format
import com.airobot.piperinterop.PiperContext
import com.airobot.piperinterop.piper_wrapper_init
import com.airobot.piperinterop.piper_wrapper_terminate
import com.airobot.piperinterop.piper_wrapper_text_to_audio
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.pclose
import platform.posix.popen
import voice.acquisition.portaudio.PortAudioDevice
import voice.api.SpeechSynthesizerApi
import voice.util.AudioDefaults
import voice.util.LogManager

/**
 * Piper语音合成器实现
 * 负责将文本转换为语音
 */
class PiperSpeechSynthesizer : SpeechSynthesizerApi {
    // Piper上下文
    private var piperContext: CValuesRef<PiperContext>? = null

    private val logger = LogManager.Logger("PiperSpeechSynthesizer")
    // 合成状态
    private val _synthesisState = MutableStateFlow(SynthesisState.IDLE)
    val synthesisState: StateFlow<SynthesisState> = _synthesisState.asStateFlow()

    // 使用全局单例的音频设备，避免多个实例导致状态不同步
    private val audioPlayer = PortAudioDevice.getInstance()

    // 同步锁，用于线程安全
    private val mutex = Mutex()

    // 是否正在播放
    private var isSpeakingFlag = false

    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Default)

    // 枚举内部状态
    enum class SynthesisState {
        IDLE,
        INITIALIZING,
        SYNTHESIZING,
        SPEAKING,
        ERROR
    }

    init {
        // 检查Piper库是否正确加载
        checkPiperLibLoaded()
        // 假设主流程已经初始化并启动了PortAudioDevice，若未启动则尝试启动
        if (audioPlayer.deviceState.value == voice.hal.AudioDevice.AudioDeviceState.IDLE) {
            audioPlayer.initialize(AudioDefaults.INPUT_DEVICE_SAMPLE_RATE)
        }
    }

    /**
     * 检查Piper库是否正确加载
     */
    private fun checkPiperLibLoaded() {
        try {
            // 获取库的symbol，如果能获取到说明库已加载
            val piperVersion = "Unknown" // 这里可以添加获取piper版本的方法，如果有的话
            logger.info(" Piper库已加载，版本: $piperVersion")
        } catch (e: Exception) {
            logger.error(" Piper库加载失败: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 初始化语音合成器
     * @param modelPath 模型文件路径
     * @param configPath 配置文件路径
     * @param espeakDataPath espeak数据路径
     * @param speakerId 说话人ID
     * @return 初始化是否成功
     */
    override fun initialize(
        modelPath: String,
        configPath: String,
        espeakDataPath: String,
        speakerId: Int
    ): Boolean {
        _synthesisState.value = SynthesisState.INITIALIZING

        // 检查文件路径是否为空
        if (modelPath.isBlank()) {
            logger.error(" Piper模型路径为空")
            _synthesisState.value = SynthesisState.ERROR
            return false
        }

        if (configPath.isBlank()) {
            logger.error(" Piper配置路径为空")
            _synthesisState.value = SynthesisState.ERROR
            return false
        }

        if (espeakDataPath.isBlank()) {
            logger.error(" espeak数据路径为空")
            _synthesisState.value = SynthesisState.ERROR
            return false
        }

        // 检查文件是否存在
        scope.launch {
            val piperModelExists = checkFileExists(modelPath)
            if (!piperModelExists) {
                logger.error(" Piper模型文件不存在: $modelPath")
            } else {
                logger.info(" Piper模型文件存在: $modelPath")
            }

            val piperConfigExists = checkFileExists(configPath)
            if (!piperConfigExists) {
                logger.error(" Piper配置文件不存在: $configPath")
            } else {
                logger.info(" Piper配置文件存在: $configPath")
            }

            val espeakDataExists = checkDirectoryExists(espeakDataPath)
            if (!espeakDataExists) {
                logger.error(" espeak数据目录不存在: $espeakDataPath")
            } else {
                logger.info("espeak数据目录存在: $espeakDataPath")
            }
        }

        // 初始化Piper语音合成
        logger.info(" 初始化Piper语音合成，模型路径: $modelPath, 配置路径: $configPath, espeak数据路径: $espeakDataPath, 说话人ID: $speakerId")
        try {
            logger.info(" 创建Piper语音合成上下文.......")
            piperContext = piper_wrapper_init(
                espeak_data_path = espeakDataPath,
                model_path = modelPath,
                config_path = configPath,
                speaker_id = speakerId,
                language = "cmn"
            )
            if (piperContext == null) {
                logger.error("Piper初始化失败，返回的上下文为空")
                _synthesisState.value = SynthesisState.ERROR
                return false
            }
            logger.info("Piper语音合成初始化成功")

            _synthesisState.value = SynthesisState.IDLE
            return true
        } catch (e: Exception) {
            logger.error(" Piper初始化异常: ${e.message}")
            e.printStackTrace()
            _synthesisState.value = SynthesisState.ERROR
            return false
        }
    }

    /**
     * 检查文件是否存在
     */
    private fun checkFileExists(path: String): Boolean {
        val file = fopen(path, "r")
        val exists = file != null
        if (file != null) {
            fclose(file)
        }
        return exists
    }

    /**
     * 检查目录是否存在
     */
    private fun checkDirectoryExists(path: String): Boolean {
        val process = popen("test -d \"$path\" && echo 1 || echo 0", "r")
        val buffer = ByteArray(2)
        fread(buffer.refTo(0), 1u, 1u, process)
        val result = buffer[0].toInt().toChar() == '1'
        pclose(process)
        return result
    }

    /**
     * 合成语音
     * @param text 要合成的文本
     * @param outputWav 是否输出wav格式(否则输出raw PCM)
     * @return 合成的音频数据，失败返回空数组
     */
    override fun synthesize(text: String, outputWav: Boolean,sampleRate:Int,channel:Int): ByteArray {
        if (piperContext == null) {
            logger.error("Piper未初始化")
            return ByteArray(0)
        }

        if (text.isBlank()) {
            logger.error("输入文本为空")
            _synthesisState.value = SynthesisState.ERROR
            return ByteArray(0)
        }

        _synthesisState.value = SynthesisState.SYNTHESIZING
        logger.info("开始合成文本: \"$text\" (目标格式: ${sampleRate}Hz/${channel}ch)")
        logger.info("Starting synthesis for text: $text")

        return memScoped {
            val audioBufferVar = alloc<CPointerVar<ShortVar>>()
            val audioLengthVar = alloc<IntVar>()

            // 调用piper合成 - 使用Piper的原生格式：22050Hz/1ch
            val ret = piper_wrapper_text_to_audio(
                context = piperContext,
                text = text,
                audio_buffer = audioBufferVar.ptr,
                audio_length = audioLengthVar.ptr,
                sampleRate = AudioDefaults.PIPER_TTS_SAMPLE_RATE,  // 使用Piper原生采样率22050Hz
                channels = AudioDefaults.PIPER_TTS_CHANNELS        // 使用Piper原生单声道
            )

            if (ret < 0) {
                logger.error("语音合成失败，返回值: $ret")
                _synthesisState.value = SynthesisState.ERROR
                ByteArray(0)
            } else {
                val frameCount = audioLengthVar.value
                val monoBufferPtr = audioBufferVar.value

                if (monoBufferPtr == null) {
                    logger.error(" Piper返回的音频缓冲区为空")
                    _synthesisState.value = SynthesisState.ERROR
                    return@memScoped ByteArray(0)
                }

                // 安全检查：帧数应该是个合理值
                if (frameCount <= 0 || frameCount > 1000000) {
                    logger.error("Piper返回的帧数异常: $frameCount")
                    com.airobot.piperinterop.piper_wrapper_free_audio(monoBufferPtr)
                    _synthesisState.value = SynthesisState.ERROR
                    return@memScoped ByteArray(0)
                }

                logger.info("Piper生成了 $frameCount 帧音频数据 (22050Hz/1ch)")

                try {
                    // 第一步：将Piper原生输出转换为ShortArray (22050Hz/1ch)
                    val piperOutputData = ShortArray(frameCount) { i ->
                        monoBufferPtr[i]
                    }
                    
                    // 第二步：如果目标格式与Piper原生格式不同，进行转换
                    val convertedData = if (sampleRate != AudioDefaults.PIPER_TTS_SAMPLE_RATE || channel != AudioDefaults.PIPER_TTS_CHANNELS) {
                        logger.info(" 需要格式转换: ${AudioDefaults.PIPER_TTS_SAMPLE_RATE}Hz/${AudioDefaults.PIPER_TTS_CHANNELS}ch -> ${sampleRate}Hz/${channel}ch")
                        
                        // 采样率转换 - 改进的重采样算法
                        val resampledData = if (sampleRate != AudioDefaults.PIPER_TTS_SAMPLE_RATE) {
                            logger.info(" 开始采样率转换: ${AudioDefaults.PIPER_TTS_SAMPLE_RATE}Hz -> ${sampleRate}Hz")
                            
                            val srcRate = AudioDefaults.PIPER_TTS_SAMPLE_RATE.toDouble()
                            val dstRate = sampleRate.toDouble()
                            val ratio = dstRate / srcRate
                            val newSize = (frameCount * ratio).toInt()

                            logger.info(" 重采样参数: 输入${frameCount}样本, 输出${newSize}样本, 比率=${"%.4f".format( ratio)}")
                            
                            // 使用改进的重采样算法，支持抗混叠
                            val result = if (ratio > 1.0) {
                                // 上采样：使用线性插值 + 简单抗混叠
                                ShortArray(newSize) { i ->
                                    val srcPos = i / ratio
                                    val srcIndex = srcPos.toInt()
                                    val frac = srcPos - srcIndex
                                    
                                    when {
                                        srcIndex >= frameCount - 1 -> piperOutputData[frameCount - 1]
                                        frac < 0.001 -> piperOutputData[srcIndex]
                                        else -> {
                                            val sample1 = piperOutputData[srcIndex].toInt()
                                            val sample2 = piperOutputData[srcIndex + 1].toInt()
                                            ((sample1 * (1.0 - frac) + sample2 * frac).toInt()).coerceIn(-32768, 32767).toShort()
                                        }
                                    }
                                }
                            } else {
                                // 下采样：使用简单的抗混叠滤波
                                ShortArray(newSize) { i ->
                                    val srcCenter = i / ratio
                                    val srcStart = (srcCenter - 0.5 / ratio).toInt().coerceAtLeast(0)
                                    val srcEnd = (srcCenter + 0.5 / ratio).toInt().coerceAtMost(frameCount - 1)
                                    
                                    if (srcStart == srcEnd) {
                                        piperOutputData[srcStart]
                                    } else {
                                        var sum = 0L
                                        var count = 0
                                        for (j in srcStart..srcEnd) {
                                            sum += piperOutputData[j].toLong()
                                            count++
                                        }
                                        (sum / count).coerceIn(-32768, 32767).toShort()
                                    }
                                }
                            }
                            
                            val maxAmp = result.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
                            val nonZeroCount = result.count { it != 0.toShort() }
                            val zeroRatio = (result.size - nonZeroCount).toFloat() / result.size

                            logger.info(" 重采样完成: 最大振幅=$maxAmp, 非零样本=${nonZeroCount}/${result.size}, 零值比例=${"%.4f".format( zeroRatio)}")
                            
                            result
                        } else {
                            piperOutputData
                        }
                        
                        // 声道转换
                        if (channel != AudioDefaults.PIPER_TTS_CHANNELS) {
                            logger.info(" 开始声道转换: ${AudioDefaults.PIPER_TTS_CHANNELS}ch -> ${channel}ch")
                            
                            when (channel) {
                                2 -> {
                                    // 单声道转立体声
                                    val result = ShortArray(resampledData.size * 2) { i ->
                                        resampledData[i / 2]
                                    }
                                    
                                    val maxAmp = result.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
                                    val nonZeroCount = result.count { it != 0.toShort() }
                                    val zeroRatio = (result.size - nonZeroCount).toFloat() / result.size

                                    logger.info("  声道转换完成: 最大振幅=$maxAmp, 非零样本=${nonZeroCount}/${result.size}, 零值比例=${"%.4f".format( zeroRatio)}")
                                    
                                    result
                                }
                                else -> {
                                    logger.warn("  不支持转换到${channel}声道，保持原格式")
                                    resampledData
                                }
                            }
                        } else {
                            resampledData
                        }
                    } else {
                        logger.info(" 目标格式与Piper原生格式相同，无需转换")
                        piperOutputData
                    }
                    
                    // 第三步：转换为字节数组
                    val byteArray = voice.util.AudioUtils.shortArrayToByteArray(convertedData)

                    logger.info(" 格式转换完成: 输出${convertedData.size}样本, ${byteArray.size}字节")
                    _synthesisState.value = SynthesisState.IDLE
                    byteArray
                } finally {
                    // 确保在任何情况下都释放C侧音频缓冲区
                    com.airobot.piperinterop.piper_wrapper_free_audio(monoBufferPtr)
                    logger.info(" 已释放Piper音频缓冲区")
                }
            }
        }
    }


    /**
     * 播放文本
     * @param text 要播放的文本
     * @return 播放是否成功
     */
    override suspend fun speak(text: String, outputSampleRate: Int, outChannels: Int): Boolean {
        if (isSpeakingFlag) {
            stopSpeaking()
        }
        val audioData = synthesize(text, true, outputSampleRate, outChannels)
        if (audioData.isEmpty()) {
            logger.warn(" 合成返回了空音频数据，无法播放")
            return false
        }

        isSpeakingFlag = true
        _synthesisState.value = SynthesisState.SPEAKING

        try {
            // 确保音频设备已处于活动状态
            if (audioPlayer.deviceState.value != voice.hal.AudioDevice.AudioDeviceState.ACTIVE) {
                val activated = audioPlayer.start()
                if (!activated) {
                    logger.warn(" 无法激活音频设备，但将尝试播放")
                }
            }

            // 使用音频设备的低级API来播放数据
            // 这是硬件交互的关键点，需要特别小心
            val result = mutex.withLock {
                // 直接使用ByteArray播放，避免转换
                audioPlayer.play(audioData, audioData.size)
            }

            if (!result) {
                logger.warn(" 调用音频播放失败，可能是设备被其他进程占用")
                return false
            }

            return true
        } catch (e: Exception) {
            logger.error(" 播放音频时发生异常: ${e.message}")
            e.printStackTrace()
            return false
        } finally {
            // 确保无论如何都重置状态
            isSpeakingFlag = false
            _synthesisState.value = SynthesisState.IDLE
        }
    }

    /**
     * 停止播放
     */
    override fun stopSpeaking() {
        if (isSpeakingFlag) {
            audioPlayer.stopPlayback()
            isSpeakingFlag = false
            _synthesisState.value = SynthesisState.IDLE
        }
    }

    /**
     * 是否正在播放
     */
    override fun isSpeaking(): Boolean {
        return isSpeakingFlag
    }

    /**
     * 释放资源
     */
    override fun release() {
        logger.info(" 释放Piper语音合成器资源")
        try {
            if (isSpeakingFlag) {
                stopSpeaking()
            }

            piper_wrapper_terminate(piperContext)
            piperContext = null
            audioPlayer.release()
            _synthesisState.value = SynthesisState.IDLE
        } catch (e: Exception) {
            logger.error(" 释放Piper资源异常: ${e.message}")
            _synthesisState.value = SynthesisState.ERROR
        }
    }
}