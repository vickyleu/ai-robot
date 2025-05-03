@file:OptIn(ExperimentalForeignApi::class, ExperimentalStdlibApi::class, NativeRuntimeApi::class)
@file:Suppress("FunctionName", "unused", "UNUSED_PARAMETER")

package snowboyPiper.impl

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
class VoskSpeechService(private val recognizer:VoskSpeechRecognizer) : SpeechService {
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
            timeoutMs: Long= 1000L,
            maxOutputSize: Int= 4096
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
    override val recognitionState: StateFlow<SpeechService.RecognitionState> = _recognitionState.asStateFlow()

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



            println("[INFO] Vosk语音服务初始化成功")
            _recognitionState.value = SpeechService.RecognitionState.IDLE
            return true
        } catch (e: Exception) {
            println("[ERROR] Vosk初始化异常: ${e.message}")
            e.printStackTrace()
            _recognitionState.value = SpeechService.RecognitionState.ERROR
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
            return recognizer.processAudio(audioData)
        } catch (e: Exception) {
            println("[ERROR] 处理音频数据异常: ${e.message}")
            e.printStackTrace()
            return false
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
}