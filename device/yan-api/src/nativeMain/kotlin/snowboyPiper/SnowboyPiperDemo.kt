@file:OptIn(ExperimentalForeignApi::class)

package snowboyPiper

import com.airobot.piperinterop.PIPER_SUCCESS
import com.airobot.piperinterop.PiperAudioFormat
import com.airobot.piperinterop.PiperContext
import com.airobot.piperinterop.PiperVoiceConfig
import com.airobot.piperinterop.piper_free
import com.airobot.piperinterop.piper_init
import com.airobot.piperinterop.piper_synthesize_text
import com.airobot.portaudiointerop.Pa_CloseStream
import com.airobot.portaudiointerop.Pa_GetErrorText
import com.airobot.portaudiointerop.Pa_Initialize
import com.airobot.portaudiointerop.Pa_OpenDefaultStream
import com.airobot.portaudiointerop.Pa_ReadStream
import com.airobot.portaudiointerop.Pa_Sleep
import com.airobot.portaudiointerop.Pa_StartStream
import com.airobot.portaudiointerop.Pa_StopStream
import com.airobot.portaudiointerop.Pa_Terminate
import com.airobot.portaudiointerop.Pa_WriteStream
import com.airobot.portaudiointerop.paFloat32
import com.airobot.portaudiointerop.paInputOverflowed
import com.airobot.portaudiointerop.paInt16
import com.airobot.portaudiointerop.paNoError
import com.airobot.snowboyinterop.SnowboyDetectWrapper
import com.airobot.snowboyinterop.snowboy_create
import com.airobot.snowboyinterop.snowboy_free
import com.airobot.snowboyinterop.snowboy_run_detection_int16
import com.airobot.snowboyinterop.snowboy_set_sensitivity
import kotlinx.cinterop.CArrayPointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.free
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.posix.size_tVar
import kotlin.time.ExperimentalTime

/**
 * Snowboy关键词检测与Piper语音合成Demo
 * 使用PortAudio获取PCM数据，Snowboy检测关键词，Piper播放语音
 */
class SnowboyPiperDemo {
    companion object {
        const val DEFAULT_SAMPLE_RATE = 16000
        const val DEFAULT_CHANNELS = 1
        const val DEFAULT_RESOURCE_PATH = "/usr/local/share/yanshee-model/snowboy/common.res"
        const val DEFAULT_MODEL_PATH = "/usr/local/share/yanshee-model/snowboy/models/snowboy.umdl"
        const val DEFAULT_PIPER_MODEL_PATH =
            "/usr/local/share/yanshee-model/piper/zh_CN-huayan-x_low.onnx"
        const val DEFAULT_PIPER_CONFIG_PATH =
            "/usr/local/share/yanshee-model/piper/zh_CN-huayan-x_low.onnx.json"
    }

    private var streamPtr = nativeHeap.alloc<COpaquePointerVar>()
    private var snowboyDetector: CPointer<SnowboyDetectWrapper>? = null
    private var piperContext: CValuesRef<PiperContext>? = null
    private val _detectionState = MutableStateFlow(DetectionState.IDLE)
    val detectionState: StateFlow<DetectionState> = _detectionState.asStateFlow()
    private var detectionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    enum class DetectionState {
        IDLE, INITIALIZING, LISTENING, DETECTED, ERROR
    }

    /**
     * 初始化Snowboy检测器和Piper语音合成
     */
    fun initialize(
        resourcePath: String = DEFAULT_RESOURCE_PATH,
        modelPath: String = DEFAULT_MODEL_PATH,
        piperModelPath: String = DEFAULT_PIPER_MODEL_PATH,
        piperConfigPath: String = DEFAULT_PIPER_CONFIG_PATH,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        channels: Int = DEFAULT_CHANNELS
    ): Boolean {
        memScoped {
            println("[INFO] 开始初始化Snowboy检测器，资源路径: $resourcePath, 模型路径: $modelPath")
            _detectionState.value = DetectionState.INITIALIZING
            // 初始化Snowboy检测器
            snowboyDetector = snowboy_create(resourcePath, modelPath)
            if (snowboyDetector == null) {
                println("[ERROR] Snowboy检测器创建失败")
                _detectionState.value = DetectionState.ERROR
                return false
            }
            // 设置灵敏度
            snowboy_set_sensitivity(snowboyDetector, "0.5")
            // 初始化Piper语音合成
            val voiceConfig = nativeHeap.alloc<PiperVoiceConfig>().apply {
                this.model_path = piperModelPath.cstr.getPointer(this@memScoped)
                this.config_path = piperConfigPath.cstr.getPointer(this@memScoped)
                this.speaker_id = 0.0f
                this.noise_scale = 0.667f
                this.length_scale = 1.0f
                this.noise_w = 0.8f
            }
            val status = nativeHeap.alloc<IntVar>()
            piperContext = piper_init(voiceConfig.ptr, status.ptr)
            if (piperContext == null || status.value != PIPER_SUCCESS) {
                println("[ERROR] Piper初始化失败，错误码: ${status.value}")
                snowboy_free(snowboyDetector)
                _detectionState.value = DetectionState.ERROR
                return false
            }

            // 初始化PortAudio
            println("[INFO] 初始化PortAudio...")
            if (Pa_Initialize() != paNoError) {
                println("[ERROR] PortAudio初始化失败")
                snowboy_free(snowboyDetector)
                piper_free(piperContext)
                _detectionState.value = DetectionState.ERROR
                return false
            }
            println("[INFO] 初始化完成")
            _detectionState.value = DetectionState.IDLE
            return true
        }
    }


    /**
     * 开始关键词检测
     */
    @OptIn(ExperimentalForeignApi::class)
    fun startDetection() {
        if (_detectionState.value == DetectionState.LISTENING) return
        _detectionState.value = DetectionState.LISTENING
        println("[INFO] 启动关键词检测，准备采集音频流...")

        detectionJob = scope.launch {
            val bufferSize = 2048
            val buffer = nativeHeap.allocArray<ShortVar>(bufferSize)

            // 打开默认音频流
            println("[INFO] 尝试打开默认音频流...")
            val defaultErr = Pa_OpenDefaultStream(
                streamPtr.ptr,
                1,  // 1个输入通道
                0,  // 0个输出通道
                paInt16,
                DEFAULT_SAMPLE_RATE.toDouble(),
                bufferSize.toUInt(),
                null,
                null
            )

            if (defaultErr != paNoError) {
                println("[ERROR] 无法打开默认音频流: ${Pa_GetErrorText(defaultErr)?.toKString()}")
                _detectionState.value = DetectionState.ERROR
                return@launch
            }

            // 开始音频流
            if (Pa_StartStream(streamPtr.value) != paNoError) {
                println("[ERROR] 无法启动音频流")
                Pa_CloseStream(streamPtr.value)
                _detectionState.value = DetectionState.ERROR
                return@launch
            }

            println("[INFO] 音频流已启动，开始检测关键词...")

            try {
                while (isActive) {
                    // 读取音频数据
                    val readErr = Pa_ReadStream(streamPtr.value, buffer, bufferSize.toUInt())
                    if (readErr != paNoError && readErr != paInputOverflowed) {
                        println("[WARN] 读取音频流错误: ${Pa_GetErrorText(readErr)?.toKString()}")
                        delay(100) // 短暂延迟后继续
                        continue
                    }

                    // 使用Snowboy检测关键词
                    val result =
                        snowboy_run_detection_int16(snowboyDetector, buffer, bufferSize, 0)


                    when (result) {
                        -2 -> { /* 静音 */
                        }

                        -1 -> println("[ERROR] Snowboy检测错误")
                        0 -> { /* 无事件 */
                        }

                        else -> {
                            // 检测到关键词
                            println("[INFO] 检测到关键词! 结果: $result")
                            _detectionState.value = DetectionState.DETECTED

                            // 使用Piper播放"你好"
                            playGreeting()

                            // 短暂暂停检测，避免连续触发
                            delay(1000)
                            _detectionState.value = DetectionState.LISTENING
                        }
                    }

                    // 短暂延迟，减少CPU使用
                    delay(10)
                }
            } catch (e: CancellationException) {
                println("[INFO] 检测任务已取消")
            } catch (e: Exception) {
                println("[ERROR] 检测过程中发生错误: ${e.message}")
                _detectionState.value = DetectionState.ERROR
            } finally {
                // 停止并关闭音频流
                Pa_StopStream(streamPtr.value)
                Pa_CloseStream(streamPtr.value)
            }
        }
    }

    /**
     * 使用Piper播放"你好"语音
     */
    private fun playGreeting() {
        scope.launch {
            println("[INFO] 使用Piper播放语音...")

            // 合成"你好"语音
            val text = "你好"
            val format = nativeHeap.alloc<PiperAudioFormat>()
            val outputSizePtr = nativeHeap.alloc<size_tVar>()

            // 首先获取需要的缓冲区大小
            val sizeStatus = piper_synthesize_text(
                piperContext,
                text,
                null,
                outputSizePtr.ptr,
                format.ptr
            )

            if (sizeStatus != PIPER_SUCCESS) {
                println("[ERROR] 无法获取音频大小，错误码: $sizeStatus")
                return@launch
            }

            val outputSize = outputSizePtr.value.toInt()
            val audioBuffer = nativeHeap.allocArray<FloatVar>(outputSize)

            // 合成语音
            val synthStatus = piper_synthesize_text(
                piperContext,
                text,
                audioBuffer,
                outputSizePtr.ptr,
                format.ptr
            )

            if (synthStatus != PIPER_SUCCESS) {
                println("[ERROR] 语音合成失败，错误码: $synthStatus")
                return@launch
            }

            // 播放合成的语音
            playAudio(audioBuffer, outputSize, format.sample_rate)

            // 释放资源
            nativeHeap.free(audioBuffer)
        }
    }

    /**
     * 使用PortAudio播放音频
     */
    private fun playAudio(audioBuffer: CArrayPointer<FloatVar>, bufferSize: Int, sampleRate: Int) {
        val outputStreamPtr = nativeHeap.alloc<COpaquePointerVar>()

        // 打开输出流
        val err = Pa_OpenDefaultStream(
            outputStreamPtr.ptr,
            0,  // 0个输入通道
            1,  // 1个输出通道
            paFloat32,
            sampleRate.toDouble(),
            bufferSize.toUInt(),
            null,
            null
        )

        if (err != paNoError) {
            println("[ERROR] 无法打开音频输出流: ${Pa_GetErrorText(err)?.toKString()}")
            return
        }

        // 开始输出流
        if (Pa_StartStream(outputStreamPtr.value) != paNoError) {
            println("[ERROR] 无法启动音频输出流")
            Pa_CloseStream(outputStreamPtr.value)
            return
        }

        // 写入音频数据
        val writeErr = Pa_WriteStream(outputStreamPtr.value, audioBuffer, bufferSize.toUInt())
        if (writeErr != paNoError) {
            println("[ERROR] 写入音频数据失败: ${Pa_GetErrorText(writeErr)?.toKString()}")
        }

        // 等待所有数据播放完成
        Pa_Sleep(1000)

        // 停止并关闭输出流
        Pa_StopStream(outputStreamPtr.value)
        Pa_CloseStream(outputStreamPtr.value)
    }

    /**
     * 停止关键词检测
     */
    fun stopDetection() {
        println("[INFO] 停止关键词检测...")
        detectionJob?.cancel()
        detectionJob = null
        _detectionState.value = DetectionState.IDLE
    }

    /**
     * 释放资源
     */
    fun release() {
        println("[INFO] 释放资源...")
        stopDetection()

        // 释放Snowboy资源
        snowboyDetector?.let { snowboy_free(it) }
        snowboyDetector = null

        // 释放Piper资源
        piperContext?.let { piper_free(it) }
        piperContext = null

        // 终止PortAudio
        Pa_Terminate()

        println("[INFO] 资源已释放")
    }
}
