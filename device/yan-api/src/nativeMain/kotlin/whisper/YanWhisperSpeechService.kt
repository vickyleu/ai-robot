@file:OptIn(ExperimentalForeignApi::class, ExperimentalStdlibApi::class, NativeRuntimeApi::class)
@file:Suppress("FunctionName", "unused", "UNUSED_PARAMETER")

package whisper

import com.airobot.alsainterop.EAGAIN
import com.airobot.alsainterop.EBADFD
import com.airobot.alsainterop.EBUSY
import com.airobot.alsainterop.EINTR
import com.airobot.alsainterop.EINVAL
import com.airobot.alsainterop.EIO
import com.airobot.alsainterop.ENODEV
import com.airobot.alsainterop.ENOENT
import com.airobot.alsainterop.ENOMEM
import com.airobot.alsainterop.ENOSYS
import com.airobot.alsainterop.EPIPE
import com.airobot.alsainterop.ESTRPIPE
import com.airobot.alsainterop.SND_PCM_ACCESS_RW_INTERLEAVED
import com.airobot.alsainterop.SND_PCM_FORMAT_FLOAT_LE
import com.airobot.alsainterop.SND_PCM_STATE_DISCONNECTED
import com.airobot.alsainterop.SND_PCM_STATE_DRAINING
import com.airobot.alsainterop.SND_PCM_STATE_OPEN
import com.airobot.alsainterop.SND_PCM_STATE_PAUSED
import com.airobot.alsainterop.SND_PCM_STATE_PREPARED
import com.airobot.alsainterop.SND_PCM_STATE_RUNNING
import com.airobot.alsainterop.SND_PCM_STATE_SETUP
import com.airobot.alsainterop.SND_PCM_STATE_SUSPENDED
import com.airobot.alsainterop.SND_PCM_STATE_XRUN
import com.airobot.alsainterop.SND_PCM_STREAM_CAPTURE
import com.airobot.alsainterop._snd_pcm
import com.airobot.alsainterop._snd_pcm_state
import com.airobot.alsainterop.snd_pcm_close
import com.airobot.alsainterop.snd_pcm_drain
import com.airobot.alsainterop.snd_pcm_hw_params
import com.airobot.alsainterop.snd_pcm_hw_params_any
import com.airobot.alsainterop.snd_pcm_hw_params_free
import com.airobot.alsainterop.snd_pcm_hw_params_get_buffer_size
import com.airobot.alsainterop.snd_pcm_hw_params_get_period_size
import com.airobot.alsainterop.snd_pcm_hw_params_malloc
import com.airobot.alsainterop.snd_pcm_hw_params_set_access
import com.airobot.alsainterop.snd_pcm_hw_params_set_buffer_size_near
import com.airobot.alsainterop.snd_pcm_hw_params_set_channels
import com.airobot.alsainterop.snd_pcm_hw_params_set_format
import com.airobot.alsainterop.snd_pcm_hw_params_set_period_size_near
import com.airobot.alsainterop.snd_pcm_hw_params_set_rate_near
import com.airobot.alsainterop.snd_pcm_hw_params_t
import com.airobot.alsainterop.snd_pcm_open
import com.airobot.alsainterop.snd_pcm_prepare
import com.airobot.alsainterop.snd_pcm_recover
import com.airobot.alsainterop.snd_pcm_state
import com.airobot.alsainterop.snd_pcm_sw_params
import com.airobot.alsainterop.snd_pcm_sw_params_current
import com.airobot.alsainterop.snd_pcm_sw_params_free
import com.airobot.alsainterop.snd_pcm_sw_params_malloc
import com.airobot.alsainterop.snd_pcm_sw_params_set_avail_min
import com.airobot.alsainterop.snd_pcm_sw_params_set_start_threshold
import com.airobot.alsainterop.snd_pcm_sw_params_t
import com.airobot.alsainterop.snd_strerror
import com.airobot.device.yanapi.whisper.whisper_init_from_file_with_params
import com.airobot.device.yanapi.whisper.whisper_init_with_params
import com.airobot.whisperinterop.whisper_context
import com.airobot.whisperinterop.whisper_context_params
import com.airobot.whisperinterop.whisper_free
import com.airobot.whisperinterop.whisper_model_loader
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.cValuesOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import vosk.YanVoskSpeechService.Companion.executeCommand
import kotlin.concurrent.AtomicInt
import kotlin.native.runtime.NativeRuntimeApi

/**
 * YAN设备Whisper语音服务
 *
 * 该类封装了YanWhisperSpeechRecognizer（待实现），提供了更高级别的API接口，
 * 使得在Kotlin Native环境中更容易使用ALSA麦克风采集和Whisper语音识别功能。
 */
class YanWhisperSpeechService {
    companion object {
        // Whisper模型的默认路径或标识符
        const val DEFAULT_WHISPER_MODEL = "ggml-base-q4_1.bin" // Placeholder, adjust as needed

    }

    // ALSA & Whisper Resources
    private var pcmHandle: CPointer<_snd_pcm>? = null
    private var whisperContext: CPointer<whisper_context>? = null
    private var isInitialized = false // Track initialization status

    // ALSA Config (Defaults, can be overridden in initialize)
    private var deviceName = "default"
    private var sampleRate = 16000      // Whisper requires 16kHz
    private var channels = 1           // Whisper requires mono
    private var bufferSize = 8192       // ALSA buffer size in frames
    private var periodSize = 2048       // ALSA period size in frames
    private var periods = 4             // ALSA periods
    private val audioFormat = SND_PCM_FORMAT_FLOAT_LE // Whisper uses 32-bit float

    // Error Recovery Config
    private var maxErrorRetries = 10
    private var errorRecoveryDelay = 1000L
    private var brokenPipeRetryDelay = 2000L

    // Control Flags
    private val isRunning = AtomicInt(0)

    // State Management
    private val _recognitionState = MutableStateFlow(RecognitionState.IDLE)
    val recognitionState: StateFlow<RecognitionState> = _recognitionState.asStateFlow()

    // 协程作用域和任务
    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private var recognitionJob: Job? = null

    // 识别结果流
    private val _recognitionText = MutableStateFlow<String?>(null)
    val recognitionText: StateFlow<String?> = _recognitionText.asStateFlow()

    // Recognition State Enum (similar to Vosk)
    enum class RecognitionState {
        IDLE,        // Idle state
        INITIALIZING,// Initializing ALSA or Whisper
        LISTENING,   // Listening for audio
        PROCESSING,  // Processing audio data
        ERROR        // Error state
    }

    /**
     * 初始化语音服务
     *
     * @param deviceName ALSA设备名称 (可能不需要，取决于Whisper实现)
     * @param modelPath Whisper模型路径
     * @param sampleRate 音频采样率 (Whisper通常需要16000Hz)
     * @param micVolume 麦克风音量 (可能由底层处理)
     * @return 初始化是否成功
     */
    fun initialize(
        deviceName: String = "default", // May not be needed for Whisper
        modelPath: String = DEFAULT_WHISPER_MODEL,
        sampleRate: Int = 16000,
        micVolume: Int = 80 // May be handled by ALSA/audio layer
    ): Boolean {
        if (isInitialized) {
            println("[INFO] Whisper service already initialized.")
            return true
        }

        com.airobot.openccinterop.opencc_open()
        _recognitionState.value = RecognitionState.INITIALIZING
        println("Initializing Whisper Speech Service...")

        this.deviceName = deviceName
        // Whisper requires 16000 Hz, mono. Override provided sampleRate/channels if different.
        if (sampleRate != 16000) {
            println("[WARN] Whisper requires a sample rate of 16000 Hz. Overriding provided rate ($sampleRate Hz).")
            this.sampleRate = 16000
        }
        // Assuming mono channel is required by Whisper
        this.channels = 1

        val actualModelPath = "/usr/local/share/yanshee-model/$modelPath" // Adjust path as needed
        println("Model path: $actualModelPath")
        println("ALSA Device: $deviceName, Sample rate: ${this.sampleRate} Hz, Channels: ${this.channels}")

        // Initialize Whisper first, then ALSA
        if (!initWhisper(actualModelPath)) {
            println("[ERROR] Failed to initialize Whisper.")
            _recognitionState.value = RecognitionState.ERROR
            return false
        }

        if (!initAlsa()) {
            println("[ERROR] Failed to initialize ALSA.")
            releaseWhisper() // Clean up Whisper if ALSA fails
            _recognitionState.value = RecognitionState.ERROR
            return false
        }

        isInitialized = true
        _recognitionState.value = RecognitionState.IDLE
        println("Whisper Speech Service initialized successfully.")
        return true
    }

    /**
     * Initializes the Whisper model and context.
     *
     * @param modelPath Path to the Whisper model file.
     * @return True if initialization was successful, false otherwise.
     */
    private fun initWhisper(modelPath: String): Boolean {
        if (whisperContext != null) return true
        println("[WHISPER] Initializing Whisper context from model: $modelPath")
        memScoped {
            // Initialize context parameters with default values
            val loaderPtr = alloc<whisper_model_loader>().apply {
                // 初始化字段
                this.read
            }
            val cparamPtr = alloc<whisper_context_params>().apply {
                // 初始化字段
            }
            val contextForParam =
                whisper_init_with_params(loader = loaderPtr.ptr, params = cparamPtr.readValue())
            if (contextForParam == null) {
                println("[ERROR] Failed to initialize whisper context parameters.")
                return false
            }
            whisperContext = whisper_init_from_file_with_params(modelPath, cparamPtr.ptr)
            if (whisperContext == null) {
                println("[ERROR] Failed to load Whisper model from path: $modelPath")
                return false
            }
        }
        println("[WHISPER] Whisper context initialized successfully.")
        return true
    }

    /**
     * Initializes ALSA audio capture.
     *
     * @return True if initialization was successful, false otherwise.
     */
    private fun initAlsa(): Boolean {
        if (pcmHandle != null) {
            // ALSA already initialized, check state and potentially recover/re-prepare
            val state = snd_pcm_state(pcmHandle)
            println("[ALSA] ALSA already initialized. Current state: ${getPcmStateName(state)}")
            if (state == SND_PCM_STATE_XRUN) {
                println("[ALSA] PCM state is XRUN, attempting recovery...")
                val recoverResult = snd_pcm_recover(pcmHandle, -EPIPE, 1)
                if (recoverResult < 0) {
                    println("[ERROR] ALSA recovery failed: ${snd_strerror(recoverResult)?.toKString()}. Re-initializing.")
                    releaseAlsa()
                } else {
                    println("[ALSA] Recovery successful. Preparing PCM.")
                    val prepResult = snd_pcm_prepare(pcmHandle)
                    if (prepResult < 0) {
                        println(
                            "[ERROR] Failed to prepare PCM after recovery: ${
                                snd_strerror(
                                    prepResult
                                )?.toKString()
                            }. Re-initializing."
                        )
                        releaseAlsa()
                    } else {
                        return true // Successfully recovered and prepared
                    }
                }
            } else if (state == SND_PCM_STATE_PREPARED || state == SND_PCM_STATE_RUNNING) {
                println("[ALSA] PCM state is good (${getPcmStateName(state)}). Ensuring prepared.")
                val prepResult = snd_pcm_prepare(pcmHandle)
                if (prepResult < 0) {
                    println("[WARN] Failed to re-prepare PCM: ${snd_strerror(prepResult)?.toKString()}. Continuing, but might indicate issues.")
                }
                return true // Already initialized and in a usable state
            } else {
                println("[ALSA] PCM state is ${getPcmStateName(state)}. Re-initializing.")
                releaseAlsa()
            }
        }

        println("[ALSA] Initializing ALSA audio capture: device=$deviceName, rate=$sampleRate, format=$audioFormat, channels=$channels")
        memScoped {
            val pcmHandlePtr = allocPointerTo<_snd_pcm>()
            var err = snd_pcm_open(
                pcmHandlePtr.ptr,
                deviceName,
                SND_PCM_STREAM_CAPTURE,
                0
            ) // Blocking mode for init
            if (err < 0) {
                println("[ERROR] Failed to open ALSA device '$deviceName': ${snd_strerror(err)?.toKString()}")
                if (deviceName != "default") {
                    println("[ALSA] Retrying with 'default' device...")
                    deviceName = "default"
                    err = snd_pcm_open(pcmHandlePtr.ptr, deviceName, SND_PCM_STREAM_CAPTURE, 0)
                    if (err < 0) {
                        println("[ERROR] Failed to open default ALSA device: ${snd_strerror(err)?.toKString()}")
                        return false
                    }
                } else {
                    return false
                }
            }
            pcmHandle = pcmHandlePtr.value
                ?: run { println("[ERROR] pcmHandle is null after open"); return false }

            // Allocate and initialize hardware parameters
            val hwParamsPtr = allocPointerTo<snd_pcm_hw_params_t>()
            err = snd_pcm_hw_params_malloc(hwParamsPtr.ptr)
            if (err < 0) {
                println("[ERROR] Failed to allocate hw params: ${snd_strerror(err)?.toKString()}")
                releaseAlsa()
                return false
            }
            val hwParams = hwParamsPtr.value
                ?: run { println("[ERROR] hwParams is null after malloc"); releaseAlsa(); return false }

            err = snd_pcm_hw_params_any(pcmHandle, hwParams)
            if (err < 0) {
                println("[ERROR] Failed to initialize hw params: ${snd_strerror(err)?.toKString()}")
                snd_pcm_hw_params_free(hwParams)
                releaseAlsa()
                return false
            }

            // Set hardware parameters
            err = snd_pcm_hw_params_set_access(pcmHandle, hwParams, SND_PCM_ACCESS_RW_INTERLEAVED)
            if (err < 0) println("[WARN] Failed to set access: ${snd_strerror(err)?.toKString()}")

            err = snd_pcm_hw_params_set_format(pcmHandle, hwParams, audioFormat)
            if (err < 0) {
                println("[ERROR] Failed to set format $audioFormat: ${snd_strerror(err)?.toKString()}")
                // Fallback or error handling if needed
            }

            var actualRate = sampleRate.toUInt()
            err = snd_pcm_hw_params_set_rate_near(pcmHandle, hwParams, cValuesOf(actualRate), null)
            if (err < 0) println("[WARN] Failed to set rate near $sampleRate: ${snd_strerror(err)?.toKString()}")
            if (actualRate != sampleRate.toUInt()) {
                println("[WARN] Actual sample rate set to $actualRate Hz (requested $sampleRate Hz)")
                // Update internal rate if necessary, though Whisper needs 16k
            }

            err = snd_pcm_hw_params_set_channels(pcmHandle, hwParams, channels.toUInt())
            if (err < 0) println("[WARN] Failed to set channels $channels: ${snd_strerror(err)?.toKString()}")

            var actualPeriodSize = periodSize.toUInt()
            err = snd_pcm_hw_params_set_period_size_near(
                pcmHandle,
                hwParams,
                cValuesOf(actualPeriodSize),
                null
            )
            if (err < 0) println(
                "[WARN] Failed to set period size near $periodSize: ${
                    snd_strerror(
                        err
                    )?.toKString()
                }"
            )
            this@YanWhisperSpeechService.periodSize = actualPeriodSize.toInt()

            var actualBufferSize = bufferSize.toUInt()
            err = snd_pcm_hw_params_set_buffer_size_near(
                pcmHandle,
                hwParams,
                cValuesOf(actualBufferSize)
            )
            if (err < 0) println(
                "[WARN] Failed to set buffer size near $bufferSize: ${
                    snd_strerror(
                        err
                    )?.toKString()
                }"
            )
            this@YanWhisperSpeechService.bufferSize = actualBufferSize.toInt()

            // Apply hardware parameters
            err = snd_pcm_hw_params(pcmHandle, hwParams)
            if (err < 0) {
                println("[ERROR] Failed to apply hw params: ${snd_strerror(err)?.toKString()}")
                snd_pcm_hw_params_free(hwParams)
                releaseAlsa()
                return false
            }

            // Get final buffer/period sizes
            val finalBufferSize = alloc<UIntVar>()
            val finalPeriodSize = alloc<UIntVar>()
            snd_pcm_hw_params_get_buffer_size(hwParams, finalBufferSize.ptr)
            snd_pcm_hw_params_get_period_size(hwParams, finalPeriodSize.ptr, null)
            this@YanWhisperSpeechService.bufferSize = finalBufferSize.value.toInt()
            this@YanWhisperSpeechService.periodSize = finalPeriodSize.value.toInt()
            this@YanWhisperSpeechService.periods =
                if (this@YanWhisperSpeechService.periodSize > 0) this@YanWhisperSpeechService.bufferSize / this@YanWhisperSpeechService.periodSize else 0
            println("[ALSA] Applied HW Params: bufferSize=${this@YanWhisperSpeechService.bufferSize}, periodSize=${this@YanWhisperSpeechService.periodSize}, periods=${this@YanWhisperSpeechService.periods}")

            snd_pcm_hw_params_free(hwParams)

            // Set software parameters
            val swParamsPtr = allocPointerTo<snd_pcm_sw_params_t>()
            err = snd_pcm_sw_params_malloc(swParamsPtr.ptr)
            if (err < 0) {
                println("[ERROR] Failed to allocate sw params: ${snd_strerror(err)?.toKString()}")
                releaseAlsa()
                return false
            }
            val swParams = swParamsPtr.value
                ?: run { println("[ERROR] swParams is null after malloc"); releaseAlsa(); return false }

            err = snd_pcm_sw_params_current(pcmHandle, swParams)
            if (err < 0) println("[WARN] Failed to get current sw params: ${snd_strerror(err)?.toKString()}")

            // Set avail_min to one period size
            err = snd_pcm_sw_params_set_avail_min(
                pcmHandle,
                swParams,
                this@YanWhisperSpeechService.periodSize.convert()
            )
            if (err < 0) println("[WARN] Failed to set avail_min: ${snd_strerror(err)?.toKString()}")

            // Set start_threshold to 1 to start ASAP
            err = snd_pcm_sw_params_set_start_threshold(pcmHandle, swParams, 1U)
            if (err < 0) println("[WARN] Failed to set start_threshold: ${snd_strerror(err)?.toKString()}")

            err = snd_pcm_sw_params(pcmHandle, swParams)
            if (err < 0) println("[WARN] Failed to apply sw params: ${snd_strerror(err)?.toKString()}")

            snd_pcm_sw_params_free(swParams)

            // Prepare PCM device
            err = snd_pcm_prepare(pcmHandle)
            if (err < 0) {
                println("[ERROR] Failed to prepare PCM device: ${snd_strerror(err)?.toKString()}")
                releaseAlsa()
                return false
            }
        }
        println("[ALSA] ALSA initialized successfully.")
        return true
    }

    /**
     * 开始语音识别
     *
     * @return 是否成功启动识别
     */
    fun startRecognition(): Boolean {
        if (!isInitialized) {
            println("[ERROR] Whisper service not initialized.")
            return false
        }
        if (isRunning.get() == 1) {
            println("[WARN] Whisper recognition already running.")
            return false
        }
        isRunning.set(1)
        _recognitionState.value = RecognitionState.LISTENING
        recognitionJob = serviceScope.launch {
            try {
                while (isActive && isRunning.get() == 1) {
                    _recognitionState.value = RecognitionState.LISTENING
                    val audioData = readAudioFrame() // 采集一帧音频
                    if (audioData != null) {
                        _recognitionState.value = RecognitionState.PROCESSING
                        val result = recognizeAudio(audioData)
                        if (!result.isNullOrBlank()) {
                            _recognitionText.value = result
                        }
                    } else {
                        delay(10)
                    }
                }
            } catch (e: Exception) {
                println("[ERROR] Whisper recognition error: ${e.message}")
                _recognitionState.value = RecognitionState.ERROR
            } finally {
                _recognitionState.value = RecognitionState.IDLE
            }
        }
        return true
    }

    /**
     * 停止语音识别
     */
    fun stopRecognition() {
        isRunning.set(0)
        recognitionJob?.cancel()
        recognitionJob = null
        _recognitionState.value = RecognitionState.IDLE
    }

    /**
     * 执行一次语音识别并返回结果
     *
     * 这个方法会启动识别，等待一段时间获取结果，然后停止识别
     *
     * @param timeoutMs 超时时间（毫秒）
     * @return 识别结果，如果识别失败则返回null
     */
    suspend fun recognizeOnce(timeoutMs: Long = 5000): String? {
        if (!isInitialized) return null
        _recognitionText.value = null
        isRunning.set(1)
        _recognitionState.value = RecognitionState.LISTENING
        return try {
            withTimeout(timeoutMs) {
                while (isActive && isRunning.get() == 1) {
                    val audioData = readAudioFrame()
                    if (audioData != null) {
                        _recognitionState.value = RecognitionState.PROCESSING
                        val result = recognizeAudio(audioData)
                        if (!result.isNullOrBlank()) {
                            _recognitionText.value = result
                            stopRecognition()
                            return@withTimeout result
                        }
                    } else {
                        delay(10)
                    }
                }
                null
            }
        } catch (e: TimeoutCancellationException) {
            println("[WARN] Whisper recognizeOnce timeout.")
            stopRecognition()
            null
        } finally {
            isRunning.set(0)
            _recognitionState.value = RecognitionState.IDLE
        }
    }

    /**
     * 采集一帧音频数据
     *
     * @return FloatArray? 采集到的音频数据
     */
    private fun readAudioFrame(): FloatArray? {
        if (pcmHandle == null) return null
        val frameCount = periodSize
        val buffer = FloatArray(frameCount * channels)
        val read = snd_pcm_readi(pcmHandle, buffer.refTo(0), frameCount.toULong())
        if (read < 0) {
            val err = snd_pcm_recover(pcmHandle, read.toInt(), 1)
            if (err < 0) {
                println("[ERROR] ALSA read recover failed: ${snd_strerror(err)?.toKString()}")
                return null
            }
            return null
        }
        if (read == 0L) return null
        return buffer.copyOf(read.toInt() * channels)
    }

    /**
     * 调用Whisper进行音频识别
     *
     * @param audioData FloatArray 音频数据
     * @return String? 识别文本
     */
    private fun recognizeAudio(audioData: FloatArray): String? {
        if (whisperContext == null) return null
        // 伪代码：实际应调用whisper_full等API进行识别
        // 这里只做接口占位，需根据Whisper C API实际实现
        // whisper_full(whisperContext, params, audioData, audioData.size)
        // val nSegments = whisper_full_n_segments(whisperContext)
        // val text = StringBuilder()
        // for (i in 0 until nSegments) {
        //     text.append(whisper_full_get_segment_text(whisperContext, i))
        // }
        // return text.toString()
        return null // TODO: 实现Whisper识别逻辑
    }

    /**
     * 设置麦克风音量 (可能不需要直接控制)
     *
     * @param volume 音量值 (0-100)
     * @return 设置是否成功
     */
    fun setMicrophoneVolume(volume: Int): Boolean {
        println("[WARN] setMicrophoneVolume might not be directly applicable to Whisper service. Volume control usually handled by the audio system (ALSA).")
        // TODO: If specific control is needed via ALSA/audio library, implement here.
        // Example: return recognizer.setMicrophoneVolume(volume)
        return true // Placeholder
    }

    /**
     * 获取当前麦克风音量 (可能不需要直接控制)
     *
     * @return 当前音量值 (0-100)
     */
    fun getMicrophoneVolume(): Int {
        println("[WARN] getMicrophoneVolume might not be directly applicable to Whisper service. Volume usually read from the audio system (ALSA).")
        // TODO: If specific control is needed via ALSA/audio library, implement here.
        // Example: return recognizer.getMicrophoneVolume()
        return 80 // Placeholder
    }

    /**
     * 检查麦克风状态
     *
     * @param timeout 检查超时时间（毫秒），默认1000ms
     * @return 麦克风状态信息
     */
    suspend fun checkMicrophoneStatus(timeout: Long = 1000): String {
        println("Checking microphone status...")
        // Reuse the arecord check from Vosk service
        val arecordOutput = executeCommand("arecord -l", timeoutMs = 500)
        println("[INFO] System microphone device check result:")
        println(arecordOutput)

        // TODO: Implement audio level check if possible with the chosen audio capture method
        // This might involve capturing a short audio snippet and analyzing its energy/amplitude.

        println("Microphone status check complete (placeholder for audio level analysis).")
        return "Microphone status check complete. System devices:\n$arecordOutput\n(Audio level analysis not implemented yet)"
    }


    /**
     * 释放资源
     */
    /**
     * Releases only ALSA resources.
     */
    private fun releaseAlsa() {
        if (pcmHandle != null) {
            println("[ALSA] Releasing ALSA resources...")
            // Attempt drain, ignore errors as we are closing anyway
            snd_pcm_drain(pcmHandle)
            snd_pcm_close(pcmHandle)
            pcmHandle = null
            println("[ALSA] ALSA resources released.")
        }
    }

    /**
     * Releases only Whisper resources.
     */
    private fun releaseWhisper() {
        if (whisperContext != null) {
            println("[WHISPER] Releasing Whisper context...")
            whisper_free(whisperContext)
            whisperContext = null
            println("[WHISPER] Whisper context released.")
        }
    }

    /**
     * Releases all resources (ALSA, Whisper, Coroutines).
     */
    fun release() {
        println("Releasing Whisper Speech Service resources...")
        stopRecognition() // Ensure recognition loop is stopped
        releaseAlsa()
        releaseWhisper()
        serviceScope.cancel("Service released")
        isInitialized = false
        _recognitionState.value = RecognitionState.IDLE
        println("Whisper Speech Service resources released.")
    }

    // Helper to get ALSA state name (similar to Vosk Recognizer)
    private fun getPcmStateName(state: _snd_pcm_state): String {
        return when (state) {
            SND_PCM_STATE_OPEN -> "OPEN"
            SND_PCM_STATE_SETUP -> "SETUP"
            SND_PCM_STATE_PREPARED -> "PREPARED"
            SND_PCM_STATE_RUNNING -> "RUNNING"
            SND_PCM_STATE_XRUN -> "XRUN (underrun/overrun)"
            SND_PCM_STATE_DRAINING -> "DRAINING"
            SND_PCM_STATE_PAUSED -> "PAUSED"
            SND_PCM_STATE_SUSPENDED -> "SUSPENDED"
            SND_PCM_STATE_DISCONNECTED -> "DISCONNECTED"
            else -> "UNKNOWN_STATE ($state)"
        }
    }

    // Helper to get ALSA error description (similar to Vosk Recognizer)
    private fun getAlsaErrorDescription(errorCode: Int): Pair<String, String> {
        // Simplified version, add more codes as needed
        return when (errorCode) {
            -EPIPE -> ("EPIPE" to "Broken pipe")
            -EBADFD -> ("EBADFD" to "Bad file descriptor")
            -ESTRPIPE -> ("ESTRPIPE" to "Stream suspended")
            -EAGAIN -> ("EAGAIN" to "Resource temporarily unavailable")
            -EINTR -> ("EINTR" to "Interrupted system call")
            -EIO -> ("EIO" to "Input/output error")
            -ENODEV -> ("ENODEV" to "No such device")
            -ENOENT -> ("ENOENT" to "No such file or directory")
            -EBUSY -> ("EBUSY" to "Device busy")
            -EINVAL -> ("EINVAL" to "Invalid argument")
            -ENOMEM -> ("ENOMEM" to "Out of memory")
            -ENOSYS -> ("ENOSYS" to "Function not implemented")
            else -> ("Unknown ($errorCode)" to (snd_strerror(errorCode)?.toKString()
                ?: "Unknown ALSA error"))
        }
    }
}