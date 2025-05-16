@file:OptIn(ExperimentalForeignApi::class, ExperimentalStdlibApi::class, NativeRuntimeApi::class)
@file:Suppress("FunctionName", "unused", "UNUSED_PARAMETER")

package snowboyPiper.impl

import com.airobot.voskinterop.VoskModel
import com.airobot.voskinterop.VoskRecognizer
import com.airobot.voskinterop.vosk_model_free
import com.airobot.voskinterop.vosk_model_new
import com.airobot.voskinterop.vosk_recognizer_accept_waveform_s
import com.airobot.voskinterop.vosk_recognizer_final_result
import com.airobot.voskinterop.vosk_recognizer_free
import com.airobot.voskinterop.vosk_recognizer_new
import com.airobot.voskinterop.vosk_recognizer_partial_result
import com.airobot.voskinterop.vosk_recognizer_set_grm
import com.airobot.voskinterop.vosk_recognizer_set_max_alternatives
import com.airobot.voskinterop.vosk_recognizer_set_words
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.refTo
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.posix.FILE
import platform.posix.fgets
import platform.posix.pclose
import platform.posix.popen
import snowboyPiper.interfaces.SpeechRecognizer
import snowboyPiper.interfaces.SpeechService
import snowboyPiper.interop.SoxrSingleton
import kotlin.math.min
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

/**
 * Vosk语音服务实现
 *
 * 该类封装了VoskSpeechRecognizer，提供了更高级别的API接口，
 * 使得在Kotlin Native环境中更容易使用ALSA麦克风采集和Vosk语音识别功能。
 *
 * 功能：
 * 1. 音频信号增强和降噪
 * 2. 麦克风状态诊断
 * 3. 详细的调试信息输出
 */
class VoskSpeechService(private val recognizer: VoskSpeechRecognizer) : SpeechService {
    companion object {
        const val TARGET_SAMPLE_RATE = 16000 // Vosk要求的采样率
        const val SOURCE_SAMPLE_RATE = 48000 // PortAudio默认采样率

        /**
         * 执行shell命令并返回输出结果
         *
         * @param command 要执行的命令
         * @param timeoutMs 命令执行超时时间（毫秒），默认1000ms
         * @param maxOutputSize 最大输出大小，防止内存溢出，默认4096字节
         * @return 命令执行的输出结果
         */
        suspend fun executeCommand(
            command: String,
            timeoutMs: Long = 1000L,
            maxOutputSize: Int = 4096
        ): String {
            val completableDeferred = CompletableDeferred<String>()
            // 启动一个线程执行命令，以便实现超时控制
            withContext(Dispatchers.Default) {
                val result = StringBuilder()
                var process: CPointer<FILE>? = null
                try {
                    // 创建并启动协程
                    val commandJob = async {
                        try {
                            process = popen(command, "r")
                            if (process == null) {
                                result.append("无法执行命令: $command")
                                return@async
                            }

                            val buffer = ByteArray(1024)
                            var totalRead = 0
                            var readBuffer: CPointer<ByteVar>?

                            do {
                                readBuffer = fgets(buffer.refTo(0), buffer.size, process!!)
                                if (readBuffer != null) {
                                    val line = buffer.toKString()
                                    // 限制输出大小
                                    if (totalRead + line.length <= maxOutputSize) {
                                        result.append(line)
                                        totalRead += line.length
                                    } else {
                                        result.append("\n... 输出过大，已截断")
                                        break
                                    }
                                }
                            } while (readBuffer != null && isActive) // 检查协程是否仍然活跃
                        } catch (e: Exception) {
                            result.append("执行命令出错: ${e.message}")
                        }
                    }
                    // 设置超时
                    try {
                        withTimeout(timeoutMs) {
                            commandJob.await() // 等待协程完成或超时
                        }
                    } catch (e: TimeoutCancellationException) {
                        commandJob.cancel() // 取消协程
                        result.append("\n命令执行超时 (${timeoutMs}ms)")
                    }
                } catch (e: Exception) {
                    result.append("执行命令异常: ${e.message}")
                } finally {
                    // 确保关闭进程
                    process?.let { pclose(it) }
                    // 强制GC回收内存
                    GC.collect()
                }
                completableDeferred.complete(result.toString())
            }
            return completableDeferred.await()
        }
    }

    // 协程作用域和任务
    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private var recognitionJob: Job? = null

    // 识别结果流
    private val _recognitionText = MutableStateFlow<String?>(null)
    override val recognitionText: StateFlow<String?> = _recognitionText.asStateFlow()

    // 识别状态流映射
    private val _recognitionState = MutableStateFlow(SpeechService.RecognitionState.IDLE)
    override val recognitionState: StateFlow<SpeechService.RecognitionState> =
        _recognitionState.asStateFlow()

    // 记录音频帧计数
    private var audioFrameCounter = 0

    // 标记Vosk模型是否已加载
    private var voskModelLoaded = false

    // 标记服务是否已初始化
    private var initialized = false

    // Vosk 模型和识别器指针
    private var voskModel: CPointer<VoskModel>? = null
    private var voskRecognizer: CPointer<VoskRecognizer>? = null

    // 词级别识别是否已启用
    private var wordRecognitionEnabled = false

    // 是否使用关键词模式
    private var keywordModeEnabled = false

    // 关键词列表
    private val keywords = mutableListOf<String>()

    // 状态映射函数
    private fun mapRecognizerState(state: SpeechRecognizer.RecognitionState): SpeechService.RecognitionState {
        return when (state) {
            SpeechRecognizer.RecognitionState.IDLE -> SpeechService.RecognitionState.IDLE
            SpeechRecognizer.RecognitionState.INITIALIZING -> SpeechService.RecognitionState.INITIALIZING
            SpeechRecognizer.RecognitionState.LISTENING -> SpeechService.RecognitionState.LISTENING
            SpeechRecognizer.RecognitionState.PROCESSING -> SpeechService.RecognitionState.PROCESSING
            SpeechRecognizer.RecognitionState.ERROR -> SpeechService.RecognitionState.ERROR
        }
    }

    /**
     * 初始化语音服务
     *
     * @param deviceName ALSA设备名称
     * @param modelPath Vosk模型路径
     * @param sampleRate 音频采样率
     * @param micVolume 麦克风音量
     * @return 初始化是否成功
     */
    override fun initialize(
        deviceName: String,
        modelPath: String,
        sampleRate: Int,
        micVolume: Int
    ): Boolean {
        _recognitionState.value = SpeechService.RecognitionState.INITIALIZING

        println("[INFO] 初始化Vosk语音服务...")
        try {
            // 监听识别器状态变化
            serviceScope.launch {
                recognizer.recognitionState.collect { state ->
                    _recognitionState.value = mapRecognizerState(state)
                }
            }

            // 构建完整的模型路径
            println("[INFO] 使用模型: $modelPath")

            // 检查模型路径是否存在
            serviceScope.launch {
                val pathExists =
                    executeCommand("test -d \"$modelPath\" && echo \"exists\" || echo \"not exists\"").trim()
                if (pathExists != "exists") {
                    println("[WARN] Vosk模型路径可能不存在: $modelPath (结果: $pathExists)")
                } else {
                    println("[INFO] Vosk模型路径有效: $modelPath")
                }
            }

            // 尝试加载Vosk模型
            try {
                voskModelLoaded = loadVoskModel(modelPath)
                if (!voskModelLoaded) {
                    println("[ERROR] 无法加载Vosk模型")
                    _recognitionState.value = SpeechService.RecognitionState.ERROR
                    return false
                }
                println("[INFO] Vosk模型加载成功")
            } catch (e: Exception) {
                println("[ERROR] 加载Vosk模型异常: ${e.message}")
                e.printStackTrace()
                _recognitionState.value = SpeechService.RecognitionState.ERROR
                return false
            }

            println("[INFO] Vosk语音服务初始化成功")
            _recognitionState.value = SpeechService.RecognitionState.IDLE
            initialized = true
            return true
        } catch (e: Exception) {
            println("[ERROR] Vosk初始化异常: ${e.message}")
            e.printStackTrace()
            _recognitionState.value = SpeechService.RecognitionState.ERROR
            return false
        }
    }

    /**
     * 加载Vosk模型
     */
    private fun loadVoskModel(modelPath: String): Boolean {
        try {
            println("[INFO] 正在加载Vosk模型: $modelPath")

            // 尝试加载模型
            voskModel = vosk_model_new(modelPath)
            if (voskModel == null) {
                println("[ERROR] Vosk模型加载失败: 无效的模型指针")
                return false
            }

            // 创建识别器 - 16000为采样率
            try {
                voskRecognizer = vosk_recognizer_new(voskModel, TARGET_SAMPLE_RATE.toFloat())
                if (voskRecognizer == null) {
                    println("[ERROR] Vosk识别器创建失败: 无效的识别器指针")
                    return false
                }
                println("[INFO] Vosk语音识别器创建成功")
            } catch (e: Exception) {
                println("[ERROR] Vosk语音识别器创建失败: ${e.message}")
                return false
            }

            // 如果一切正常，设置模型加载状态为true
            voskModelLoaded = true
            println("[INFO] Vosk模型加载和初始化完成")
            return true
        } catch (e: Exception) {
            println("[ERROR] 加载Vosk模型异常: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    /**
     * 启用词级别识别
     * 让Vosk返回更详细的识别结果，包括单词级别的时间戳和置信度
     */
    fun enableWordRecognition() {
        if (!voskModelLoaded || voskRecognizer == null) {
            println("[ERROR] 无法启用词级别识别: Vosk模型未加载或识别器未初始化")
            return
        }

        try {
            // 调用Vosk API启用词级别识别
            vosk_recognizer_set_words(voskRecognizer, 1)
            wordRecognitionEnabled = true
            println("[INFO] 已启用Vosk词级别识别")

            // 设置最大替代结果数量为3
            vosk_recognizer_set_max_alternatives(voskRecognizer, 3)
            println("[INFO] 已设置Vosk最大替代结果数量为3")
        } catch (e: Exception) {
            println("[ERROR] 启用词级别识别失败: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 设置关键词检测模式
     * @param keywordList 要检测的关键词列表
     * @return 是否成功设置
     */
    fun setKeywords(keywordList: List<String>): Boolean {
        if (!voskModelLoaded || voskRecognizer == null) {
            println("[ERROR] 无法设置关键词: Vosk模型未加载或识别器未初始化")
            return false
        }

        if (keywordList.isEmpty()) {
            println("[WARN] 关键词列表为空，无法设置")
            return false
        }

        try {
            // 清除现有关键词
            keywords.clear()

            // 添加新的关键词
            keywords.addAll(keywordList)

            // 构建JSON格式的语法字符串，例如: ["你好", "小度", "嗨"]
            val grammarJson = keywords.joinToString(
                prefix = "[\"",
                separator = "\", \"",
                postfix = "\"]"
            )

            println("[INFO] 设置Vosk关键词语法: $grammarJson")

            // 调用Vosk API设置语法
            vosk_recognizer_set_grm(voskRecognizer, grammarJson)
            keywordModeEnabled = true

            println("[INFO] 已成功设置Vosk关键词检测模式，包含${keywords.size}个关键词")
            return true
        } catch (e: Exception) {
            println("[ERROR] 设置关键词检测模式失败: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    /**
     * 开始语音识别
     *
     * @return 是否成功启动识别
     */
    // 添加标志位防止重复启动
    private var isStartingRecognition = false

    override fun startRecognition(): Boolean {
        // 如果已经在启动过程中，直接返回，防止重复调用
        if (isStartingRecognition) {
            // 移除调试日志，减少输出
            return true
        }

        // 如果已经在监听状态，先停止当前会话再重新启动
        if (_recognitionState.value == SpeechService.RecognitionState.LISTENING) {
            // 简化日志输出
            stopRecognition()
            // 短暂延迟确保停止完成
            kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(100) }
        }

        try {
            isStartingRecognition = true

            // 重置识别结果
            _recognitionText.value = null

            // 启动识别器
            if (!recognizer.startRecognition()) {
                println("[ERROR] 启动Vosk语音识别失败")
                isStartingRecognition = false
                return false
            }

            // 监听识别结果
            recognitionJob = serviceScope.launch {
                recognizer.recognitionText.collectLatest { text ->
                    // 更新服务自身的识别结果流
                    _recognitionText.value = text
                }
            }

            return true
        } catch (e: Exception) {
            println("[ERROR] 启动语音识别异常: ${e.message}")
            e.printStackTrace()
            _recognitionState.value = SpeechService.RecognitionState.ERROR
            return false
        } finally {
            isStartingRecognition = false
        }
    }

    /**
     * 停止语音识别
     */
    // 添加标志位防止递归调用
    private var isStoppingRecognition = false

    override fun stopRecognition() {
        // 如果已经在停止过程中，直接返回，防止递归调用
        if (isStoppingRecognition) {
            println("[DEBUG] 已经在停止语音识别过程中，避免递归调用")
            return
        }

        try {
            isStoppingRecognition = true
            recognitionJob?.cancel()
            recognitionJob = null
            println("[INFO] 语音识别已停止")
        } catch (e: Exception) {
            println("[ERROR] 停止语音识别异常: ${e.message}")
            e.printStackTrace()
            _recognitionState.value = SpeechService.RecognitionState.ERROR
        } finally {
            isStoppingRecognition = false
        }
    }

    /**
     * 处理音频数据
     * @param audioData 音频数据
     * @return 是否成功处理
     */
    override fun processAudio(audioData: ShortArray): Boolean {
        if (_recognitionState.value != SpeechService.RecognitionState.LISTENING) {
            return false
        }

        try {
            // 检查音频数据有效性
            if (audioData.isEmpty()) {
                return false
            }

            // 检查Vosk模型是否已加载
            if (!voskModelLoaded) {
                println("[ERROR] Vosk模型未加载，无法处理音频")
                tryLoadVoskModel()
                return false
            }

            // 确保已正确初始化
            if (!initialized) {
                println("[ERROR] Vosk语音服务未初始化")
                return false
            }

            // 调试输出Vosk状态
            if (audioFrameCounter % 200 == 0) {
                println("[DEBUG-VOSK-SERVICE] 处理音频帧: #$audioFrameCounter, 大小: ${audioData.size}")
            }

            try {
                // 尝试使用实际的Vosk方法处理音频
                // 这里添加了详细的错误检查
                val result = processAudioInternal(audioData)
                if (!result) {
                    if (audioFrameCounter % 50 == 0) {
                        println("[ERROR] Vosk内部处理音频失败，可能是模型或API问题")
                    }
                }
                audioFrameCounter++
                return result

            } catch (e: Exception) {
                if (audioFrameCounter % 20 == 0) {
                    println("[ERROR] Vosk处理音频异常: ${e.message}")
                    e.printStackTrace()
                }
                return false
            }
        } catch (e: Exception) {
            if (audioFrameCounter % 20 == 0) {
                println("[ERROR] 音频处理总异常: ${e.message}")
                e.printStackTrace()
            }
            return false
        }
    }

    // 内部音频处理实现，调用实际的Vosk API
    private fun processAudioInternal(audioData: ShortArray): Boolean {
        try {
            // 检查音频数据是否有效
            if (audioData.isEmpty()) {
                return true // 跳过空数据
            }

            // 检查音频数据是否全为零
            var hasNonZeroData = false
            for (i in 0 until min(100, audioData.size)) {
                if (audioData[i] != 0.toShort()) {
                    hasNonZeroData = true
                    break
                }
            }

            if (!hasNonZeroData) {
                if (audioFrameCounter % 200 == 0) {
                    println("[WARN] 收到全零音频数据，跳过处理")
                }
                return true // 返回true避免上层警告
            }

            // 计算音频能量，确保有有效信号
            var sumSquares = 0.0
            for (sample in audioData) {
                sumSquares += (sample.toDouble() * sample.toDouble())
            }
            val rms = kotlin.math.sqrt(sumSquares / audioData.size)

            // 如果音频能量太低，直接跳过处理
            if (rms < 10.0) {
                return true // 返回true避免上层警告
            }

            // 验证Vosk识别器是否已初始化
            if (voskRecognizer == null) {
                if (audioFrameCounter % 100 == 0) {
                    println("[ERROR] Vosk识别器未初始化，无法处理音频")
                }
                return false
            }

            try {
                // 使用SoxrSingleton进行采样率转换
                val srcSampleRate = SOURCE_SAMPLE_RATE.toDouble()
                val dstSampleRate = TARGET_SAMPLE_RATE.toDouble()

                // 计算转换后的大小
                val outputSize = (audioData.size * (dstSampleRate / srcSampleRate)).toInt()

                // 分配转换后的缓冲区
                val tempFloatBuffer = nativeHeap.allocArray<FloatVar>(outputSize)
                val processedAudio = ShortArray(outputSize)

                // 创建临时短整型缓冲区指针
                val inputBuffer = nativeHeap.allocArray<ShortVar>(audioData.size)
                for (i in audioData.indices) {
                    inputBuffer[i] = audioData[i]
                }

                try {
                    // 执行重采样
                    val processedSize = SoxrSingleton.process(
                        srcSampleRate, dstSampleRate,
                        inputBuffer, audioData.size.toUInt(),
                        tempFloatBuffer, outputSize.toUInt()
                    )

                    // 将float转换回short
                    for (i in 0 until processedSize.toInt()) {
                        if (i < outputSize) {
                            // 将浮点值限制在[-1.0, 1.0]范围内
                            val sample = tempFloatBuffer[i]
                            val clampedSample =
                                if (sample > 1.0f) 1.0f else if (sample < -1.0f) -1.0f else sample
                            // 转换为short范围[-32768, 32767]
                            processedAudio[i] = (clampedSample * 32767.0f).toInt().toShort()
                        }
                    }

                    // 将音频数据传递给Vosk识别器处理
                    val acceptResult = vosk_recognizer_accept_waveform_s(
                        voskRecognizer,
                        processedAudio.refTo(0),
                        processedAudio.size
                    )

                    // 根据处理结果决定获取部分结果还是最终结果
                    if (acceptResult == 0) {
                        // 继续处理中，获取部分结果
                        updatePartialResult()
                    } else {
                        // 已完成一段识别，获取最终结果
                        updateFinalResult()
                    }

                    return true
                } finally {
                    // 释放临时缓冲区
                    nativeHeap.free(tempFloatBuffer.rawValue)
                    nativeHeap.free(inputBuffer.rawValue)
                }
            } catch (e: Exception) {
                if (audioFrameCounter % 50 == 0) {
                    println("[ERROR] Vosk音频处理异常: ${e.message}")
                }
                return false
            }
        } catch (e: Exception) {
            if (audioFrameCounter % 50 == 0) {
                println("[ERROR] Vosk音频处理总异常: ${e.message}")
                e.printStackTrace()
            }
            return false
        }
    }

    // 更新部分识别结果
    private fun updatePartialResult() {
        try {
            // 确保Vosk识别器已初始化
            if (voskRecognizer == null) return

            // 获取部分识别结果
            val partialResultPtr = vosk_recognizer_partial_result(voskRecognizer)
            if (partialResultPtr == null) return

            // 将C字符串转换为Kotlin字符串
            val partialResultJson = partialResultPtr.toKString()

            // 处理JSON结果
            processRecognitionResult(partialResultJson, false)
        } catch (e: Exception) {
            if (audioFrameCounter % 100 == 0) {
                println("[WARN] 获取部分识别结果失败: ${e.message}")
            }
        }
    }

    // 更新最终识别结果
    private fun updateFinalResult() {
        try {
            // 确保Vosk识别器已初始化
            if (voskRecognizer == null) return

            // 获取最终识别结果
            val finalResultPtr = vosk_recognizer_final_result(voskRecognizer)
            if (finalResultPtr == null) return

            // 将C字符串转换为Kotlin字符串
            val finalResultJson = finalResultPtr.toKString()

            // 处理JSON结果
            processRecognitionResult(finalResultJson, true)
        } catch (e: Exception) {
            if (audioFrameCounter % 100 == 0) {
                println("[WARN] 获取最终识别结果失败: ${e.message}")
            }
        }
    }

    // 处理识别结果JSON
    private fun processRecognitionResult(resultJson: String, isFinal: Boolean) {
        try {
            // 跳过空结果
            if (resultJson.isBlank()) return

            // 简单解析JSON，提取text字段
            // 示例JSON: {"text":"你好"}
            if (resultJson.contains("\"text\"")) {
                val startIndex = resultJson.indexOf("\"text\"") + 7
                val endIndex = resultJson.indexOf("\"", startIndex + 1)

                if (startIndex > 7 && endIndex > startIndex) {
                    val recognizedText = resultJson.substring(startIndex, endIndex)

                    // 检查是否为关键词模式
                    if (keywordModeEnabled) {
                        // 关键词模式下，只有文本包含关键词列表中的词时才更新
                        val matchedKeyword = keywords.find {
                            recognizedText.contains(it, ignoreCase = true)
                        }

                        if (matchedKeyword != null) {
                            if (_recognitionText.value != matchedKeyword) {
                                println("[INFO] 检测到关键词: $matchedKeyword")
                                _recognitionText.value = matchedKeyword
                            }
                        }
                    } else {
                        // 非关键词模式，直接更新识别文本
                        if (_recognitionText.value != recognizedText && recognizedText.isNotBlank()) {
                            if (isFinal) {
                                println("[INFO] 最终识别结果: $recognizedText")
                            } else if (audioFrameCounter % 50 == 0) {
                                println("[DEBUG] 部分识别结果: $recognizedText")
                            }
                            _recognitionText.value = recognizedText
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("[ERROR] 处理识别结果异常: ${e.message}")
        }
    }

    // 尝试重新加载Vosk模型
    private fun tryLoadVoskModel() {
        try {
            println("[INFO] 尝试重新加载Vosk模型...")

            // 释放当前模型资源
            releaseVoskResources()

            // 重新加载模型
            voskModelLoaded = loadVoskModel("/usr/local/share/vosk/model")

            if (voskModelLoaded) {
                println("[INFO] Vosk模型重新加载成功")
            } else {
                println("[ERROR] Vosk模型重新加载失败")
            }
        } catch (e: Exception) {
            println("[ERROR] 重载Vosk模型失败: ${e.message}")
        }
    }

    /**
     * 释放资源
     */
    private var isReleasing = false

    override fun release() {
        // 防止递归调用
        if (isReleasing) {
            println("[DEBUG] 已经在释放资源过程中，避免递归调用")
            return
        }

        try {
            isReleasing = true
            // 直接取消任务，不调用stopRecognition避免潜在的递归
            recognitionJob?.cancel()
            recognitionJob = null

            // 释放Vosk资源
            releaseVoskResources()

            // 设置状态为IDLE
            _recognitionState.value = SpeechService.RecognitionState.IDLE
            println("[INFO] Vosk资源已释放")
        } catch (e: Exception) {
            println("[WARN] 释放Vosk资源时出错: ${e.message}")
            _recognitionState.value = SpeechService.RecognitionState.ERROR
        } finally {
            isReleasing = false
        }
    }

    /**
     * 重新初始化Vosk处理管道
     * 在发生连续错误时调用此方法来尝试恢复
     */
    fun reinitializeVosk(): Boolean {
        println("[INFO] 开始重新初始化Vosk处理管道...")

        try {
            // 先释放现有资源
            releaseVoskResources()

            // 等待资源释放完成
            kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(500) }

            // 重新初始化 - 临时代码，应该由外部调用提供正确的参数
            return initialize("default", "/usr/local/share/vosk/model", 16000, 80)
        } catch (e: Exception) {
            println("[ERROR] 重新初始化Vosk失败: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    /**
     * 释放Vosk相关资源
     */
    private fun releaseVoskResources() {
        try {
            // 停止识别
            stopRecognition()

            // 释放Vosk识别器
            if (voskRecognizer != null) {
                vosk_recognizer_free(voskRecognizer)
                voskRecognizer = null
                println("[DEBUG] Vosk识别器已释放")
            }

            // 释放Vosk模型
            if (voskModel != null) {
                vosk_model_free(voskModel)
                voskModel = null
                println("[DEBUG] Vosk模型已释放")
            }

            // 重置状态
            voskModelLoaded = false
            initialized = false
            keywordModeEnabled = false
            wordRecognitionEnabled = false
            keywords.clear()

            println("[INFO] Vosk资源已释放")
        } catch (e: Exception) {
            println("[WARN] 释放Vosk资源时出错: ${e.message}")
        }
    }

    /**
     * 运行Vosk诊断
     * 检查Vosk相关组件是否正常工作
     */
    fun runVoskDiagnostics(): String {
        val diagnostics = StringBuilder()
        diagnostics.append("=== Vosk诊断报告 ===\n")

        try {
            // 检查模型状态
            diagnostics.append("模型已加载: $voskModelLoaded\n")

            // 检查初始化状态
            diagnostics.append("服务已初始化: $initialized\n")

            // 检查识别状态
            diagnostics.append("当前识别状态: ${_recognitionState.value}\n")

            // 检查处理的帧数
            diagnostics.append("已处理音频帧数: $audioFrameCounter\n")

            // 检查关键词模式
            diagnostics.append("关键词模式: $keywordModeEnabled, 词数: ${keywords.size}\n")
            if (keywords.isNotEmpty()) {
                diagnostics.append("关键词列表: ${keywords.joinToString(", ")}\n")
            }

            // 检查系统资源
            serviceScope.launch {
                val memInfo = executeCommand("free -h | grep Mem").trim()
                diagnostics.append("系统内存: $memInfo\n")

                val cpuInfo =
                    executeCommand("top -bn1 | grep 'Cpu(s)' | awk '{print $2 + $4}'").trim()
                diagnostics.append("CPU使用率: $cpuInfo%\n")
            }

            diagnostics.append("=== 诊断完成 ===")
        } catch (e: Exception) {
            diagnostics.append("诊断时出错: ${e.message}\n")
        }

        val report = diagnostics.toString()
        println(report)
        return report
    }
}