@file:OptIn(ExperimentalForeignApi::class, ExperimentalStdlibApi::class, ExperimentalTime::class)
package voskPortaudio

import com.airobot.portaudiointerop.*
import com.airobot.voskinterop.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.time.Clock.System
import kotlin.time.ExperimentalTime

typealias PaStream = COpaquePointerVar
/**
 * 基于PortAudio的语音采集与Vosk识别模块骨架
 */
class YanVoskPortaudioSpeechRecognizer {
    companion object {
        const val DEFAULT_SAMPLE_RATE = 16000
        const val DEFAULT_CHANNELS = 1
        const val DEFAULT_MODEL_PATH = "/usr/local/share/yanshee-model/vosk-model-small-cn-0.22"
    }
    private var streamPtr = nativeHeap.alloc<COpaquePointerVar>()
    private var recognizer: CPointer<VoskRecognizer>? = null
    private var model: CPointer<VoskModel>? = null
    private val _recognitionState = MutableStateFlow(RecognitionState.IDLE)
    val recognitionState: StateFlow<RecognitionState> = _recognitionState.asStateFlow()
    private val _recognitionResult = MutableStateFlow<String?>(null)
    val recognitionResult: StateFlow<String?> = _recognitionResult.asStateFlow()
    private var recognitionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    enum class RecognitionState {
        IDLE, INITIALIZING, LISTENING, PROCESSING, ERROR
    }

    fun initialize(modelPath: String = DEFAULT_MODEL_PATH, sampleRate: Int = DEFAULT_SAMPLE_RATE, channels: Int = DEFAULT_CHANNELS): Boolean {
        println("[INFO] 开始初始化Vosk模型，路径: $modelPath")
        _recognitionState.value = RecognitionState.INITIALIZING

        // 初始化Vosk模型
        model = vosk_model_new(modelPath)
        if (model == null) {
            println("[ERROR] Vosk模型加载失败，路径: $modelPath")
            _recognitionState.value = RecognitionState.ERROR
            return false
        }

        // 使用原始的函数创建识别器
        recognizer = vosk_recognizer_new(model, sampleRate.toFloat())
        if (recognizer == null) {
            println("[ERROR] Vosk识别器创建失败")
            vosk_model_free(model)
            _recognitionState.value = RecognitionState.ERROR
            return false
        }

        // 可选：如果这些函数在你的Vosk库中可用，可以设置一些参数
        try {
            // 设置词级时间戳 - 如果函数存在
            vosk_recognizer_set_words(recognizer, 1)
        } catch (e: Exception) {
            println("[WARN] 无法设置词级时间戳: ${e.message}")
        }

        // 初始化PortAudio
        println("[INFO] 初始化PortAudio...")
        if (Pa_Initialize() != paNoError) {
            println("[ERROR] PortAudio初始化失败")
            vosk_recognizer_free(recognizer)
            vosk_model_free(model)
            _recognitionState.value = RecognitionState.ERROR
            return false
        }

        println("[INFO] 初始化完成")
        _recognitionState.value = RecognitionState.IDLE
        return true
    }


    fun startRecognition() {
        if (_recognitionState.value == RecognitionState.LISTENING) return
        _recognitionState.value = RecognitionState.LISTENING
        println("[INFO] 启动语音识别，准备采集音频流...")

        recognitionJob = scope.launch {
            val bufferSize = 2048
            val buffer = nativeHeap.allocArray<ShortVar>(bufferSize)

            // 使用默认流配置
            println("[INFO] 尝试打开默认音频流...")
            val defaultErr = Pa_OpenDefaultStream(
                streamPtr.ptr,
                1,  // 1个输入通道
                0,  // 0个输出通道
                paInt16,
                16000.0,  // 16kHz采样率
                bufferSize.convert(),
                null,
                null
            )

            if (defaultErr != paNoError) {
                val defaultErrorText = Pa_GetErrorText(defaultErr)?.toKString() ?: "Unknown error"
                println("[ERROR] 打开默认音频流失败，错误码: $defaultErr, 错误: $defaultErrorText")
                _recognitionState.value = RecognitionState.ERROR
                return@launch
            }

            println("[INFO] 音频流打开成功，开始采集...")
            Pa_StartStream(streamPtr.value)

            // 启动后等待一小段时间
            delay(100)

            // 添加音频检测变量
            var totalSamples = 0
            var maxAmplitude = 0
            var startTime = System.now().toEpochMilliseconds()

            while (isActive && _recognitionState.value == RecognitionState.LISTENING) {
                try {
                    val read = Pa_ReadStream(streamPtr.value, buffer, bufferSize.convert())

                    if (read == paNoError) {
                        // 检测音频振幅
                        var currentMaxAmplitude = 0
                        for (i in 0 until bufferSize) {
                            val amplitude = buffer[i].toInt()
                            if (amplitude.absoluteValue > currentMaxAmplitude) {
                                currentMaxAmplitude = amplitude.absoluteValue
                            }
                        }

                        // 每50个缓冲区打印一次音频状态
                        totalSamples += bufferSize
                        if (totalSamples % (bufferSize * 50) == 0) {
                            val currentTime =  System.now().toEpochMilliseconds()
                            val elapsedSeconds = (currentTime - startTime) / 1000.0
                            val samplesPerSecond = totalSamples / elapsedSeconds
                            println("[INFO] 音频状态: 已处理 $totalSamples 样本，当前振幅: $currentMaxAmplitude，采样率: $samplesPerSecond 样本/秒")

                            if (currentMaxAmplitude > maxAmplitude) {
                                maxAmplitude = currentMaxAmplitude
                                println("[INFO] 检测到新的最大振幅: $maxAmplitude")
                            }
                        }

                        // 处理音频数据
                        val result = vosk_recognizer_accept_waveform_s(
                            recognizer,
                            buffer,
                            (bufferSize * 2).convert()
                        )

                        if (result == 1) {
                            val resStr = vosk_recognizer_result(recognizer)?.toKString()
                            println("[DEBUG] 完整识别结果: $resStr")
                            _recognitionResult.value = resStr
                        } else {
                            val partial = vosk_recognizer_partial_result(recognizer)?.toKString()
                            println("[DEBUG] 部分识别结果: $partial")
                            _recognitionResult.value = partial
                        }

                    } else if (read == -9981) {  // Input overflow
                        println("[WARN] 读取音频流失败，错误码: $read，错误: Input overflowed，尝试继续...")

                        // 清空部分缓冲区
                        val available = Pa_GetStreamReadAvailable(streamPtr.value)
                        if (available > 0) {
                            val flushSize = minOf(available, bufferSize)
                            Pa_ReadStream(streamPtr.value, buffer, flushSize.convert())
                        }

                        delay(10)

                    } else {
                        val errorText = Pa_GetErrorText(read)?.toKString() ?: "Unknown error"
                        println("[WARN] 读取音频流失败，错误码: $read，错误: $errorText，尝试继续...")
                        delay(10)
                    }

                    yield()

                } catch (e: CancellationException) {
                    println("[INFO] 识别协程被取消")
                    break
                } catch (e: Exception) {
                    println("[ERROR] 读取或处理音频时出错: ${e.message}")
                    e.printStackTrace()
                    delay(100)
                }
            }

            println("[INFO] 识别循环结束，清理资源...")

            try {
                if (streamPtr.value != null) {
                    Pa_StopStream(streamPtr.value)
                    Pa_CloseStream(streamPtr.value)
                }
            } catch (e: Exception) {
                println("[WARN] 关闭音频流时出错: ${e.message}")
            }

            try { nativeHeap.free(buffer) } catch (_: Exception) {}
        }
    }

    fun stopRecognition() {
        // 取消协程但不等待，避免死锁
        recognitionJob?.cancel()
        // 使用非阻塞方式等待短暂时间让协程有机会清理
        runBlocking {
            withTimeoutOrNull(1000L) {
                try {
                    recognitionJob?.join()
                } catch (e: CancellationException) {
                    // 忽略取消异常，这是预期的
                    println("[INFO] 识别协程已取消")
                }
            }
        }
        recognitionJob = null
        if (_recognitionState.value != RecognitionState.ERROR) {
            _recognitionState.value = RecognitionState.IDLE
        }
    }

    fun release() {
        stopRecognition()
        try { recognizer?.let { vosk_recognizer_free(it) } } catch (_: Exception) {}
        try { model?.let { vosk_model_free(it) } } catch (_: Exception) {}
        try { nativeHeap.free(streamPtr.rawPtr) } catch (_: Exception) {}
        try { Pa_Terminate() } catch (_: Exception) {}
        _recognitionState.value = RecognitionState.IDLE
    }
}