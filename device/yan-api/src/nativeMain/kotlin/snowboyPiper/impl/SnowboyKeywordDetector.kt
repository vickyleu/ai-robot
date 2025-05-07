@file:OptIn(
    ExperimentalForeignApi::class, ExperimentalTime::class, ExperimentalTime::class,
    ExperimentalForeignApi::class
)

package snowboyPiper.impl

import com.airobot.device.yanapi.snowboyPiper.config.VoiceAssistantConfig
import com.airobot.device.yanapi.snowboyPiper.interfaces.AudioAnalyzer
import com.airobot.device.yanapi.snowboyPiper.interfaces.VoiceStateManager
import com.airobot.piperinterop.SOXR_FLOAT32_I
import com.airobot.piperinterop.soxr_io_spec_create
import com.airobot.piperinterop.soxr_quality_spec_create
import com.airobot.piperinterop.soxr_wrapper_create
import com.airobot.piperinterop.soxr_wrapper_create_resampler
import com.airobot.piperinterop.soxr_wrapper_destroy
import com.airobot.piperinterop.soxr_wrapper_process
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
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import platform.posix.perror
import platform.posix.stat
import snowboyPiper.impl.VoskSpeechService.Companion.executeCommand
import snowboyPiper.interfaces.AudioPlayer
import snowboyPiper.interfaces.KeywordDetector
import snowboyPiper.interfaces.KeywordDetector.DetectorState
import snowboyPiper.interfaces.KeywordDetector.DetectorState.*
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

    // 存储初始化参数，用于可能的重新初始化
    private var lastResourcePath = ""
    private var lastModelPath = ""
    private var lastSensitivity = VoiceAssistantConfig.snowboySensitivity

    /**
     * 初始化检测器
     * @param resourcePath 资源文件路径
     * @param modelPath 模型文件路径
     * @param sensitivity 灵敏度，范围0-1
     * @return 初始化是否成功
     */
    override fun initialize(resourcePath: String, modelPath: String, sensitivity: Float): Boolean {
        // 保存初始化参数，用于可能的重新初始化
        lastResourcePath = resourcePath
        lastModelPath = modelPath
        lastSensitivity = sensitivity

        _detectionState.value = KeywordDetector.DetectionState.INITIALIZING

        // 检查文件是否存在
        scope.launch {
            val checkResourceCmd = "test -f $resourcePath && echo 'exists' || echo 'not exists'"
            val resourceExists = executeCommand(checkResourceCmd).trim() == "exists"
            if (!resourceExists) {
                println("[WARN] Snowboy资源文件不存在: $resourcePath")
            }

            val checkModelCmd = "test -f $modelPath && echo 'exists' || echo 'not exists'"
            val modelExists = executeCommand(checkModelCmd).trim() == "exists"
            if (!modelExists) {
                println("[WARN] Snowboy模型文件不存在: $modelPath")
            } else {
                // 在初始化方法中添加
                println("加载模型: ${modelPath}")
                val fileInfo = nativeHeap.alloc<stat>()
                if (stat(modelPath, fileInfo.ptr) != 0) {
                    perror("模型文件访问失败")
                    nativeHeap.free(fileInfo.rawPtr)
                    return@launch
                }
                println("模型文件大小: ${fileInfo.st_size} bytes")
                nativeHeap.free(fileInfo.rawPtr)
            }
        }

        // 初始化Snowboy检测器
        println("[INFO] 创建Snowboy检测器...")
        try {
            snowboyDetector = snowboy_create(resourcePath, modelPath)
            if (snowboyDetector == null) {
                println("[ERROR] Snowboy检测器创建失败")
                _detectionState.value = KeywordDetector.DetectionState.ERROR
                return false
            }
            // 提高灵敏度以增加检测成功率
            // 灵敏度范围为0-1，值越高越容易检测到关键词，但可能增加误检率
            // 调整为0.98以进一步提高检测率
            println("[INFO] 设置灵敏度 ${sensitivity}）...") // 1.0f
            snowboy_set_sensitivity(snowboyDetector, sensitivity.toString())
            snowboy_set_audio_gain(snowboyDetector, 1f)
            snowboy_apply_frontend(snowboyDetector, 0)
            // 验证灵敏度设置是否生效
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
            val bufferPtr = nativeHeap.allocArray<ShortVar>(frameCount)
            // 1. 动态范围压缩 - 提升小信号，压缩大信号
//            fun compressAudio(audio: ShortArray, threshold: Int = 8000, ratio: Float = 0.7f): ShortArray {
//                return ShortArray(audio.size) { i ->
//                    val sample = audio[i]
//                    if (abs(sample.toInt()) > threshold) {
//                        val compressed = threshold + ((abs(sample.toInt()) - threshold) * ratio)
//                        (if (sample > 0) compressed else -compressed).toInt().toShort()
//                    } else {
//                        // 轻微放大较低信号，确保捕捉到更安静的声音
//                        (sample.toInt() * 1.2).toInt().toShort()
//                    }
//                }
//            }
//
//            // 2. 应用于检测前
//            val processedData = compressAudio(buffer)
            // 复制音频数据到本地内存
            for (i in 0 until frameCount) {
                bufferPtr[i] = buffer[i]
            }
            // 检测当前帧是否有语音活动
            val hasVoice = audioAnalyzer.hasVoiceActivity(buffer)
            println("[DEBUG] 检测到语音活动: $hasVoice")
            // 当没有检测到语音活动时，将is_end设为1表示语音结束
            // 当检测到语音活动时，将is_end设为0表示语音未结束，继续处理
            // 修改后的代码
//            val isSpeechEnd = voiceStateManager.isSilenceThresholdReached(voiceStateManager.silenceFramesThreshold) && voiceStateManager.speechStarted
            val is_end = /*if (isSpeechEnd) 1 else*/ 0
            if (hasVoice) {
                // 在初始化后立即获取并打印采样率要求
                val requiredSampleRate = snowboy_sample_rate(snowboyDetector)
                val requiredChannels = snowboy_num_channels(snowboyDetector)
                val requiredBitsPerSample = snowboy_bits_per_sample(snowboyDetector)
                println("Snowboy要求：采样率=$requiredSampleRate Hz, 通道数=$requiredChannels, 位深=$requiredBitsPerSample bit")
                println("当前音频：采样率=$sampleRate Hz, 通道数=$channels, 位深=${16} bit")
                // 检查采样率和通道数是否匹配
                val (bufferTransPtr, outputSize) = if (sampleRate != requiredSampleRate || channels != requiredChannels) {
                     val bufferTrans = transcoding(frameCount, bufferPtr, sampleRate, requiredSampleRate)
                    if(bufferTrans==null){
                        nativeHeap.free(bufferPtr.rawValue)
                        return  ERROR
                    }
                    bufferTrans
                }
                else {
                    // 直接使用当前音频数据进行检测
                    println("[DEBUG] 音频数据符合要求，开始检测")
                    bufferPtr to frameCount
                }
                // 执行检测
                val result = DetectorState.fromValue(
                    snowboy_run_detection_int16(
                        snowboyDetector,
                        bufferTransPtr,
                        outputSize,
                        is_end = is_end
                    )
                ).apply {
                    println("[DEBUG] 检测结果: ${this.name}")
                }
                // 释放本地内存
                nativeHeap.free(bufferTransPtr.rawValue)
                // 处理检测结果
                when (result) {
                    Silence -> {
                        // 检测到静音，可能是背景噪声或无声
                        println("[DEBUG] 检测到静音")
                        _detectionState.value = KeywordDetector.DetectionState.LISTENING
                    }

                    ERROR -> {
                        // 检测器错误，可能是初始化失败或其他问题
                        println("[ERROR] 检测器错误")
                        // 错误情况，应用去抖动机制避免频繁报错
                        val currentTime = Clock.System.now().toEpochMilliseconds()
                        if (currentTime - lastDetectionTime > debounceTimeMs) {
                            println("[WARN] 关键词检测错误，错误码: $result")
                            // 尝试重新初始化检测器
                            scope.launch {
                                println("[INFO] 尝试重新初始化Snowboy检测器...")
                                // 先释放资源
                                snowboyDetector?.let {
                                    snowboy_free(it)
                                }
                                snowboyDetector = null

                                // 短暂延迟后重新初始化
                                kotlinx.coroutines.delay(1000)
                                // 使用类成员变量存储初始化参数，避免硬编码路径
                                if (lastResourcePath.isNotEmpty() && lastModelPath.isNotEmpty()) {
                                    val success =
                                        initialize(lastResourcePath, lastModelPath, lastSensitivity)
                                    if (!success) {
                                        println("[ERROR] 检测器重新初始化失败")
                                    }
                                }
                            }
                            lastDetectionTime = currentTime
                        }
                        _detectionState.value = KeywordDetector.DetectionState.ERROR
                    }

                    NoEvent -> {
                        // 没有检测到关键词，继续监听
                        println("[DEBUG] 没有检测到关键词 $outputSize")
                        // 检查并清理语音活动和缓冲区状态，防止重复播放
                        voiceStateManager.reset()
                        _detectionState.value = KeywordDetector.DetectionState.LISTENING
                    }

                    Hotword1Triggered,
                    Hotword2Triggered,
                    Hotword3Triggered -> {
                        // 检测到关键词，更新状态
                        println("[DEBUG] 检测到关键词")
                        val currentTime = Clock.System.now().toEpochMilliseconds()
                        if (currentTime - lastDetectionTime > debounceTimeMs) {
                            println("[INFO] 检测到关键词！结果值: $result")
                            _detectionState.value = KeywordDetector.DetectionState.DETECTED
                            lastDetectionTime = currentTime
                        }
                    }
                }
                return result
            } else {
                // 释放本地内存
                nativeHeap.free(bufferPtr.rawValue)
                // 没有检测到语音活动，继续监听
                println("[DEBUG] 没有检测到语音活动，继续监听")
                _detectionState.value = KeywordDetector.DetectionState.LISTENING
                return NoEvent
            }
        } catch (e: Exception) {
            println("[ERROR] 关键词检测异常: ${e.message}")
            _detectionState.value = KeywordDetector.DetectionState.ERROR
            return ERROR
        }
    }

    private fun transcoding(
        frameCount: Int,
        bufferPtr: CArrayPointer<ShortVar>,
        sampleRate: Int,
        requiredSampleRate: Int
    ): Pair<CArrayPointer<ShortVar>,Int>? {
        // 使用soxr c api 进行音频转换
        println("[WARN] 采样率或通道数不匹配，使用soxr进行转换\n")
        // 1. 将short转换为float用于soxr处理（可选，取决于您的soxr配置）
        val floatInput = nativeHeap.allocArray<FloatVar>(frameCount)
        for (i in 0 until frameCount) {
            floatInput[i] = (bufferPtr[i]).toFloat() / 32768.0f
        }
        // 2. 计算输出缓冲区大小
        val outputSize =
            ((frameCount.toDouble() * sampleRate) / requiredSampleRate + 0.5).toInt()
        val floatOutput = nativeHeap.allocArray<FloatVar>(outputSize)
        // 3. 配置soxr
        val wrapper = soxr_wrapper_create()
        if (wrapper == null) {
            println("[ERROR] 音频转码失败")
            return null
        }
        soxr_io_spec_create(SOXR_FLOAT32_I, SOXR_FLOAT32_I, wrapper)
        soxr_quality_spec_create(SOXR_FLOAT32_I, wrapper) // 使用最高质量
        // 4. 创建soxr重采样器
        soxr_wrapper_create_resampler(
            wrapper, sampleRate.toDouble(), sampleRate.toDouble()
        )
        if (wrapper.pointed.soxr == null) {
            println("[ERROR] 创建soxr失败")
            return null
        }
        // 5. 执行重采样
        soxr_wrapper_process(
            wrapper,
            in_data = bufferPtr,
            in_size = frameCount.toUInt(),
            out_data = floatOutput,
            out_size = outputSize.toUInt(),
        )
        val resampledBuffer = nativeHeap.allocArray<ShortVar>(outputSize)
        // 6. 将float转换回short
        for (i in 0 until outputSize) {
            var sample = floatOutput[i]
            // 限制在[-1.0, 1.0]范围内防止截断失真
            if (sample > 1.0f) sample = 1.0f
            if (sample < -1.0f) sample = -1.0f
            resampledBuffer[i] = (sample * 32767.0f).toInt().toShort()
        }
        // 7. 释放soxr资源
        nativeHeap.free(floatInput.rawValue)
        nativeHeap.free(floatOutput.rawValue)
        soxr_wrapper_destroy(wrapper)
        println("[INFO] 音频转换完成，输出大小: $outputSize 样本")
        return resampledBuffer to outputSize
    }



    /**
     * 释放资源
     */
    override fun release() {
        try {
            snowboyDetector?.let {
                snowboy_free(it)
                println("[INFO] Snowboy资源已释放")
            }
            snowboyDetector = null
            _detectionState.value = KeywordDetector.DetectionState.IDLE
        } catch (e: Exception) {
            println("[WARN] 释放Snowboy资源时出错: ${e.message}")
            _detectionState.value = KeywordDetector.DetectionState.ERROR
        }
    }
}