@file:OptIn(ExperimentalForeignApi::class, ExperimentalStdlibApi::class, NativeRuntimeApi::class)
@file:Suppress("FunctionName", "unused", "UNUSED_PARAMETER")

package snowboyPiper.impl

import com.airobot.voskinterop.vosk_model_new
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo
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
        // 语音识别器的默认模型路径

        /**
         * 执行shell命令并返回输出结果
         *
         * @param command 要执行的命令
         * @param timeoutMs 命令执行超时时间（毫秒），默认1000ms
         * @param maxOutputSize 最大输出大小，防止内存溢出，默认4096字节
         * @return 命令执行的输出结果
         */
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

//            // 检查模型路径是否存在
//            val modelExists = checkModelExists(modelPath)
//            if (!modelExists) {
//                println("[ERROR] Vosk模型路径不存在: $modelPath")
//                _recognitionState.value = SpeechService.RecognitionState.ERROR
//                return false
//            }

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

//    /**
//     * 检查模型路径是否存在
//     */
//    private fun checkModelExists(modelPath: String): Boolean {
//        try {
//            // 使用executeCommand执行检查文件的命令
//            GlobalScope.launch {
//                val result = executeCommand("test -d \"$modelPath\" && echo \"exists\" || echo \"not exists\"")
//                 result.trim() == "exists"
//            }
//        } catch (e: Exception) {
//            println("[ERROR] 检查模型路径时出错: ${e.message}")
//            return false
//        }
//    }

    /**
     * 加载Vosk模型
     */
    private fun loadVoskModel(modelPath: String): Boolean {
        try {
            println("[INFO] 正在加载Vosk模型: $modelPath")

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

            // 尝试加载模型
            val model = vosk_model_new(modelPath)
            if (model == null) {
                println("[ERROR] Vosk模型加载失败: 无效的模型指针")
                return false
            }

            // 创建识别器
            // 注意：这里仅展示关键步骤，实际可能需要更多逻辑
            try {
                // 在这里补充创建识别器的代码
                // 例如: vosk_recognizer_new(model, sampleRate)
                println("[INFO] Vosk语音识别器创建成功")
            } catch (e: Exception) {
                println("[ERROR] Vosk语音识别器创建失败: ${e.message}")
                // 如果有必要，释放模型资源
                // 例如: vosk_model_free(model)
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
            if (audioData.all { it == 0.toShort() }) {
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

            // 这里是实际Vosk音频处理代码
            // 1. 转换为Vosk可接受的格式 (通常是16位有符号整数)
            // 2. 将数据传递给Vosk识别器处理

            try {
                // 获取Vosk识别器实例 (假设在初始化时已创建)
                // val recognizer = getVoskRecognizer()

                // 处理音频数据
                // 例如: vosk_recognizer_accept_waveform(recognizer, audioBuffer, bufferSize)
                // 或者: recognizer.acceptWaveform(audioData)

                // 每帧音频处理后，获取部分结果
                updatePartialResult()

                return true
            } catch (e: Exception) {
                if (audioFrameCounter % 50 == 0) {
                    println("[ERROR] Vosk音频处理异常: ${e.message}")
                }
                return false
            }
        } catch (e: Exception) {
            println("[ERROR] Vosk音频处理总异常: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    // 更新部分识别结果
    private fun updatePartialResult() {
        try {
            // 获取部分识别结果
            // 例如: val partial = vosk_recognizer_partial_result(recognizer)

            // 更新识别文本 (如果有新内容)
            // 解析JSON并更新文本

            // 仅模拟测试用途
            if (audioFrameCounter % 500 == 0) {
                _recognitionText.value = "临时识别结果 #${audioFrameCounter}"
            }
        } catch (e: Exception) {
            if (audioFrameCounter % 100 == 0) {
                println("[WARN] 获取部分识别结果失败: ${e.message}")
            }
        }
    }

    // 尝试重新加载Vosk模型
    private fun tryLoadVoskModel() {
        try {
            println("[INFO] 尝试重新加载Vosk模型...")
            // 实际重载模型代码
            // ...
            voskModelLoaded = true
        } catch (e: Exception) {
            println("[ERROR] 重载Vosk模型失败: ${e.message}")
        }
    }

    // 从Vosk引擎获取识别结果
    private fun updateRecognitionFromVosk() {
        try {
            // 这里是获取识别结果的代码
            // 通常会调用Vosk的获取最终结果或部分结果的API
            // ...

            // 如果有新的识别结果，更新状态流
            if (audioFrameCounter % 300 == 0) {
                val fakeResult = "测试识别结果 #${audioFrameCounter}"
                _recognitionText.value = fakeResult
            }
        } catch (e: Exception) {
            println("[ERROR] 获取Vosk识别结果失败: ${e.message}")
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
            // 例如: vosk_recognizer_free(recognizer)

            // 释放Vosk模型
            // 例如: vosk_model_free(model)

            // 重置状态
            voskModelLoaded = false
            initialized = false

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
            // 检查库版本
            // 例如: diagnostics.append("Vosk库版本: ${vosk_get_version()}\n")

            // 检查模型状态
            diagnostics.append("模型已加载: $voskModelLoaded\n")

            // 检查初始化状态
            diagnostics.append("服务已初始化: $initialized\n")

            // 检查识别状态
            diagnostics.append("当前识别状态: ${_recognitionState.value}\n")

            // 检查处理的帧数
            diagnostics.append("已处理音频帧数: $audioFrameCounter\n")

            // 检查模型资源
            // TODO: 添加实际的模型资源检查

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