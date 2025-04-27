@file:OptIn(ExperimentalForeignApi::class, ExperimentalStdlibApi::class, ExperimentalTime::class,
    ExperimentalAtomicApi::class
)
package whisperPortaudio

import com.airobot.device.yanapi.whisper.whisper_full
import com.airobot.device.yanapi.whisper.whisper_full_default_params
import com.airobot.device.yanapi.whisper.whisper_full_parallel
import com.airobot.device.yanapi.whisper.whisper_full_with_state
import com.airobot.device.yanapi.whisper.whisper_init_from_file_with_params
import com.airobot.portaudiointerop.Pa_AbortStream
import com.airobot.portaudiointerop.Pa_CloseStream
import com.airobot.portaudiointerop.Pa_GetErrorText
import com.airobot.portaudiointerop.Pa_Initialize
import com.airobot.portaudiointerop.Pa_OpenDefaultStream
import com.airobot.portaudiointerop.Pa_ReadStream
import com.airobot.portaudiointerop.Pa_StartStream
import com.airobot.portaudiointerop.Pa_StopStream
import com.airobot.portaudiointerop.Pa_Terminate
import com.airobot.portaudiointerop.paInt16
import com.airobot.portaudiointerop.paNoError
import com.airobot.pythoninterop.state
import com.airobot.whisperinterop.whisper_context
import com.airobot.whisperinterop.whisper_context_params
import com.airobot.whisperinterop.whisper_decode
import com.airobot.whisperinterop.whisper_decode_with_state
import com.airobot.whisperinterop.whisper_encode
import com.airobot.whisperinterop.whisper_encode_with_state
import com.airobot.whisperinterop.whisper_free
import com.airobot.whisperinterop.whisper_free_state
import com.airobot.whisperinterop.whisper_full_get_segment_text
import com.airobot.whisperinterop.whisper_full_get_segment_text_from_state
import com.airobot.whisperinterop.whisper_full_n_segments
import com.airobot.whisperinterop.whisper_full_n_segments_from_state
import com.airobot.whisperinterop.whisper_full_params
import com.airobot.whisperinterop.whisper_init_state
import com.airobot.whisperinterop.whisper_pcm_to_mel
import com.airobot.whisperinterop.whisper_pcm_to_mel_with_state
import com.airobot.whisperinterop.whisper_sampling_strategy
import com.airobot.whisperinterop.whisper_state
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.free
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.rawPtr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.absoluteValue
import kotlin.math.sqrt
import kotlin.time.Clock.System
import kotlin.time.ExperimentalTime

/**
 * 基于PortAudio的语音采集与Whisper.cpp识别模块骨架
 */
class WhisperPortaudioRecognizer {
    companion object {
        const val DEFAULT_SAMPLE_RATE = 16000
        const val DEFAULT_CHANNELS = 1
        const val DEFAULT_MODEL_PATH = "/usr/local/share/yanshee-model/whisper/ggml-tiny-q4_1.bin"
        const val MIN_AUDIO_SECONDS = 3.0  // 最小处理音频长度（秒）
    }
    private var streamPtr: COpaquePointerVar?= null
    private var whisperCtx: CPointer<whisper_context>? = null
    private var whisperState: CPointer<whisper_state>? = null
    private var whisperParams: CValue<whisper_full_params>? = null
    private val _recognitionState = MutableStateFlow(RecognitionState.IDLE)
    val recognitionState: StateFlow<RecognitionState> = _recognitionState.asStateFlow()
    private val _recognitionResult = MutableStateFlow<String?>(null)
    val recognitionResult: StateFlow<String?> = _recognitionResult.asStateFlow()
    private var recognitionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    // 增加一个处理状态标志，与主识别状态分开
    private val _isProcessing = AtomicBoolean(false)  // 改用AtomicBoolean，更安全

    // 音频缓冲区，用于累积足够的音频数据进行推理
    private val audioBuffer = ArrayList<Float>()
    private val audioBufferSize = 16000 * 30 // 30秒的音频数据
    private val audioBufferMutex = Mutex()  // 改用Mutex而非SynchronizedObject，更符合协程风格
    enum class RecognitionState {
        IDLE, INITIALIZING, LISTENING, PROCESSING, ERROR
    }

    fun initialize(modelPath: String = DEFAULT_MODEL_PATH, sampleRate: Int = DEFAULT_SAMPLE_RATE, channels: Int = DEFAULT_CHANNELS): Boolean {
        println("[INFO] 开始初始化Whisper模型，路径: $modelPath")
        _recognitionState.value = RecognitionState.INITIALIZING
        // 确保之前的资源已释放
        release()
        memScoped {
            try {
                streamPtr = nativeHeap.alloc<COpaquePointerVar>()
                val contextParamsPtr = nativeHeap.alloc<whisper_context_params>()
                // 初始化Whisper模型
                println("[INFO] 初始化Whisper模型...")
                whisperCtx = whisper_init_from_file_with_params(modelPath, contextParamsPtr.ptr)
                if (whisperCtx == null) {
                    println("[ERROR] Whisper模型加载失败，路径: $modelPath")
                    _recognitionState.value = RecognitionState.ERROR
                    return false
                }
                // 初始化Whisper参数
                val strategyVar = nativeHeap.alloc<whisper_sampling_strategy.Var>()
                strategyVar.value = whisper_sampling_strategy.WHISPER_SAMPLING_GREEDY
                whisperParams = whisper_full_default_params(strategyVar.ptr)
                val languagePtr = "zh".cstr.getPointer(this)
                whisperParams?.useContents {
                    print_realtime = false     // 关闭实时打印，减少I/O开销
                    print_progress = false     // 关闭进度打印
                    print_timestamps = false   // 关闭时间戳打印
                    translate = false          // 不进行翻译
                    language = languagePtr     // 设置语言为中文
                    n_threads = 1          // 单线程更可靠
                    max_tokens = 8         // 减少生成的token数量
                    audio_ctx = 0          // 禁用音频上下文
                    no_context = true      // 不使用上下文
                    entropy_thold = 2.5f   // 提高熵阈值，减少低概率预测
                    no_speech_thold = 0.8f // 提高无语音阈值
                }
                whisperState = whisper_init_state(whisperCtx)
                // 初始化PortAudio
                println("[INFO] 初始化PortAudio...\n")
                if (Pa_Initialize() != paNoError) {
                    println("[ERROR] PortAudio初始化失败")
                    whisper_free(whisperCtx)
                    whisperCtx = null  // 防止double-free
                    _recognitionState.value = RecognitionState.ERROR
                    return false
                }

                println("[INFO] 初始化完成")
                _recognitionState.value = RecognitionState.IDLE
                return true
            } catch (e: Exception) {
                println("[ERROR] 初始化过程中出现异常: ${e.message}")
                e.printStackTrace()
                // 确保释放已分配的资源
                try { whisperCtx?.let { whisper_free(it); whisperCtx = null } } catch (_: Exception) {}
                try { Pa_Terminate() } catch (_: Exception) {}
                _recognitionState.value = RecognitionState.ERROR
                return false
            }
        }
    }

    fun startRecognition() {
        if (_recognitionState.value == RecognitionState.LISTENING) return
        _recognitionState.value = RecognitionState.LISTENING
        _isProcessing.store(false)
        println("[INFO] 启动语音识别，准备采集音频流...")

        // 清空音频缓冲区
        runBlocking {
            audioBufferMutex.withLock {
                audioBuffer.clear()
            }
        }

        recognitionJob = scope.launch {
            var bufferSize = 8192  // 直接使用更大的初始缓冲区
            var buffer = nativeHeap.allocArray<ShortVar>(bufferSize)

            // 使用默认流配置
            println("[INFO] 尝试打开默认音频流...")
            if(streamPtr == null) return@launch
            val defaultErr = Pa_OpenDefaultStream(
                streamPtr!!.ptr,
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
            Pa_StartStream(streamPtr!!.value)

            // 启动后等待一小段时间
            delay(200)  // 延长等待时间，确保音频流稳定

            // 添加音频检测变量
            var totalSamples = 0
            var maxAmplitude = 0
            var startTime = System.now().toEpochMilliseconds()
            var lastProcessTime = 0L

            // 启动一个独立的协程监控和处理音频
            val processingJob = launch {
                while (isActive && _recognitionState.value != RecognitionState.IDLE && _recognitionState.value != RecognitionState.ERROR) {
                    // 不再检测LISTENING状态，只要不是IDLE或ERROR就处理
                    val currentTime = System.now().toEpochMilliseconds()
                    val timeSinceLastProcess = currentTime - lastProcessTime

                    // 确保有足够的音频数据进行处理
                    val bufferSize = audioBufferMutex.withLock { audioBuffer.size }
                    val hasEnoughSamples = bufferSize > DEFAULT_SAMPLE_RATE * MIN_AUDIO_SECONDS

                    // 确保不在处理中且有足够的数据
                    if (timeSinceLastProcess > 2000 && hasEnoughSamples && !_isProcessing.load()) {
                        println("[DEBUG] 当前时间: $currentTime, 上次处理: $lastProcessTime, 间隔: $timeSinceLastProcess 毫秒")
                        println("[DEBUG] 缓冲区大小: $bufferSize, 需要: ${DEFAULT_SAMPLE_RATE * MIN_AUDIO_SECONDS}, 条件满足: $hasEnoughSamples")

                        println("[INFO] 满足处理条件，准备识别PCM数据")

                        lastProcessTime = currentTime
                        processAudio()
                    }

                    delay(100) // 每100毫秒检查一次
                }
                println("[INFO] 处理监控协程结束")
            }

            // 主循环只负责收集音频数据
            withContext(Dispatchers.IO) {
                while (isActive && (_recognitionState.value == RecognitionState.LISTENING || _recognitionState.value == RecognitionState.PROCESSING)) {
                    try {
                        if (streamPtr!!.value == null) {
                            println("[ERROR] 音频流指针为空，退出采集循环")
                            break
                        }

                        val read = Pa_ReadStream(streamPtr!!.value, buffer, bufferSize.convert())

                        if (read == paNoError) {
                            // 检测音频振幅
                            var currentMaxAmplitude = 0
                            audioBufferMutex.withLock { // 使用协程Mutex进行同步
                                for (i in 0 until bufferSize) {
                                    val amplitude = buffer[i].toInt()
                                    if (amplitude.absoluteValue > currentMaxAmplitude) {
                                        currentMaxAmplitude = amplitude.absoluteValue
                                    }
                                    // 简化增益计算，避免频繁计算
                                    val gain = 3.0f // 使用固定增益
                                    val amplifiedSample = (buffer[i].toFloat() * gain).coerceIn(-1.0f, 1.0f)
                                    audioBuffer.add(amplifiedSample)
                                }
                            }

                            // 每50个缓冲区打印一次音频状态
                            totalSamples += bufferSize
                            if (totalSamples % (bufferSize * 50) == 0) {
                                val currentTime = System.now().toEpochMilliseconds()
                                val elapsedSeconds = (currentTime - startTime) / 1000.0
                                val samplesPerSecond = totalSamples / elapsedSeconds
                                println("[INFO] 音频状态: 已处理 $totalSamples 样本，当前振幅: $currentMaxAmplitude，采样率: $samplesPerSecond 样本/秒")

                                if (currentMaxAmplitude > maxAmplitude) {
                                    maxAmplitude = currentMaxAmplitude
                                    println("[INFO] 检测到新的最大振幅: $maxAmplitude")
                                }
                            }
                        }
                        else if (read == -9981) {  // Input overflow
                            println("[WARN] 输入溢出，尝试重置整个音频流...")

                            try {
                                // 完全停止并关闭当前流，但保留缓冲区数据
                                if (streamPtr!!.value != null) {
                                    Pa_AbortStream(streamPtr!!.value)
                                    Pa_CloseStream(streamPtr!!.value)
                                    streamPtr!!.value = null
                                }

                                // 保存当前缓冲区数据
                                val savedBuffer = audioBufferMutex.withLock {
                                    ArrayList<Float>(audioBuffer)
                                }

                                // 重新打开流，使用更大的缓冲区
                                val newBufferSize = bufferSize * 2  // 每次溢出时翻倍缓冲区
                                val err = Pa_OpenDefaultStream(
                                    streamPtr!!.ptr,
                                    1,
                                    0,
                                    paInt16,
                                    16000.0,
                                    newBufferSize.convert(),
                                    null,
                                    null
                                )

                                if (err == paNoError) {
                                    Pa_StartStream(streamPtr!!.value)
                                    println("[INFO] 音频流已完全重置，新缓冲区大小: $newBufferSize")

                                    // 恢复缓冲区数据
                                    audioBufferMutex.withLock {
                                        audioBuffer.clear()
                                        audioBuffer.addAll(savedBuffer)
                                    }
                                    println("[INFO] 保留了 ${savedBuffer.size} 样本的音频数据")

                                    // 修改buffer大小
                                    nativeHeap.free(buffer)
                                    buffer = nativeHeap.allocArray<ShortVar>(newBufferSize)
                                    bufferSize = newBufferSize
                                } else {
                                    val errorText = Pa_GetErrorText(err)?.toKString() ?: "未知错误"
                                    println("[ERROR] 重新打开音频流失败: $errorText")
                                    _recognitionState.value = RecognitionState.ERROR
                                    break
                                }
                            } catch (e: Exception) {
                                println("[ERROR] 重置音频流失败: ${e.message}")
                                e.printStackTrace()
                                _recognitionState.value = RecognitionState.ERROR
                                break
                            }

                            delay(200)  // 增加恢复时间
                            continue
                        }
                        else {
                            val errorText = Pa_GetErrorText(read)?.toKString() ?: "Unknown error"
                            println("[WARN] 读取音频流失败，错误码: $read，错误: $errorText，尝试继续...")
                            delay(50)
                        }

                        yield()

                    } catch (e: CancellationException) {
                        println("[INFO] 识别协程被取消")
                        break
                    } catch (e: Exception) {
                        println("[ERROR] 读取或处理音频时出错: ${e.message}")
                        e.printStackTrace()
                        delay(200)
                    }
                }

                // 主循环结束，取消处理协程
                processingJob.cancel()
                processingJob.join()  // 等待处理协程完全退出

                println("[INFO] 识别循环结束，清理资源...")
                try {
                    if (streamPtr!!.value != null) {
                        Pa_StopStream(streamPtr!!.value)
                        Pa_CloseStream(streamPtr!!.value)
                        streamPtr!!.value = null
                    }
                } catch (e: Exception) {
                    println("[WARN] 关闭音频流时出错: ${e.message}")
                    e.printStackTrace()
                }
                try { nativeHeap.free(buffer) } catch (e: Exception) {
                    println("[WARN] 释放buffer时出错: ${e.message}")
                }
            }
        }
    }
    // 添加简单的语音活动检测，只处理有声音的片段
    private fun hasVoiceActivity(samples: FloatArray, threshold: Float = 0.01f): Boolean {
        var sum = 0.0f
        for (sample in samples) {
            sum += sample * sample  // 计算能量
        }
        val rms = sqrt(sum / samples.size)
        println("[DEBUG] 音频RMS能量: $rms, 阈值: $threshold")
        return rms > threshold
    }
    private fun processAudio() {
        // 使用局部变量存储当前状态，避免并发问题
        val currentlyProcessing = _isProcessing.load()
        println("[DEBUG] 尝试处理音频，当前处理状态: $currentlyProcessing")
        // 避免重入
        if (!_isProcessing.compareAndSet(false, true)) {
            println("[WARN] 已有处理任务在进行中，跳过此次处理")
            return
        }
        println("[INFO] 成功获取处理锁，开始处理音频")

        scope.launch {
            _recognitionState.value = RecognitionState.PROCESSING
            withContext(Dispatchers.IO) {
                memScoped {
                    try {
                        // 获取音频数据
                        val bufferSize = audioBufferMutex.withLock { audioBuffer.size }
                        println("[INFO] 处理音频数据，长度: $bufferSize 样本")

                        // 确保有足够的数据进行处理
                        if (bufferSize < DEFAULT_SAMPLE_RATE * MIN_AUDIO_SECONDS) {
                            println("[WARN] 音频数据不足，最小需要 ${DEFAULT_SAMPLE_RATE * MIN_AUDIO_SECONDS} 样本，当前仅有 $bufferSize 样本")
                            _recognitionState.value = RecognitionState.LISTENING
                            _isProcessing.store(false)
                            return@withContext
                        }

                        // 将音频数据复制到临时缓冲区，并限制最大处理长度以避免内存问题
                        val maxProcessSize = minOf(bufferSize, DEFAULT_SAMPLE_RATE * 5) // 最多处理5秒的音频
                        val tempBuffer = audioBufferMutex.withLock {
                            // 深度复制数据，防止并发修改
                            if (audioBuffer.size > maxProcessSize) {
                                audioBuffer.takeLast(maxProcessSize).toFloatArray()
                            } else {
                                audioBuffer.toFloatArray()
                            }
                        }

                        println("[INFO] 实际处理音频长度: ${tempBuffer.size} 样本")

                        // 确保有足够的数据进行处理
                        if (tempBuffer.isEmpty() || tempBuffer.size < DEFAULT_SAMPLE_RATE * 1.0) {
                            println("[WARN] 临时缓冲区数据不足，跳过处理")
                            _recognitionState.value = RecognitionState.LISTENING
                            _isProcessing.store(false)
                            return@withContext
                        }
                        // 在处理前检查是否有语音活动
                        if (!hasVoiceActivity(tempBuffer, 0.01f)) {
                            println("[INFO] 未检测到语音活动，跳过处理")
                            _recognitionState.value = RecognitionState.LISTENING
                            _isProcessing.store(false)
                            return@withContext
                        }
                        // 分配内存并复制数据，添加填充确保内存对齐
                        val paddedSize = ((tempBuffer.size + 31) / 32) * 32  // 确保32字节对齐
                        val pcmData = nativeHeap.allocArray<FloatVar>(paddedSize)
                        try {
                            // 初始化为零
                            for (i in 0 until paddedSize) {
                                pcmData[i] = 0.0f
                            }

                            // 复制实际数据
                            for (i in tempBuffer.indices) {
                                pcmData[i] = tempBuffer[i]
                            }

                            // 确保Whisper上下文和参数有效
                            if (whisperCtx == null) {
                                println("[ERROR] Whisper上下文为空，无法执行推理")
                                _recognitionState.value = RecognitionState.ERROR
                                return@withContext
                            }

                            // 执行Whisper推理
                            println("[DEBUG] 开始Whisper推理，PCM数据长度: ${tempBuffer.size}，填充后: $paddedSize")
                            val startTime = System.now().toEpochMilliseconds()

                            // 确保参数复制一份，防止并发修改
                            val params = whisperParams?.let { it } ?: run {
                                println("[ERROR] Whisper参数为空")
                                _recognitionState.value = RecognitionState.ERROR
                                return@withContext
                            }
                            println("whisperParams: ${params}")

                            // 创建tokens缓冲区
                            val tokensCapacity = 1024   // 增大容量
                            val tokensBuffer = nativeHeap.allocArray<IntVar>(tokensCapacity)
                            /*try {
                                // 将PCM转换为Mel频谱图
                                println("[DEBUG] 转换PCM到Mel频谱图")
                                val mel = whisper_pcm_to_mel_with_state(
                                    whisperCtx,   // 上下文
                                    whisperState,        // 状态
                                    pcmData,      // 音频数据
                                    tempBuffer.size, // 样本数
                                    8             // 线程数
                                )

                                if (mel != 0) {
                                    println("[ERROR] PCM转换MEL失败，错误码: $mel")
                                    return@withContext
                                }

                                // 运行编码器
                                println("[DEBUG] 编码PCM数据")
                                val encodeResult = whisper_encode_with_state(
                                    whisperCtx,   // 上下文
                                    whisperState,
                                    0,            // offset
                                    4             // 线程数
                                )

                                if (encodeResult != 0) {
                                    println("[ERROR] 编码失败，错误码: $encodeResult")
                                    return@withContext
                                }


                                var n_tokens = 0

                                // 运行解码器
                                val decodeResult = whisper_decode_with_state(
                                    whisperCtx,    // 上下文
                                    whisperState,
                                    tokensBuffer,  // tokens缓冲区
                                    n_tokens,      // token数量
                                    0,             // past token数
                                    4              // 线程数
                                )

                                if (decodeResult != 0) {
                                    println("[ERROR] 解码失败，错误码: $decodeResult")
                                    return@withContext
                                }

                                // 获取文本结果
                                val sb = StringBuilder()
                                val n_segments = whisper_full_n_segments_from_state(whisperState)

                                // 如果没有段落，可能需要使用其他API获取结果
                                if (n_segments == 0) {
                                    println("[WARN] 没有识别到语音段落")
                                    // 这里可能需要使用其他API获取结果
                                } else {
                                    for (i in 0 until n_segments) {
                                        val segment_text = whisper_full_get_segment_text_from_state(whisperState, i)?.toKString() ?: ""
                                        sb.append(segment_text).append(" ")
                                    }
                                }

                                val resultText = sb.toString().trim()
                                if (resultText.isNotEmpty()) {
                                    println("[INFO] 识别结果: $resultText")
                                    _recognitionResult.value = resultText
                                } else {
                                    println("[WARN] 识别结果为空")
                                }
                            } finally {
                                // 释放资源
                                try {
                                    nativeHeap.free(tokensBuffer)
                                } catch (e: Exception) {
                                    println("[WARN] 释放tokens缓冲区失败: ${e.message}")
                                }

    //                            try {
    //                                whisper_free_state(whisperState)
    //                            } catch (e: Exception) {
    //                                println("[WARN] 释放state失败: ${e.message}")
    //                            }
                            }*/

                            // 每次处理前重新初始化whisperState，避免状态累积导致内存问题
                            if(whisperState != null) {
                                try {
                                    whisper_free_state(whisperState)
                                    whisperState = null
                                } catch (e: Exception) {
                                    println("[WARN] 释放whisperState失败: ${e.message}")
                                }
                            }
                            
                            // 重新创建一个新的state
                            whisperState = whisper_init_state(whisperCtx)
                            if(whisperState == null) {
                                println("[ERROR] 无法创建whisperState")
                                _recognitionState.value = RecognitionState.ERROR
                                return@withContext
                            }
                            
                            val result = whisper_full_with_state(whisperCtx,
                                whisperState!!,
                                params,pcmData,tempBuffer.size)

//                       whisper_full(whisperCtx, params, pcmData, tempBuffer.size)
                            val endTime = System.now().toEpochMilliseconds()
                            println("[DEBUG] Whisper推理完成，耗时: ${endTime - startTime} 毫秒，结果码: $result")

                            if (result == 0) {
                                // 获取识别结果
                                val segmentCount = whisper_full_n_segments_from_state(whisperState)
                                println("[DEBUG] 识别到 $segmentCount 个语音片段")
                                val resultBuilder = StringBuilder()

                                for (i in 0 until segmentCount) {
                                    val text = whisper_full_get_segment_text_from_state(whisperState, i)?.toKString() ?: ""
                                    if (text.isNotEmpty()) {
                                        if(text.equals(" (explosion)").not()){
                                            // 空的片段，跳过
                                            println("[DEBUG] 片段 $i: '$text'")
                                            resultBuilder.append(text).append(" ")
                                        }
                                    }
                                }

                                val resultText = resultBuilder.toString().trim()
                                if (resultText.isNotEmpty()) {
                                    println("[INFO] 识别结果: $resultText")
                                    _recognitionResult.value = resultText
                                } else {
                                    println("[WARN] 识别结果为空")
                                }
                            } else {
                                val errorDescription = when(result) {
                                    -1 -> "一般错误"
                                    -2 -> "内存分配失败"
                                    -3 -> "模型问题"
                                    -4 -> "参数无效"
                                    -5 -> "状态无效"
                                    -6 -> "输入无效"
                                    else -> "未知错误码: $result"
                                }
                                println("[ERROR] Whisper推理失败: $errorDescription")
                            }
                        } catch (e: Exception) {
                            println("[ERROR] 执行Whisper推理时出现异常: ${e.message}")
                            e.printStackTrace()
                        } finally {
                            // 确保在任何情况下都释放内存
                            try { nativeHeap.free(pcmData) } catch (e: Exception) {
                                println("[WARN] 释放PCM数据时出错: ${e.message}")
                            }
                            
                            // 处理完成后释放whisperState，避免内存泄漏
                            try {
                                if (whisperState != null) {
                                    whisper_free_state(whisperState)
                                    whisperState = null
                                    println("[INFO] 处理完成后释放whisperState")
                                }
                            } catch (e: Exception) {
                                println("[WARN] 处理完成后释放whisperState失败: ${e.message}")
                            }
                        }

                        // 保留最近的一部分音频数据，丢弃旧数据
                        audioBufferMutex.withLock {
                            if (audioBuffer.size > audioBufferSize / 2) { // 降低阈值，更频繁地清理
                                val keepSize = audioBufferSize / 4 // 保留更少的数据
                                val newBuffer = audioBuffer.takeLast(keepSize)
                                audioBuffer.clear()
                                audioBuffer.addAll(newBuffer)
                                println("[INFO] 音频缓冲区已清理，保留了 $keepSize 样本")
                            }
                        }

                    } catch (e: Exception) {
                        println("[ERROR] 处理音频数据时出错: ${e.message}")
                        e.printStackTrace()
                    } finally {
                        // 恢复监听状态
                        _recognitionState.value = RecognitionState.LISTENING
                        // 清除处理标志
                        _isProcessing.store(false)
                        println("[INFO] 处理完成，恢复监听状态")
                    }
                }

            }
        }
    }

    fun stopRecognition() {
        println("[INFO] 停止语音识别...")
        // 标记状态变化
        if (_recognitionState.value != RecognitionState.ERROR) {
            _recognitionState.value = RecognitionState.IDLE
        }

        // 取消协程
        recognitionJob?.cancel()

        // 使用非阻塞方式等待短暂时间让协程有机会清理
        runBlocking {
            withTimeoutOrNull(2000L) {
                try {
                    recognitionJob?.join()
                } catch (e: CancellationException) {
                    // 忽略取消异常，这是预期的
                    println("[INFO] 识别协程已取消")
                }
            }
        }
        recognitionJob = null

        // 关闭音频流
        try {
            if (streamPtr?.value != null) {
                Pa_StopStream(streamPtr!!.value)
                Pa_CloseStream(streamPtr!!.value)
                streamPtr!!.value = null
            }
        } catch (e: Exception) {
            println("[WARN] 关闭音频流时出错: ${e.message}")
        }
    }

    fun release() {
        println("[INFO] 释放所有资源...")
        // 先停止所有操作
        stopRecognition()

        // 确保所有协程已完全停止
        runBlocking {
            withTimeoutOrNull(2000L) {
                recognitionJob?.join()
            }
        }

        // 释放Whisper状态资源
        try {
            if (whisperState != null) {
                whisper_free_state(whisperState)
                whisperState = null
                println("[INFO] Whisper状态已释放")
            }
        } catch (e: Exception) {
            println("[WARN] 释放Whisper状态时出错: ${e.message}")
        }
        
        // 释放Whisper上下文资源
        try {
            if (whisperCtx != null) {
                whisper_free(whisperCtx)
                whisperCtx = null
                println("[INFO] Whisper上下文已释放")
            }
        } catch (e: Exception) {
            println("[WARN] 释放Whisper上下文时出错: ${e.message}")
        }

        // 释放指针
        try {
            if (streamPtr != null) {
                nativeHeap.free(streamPtr!!.rawPtr)
                streamPtr = null
            }
        } catch (e: Exception) {
            println("[WARN] 释放流指针时出错: ${e.message}")
        }

        // 终止PortAudio
        try { Pa_Terminate() } catch (e: Exception) {
            println("[WARN] 终止PortAudio时出错: ${e.message}")
        }

        _recognitionState.value = RecognitionState.IDLE
        println("[INFO] 所有资源已释放")
    }
}