@file:OptIn(ExperimentalForeignApi::class, ExperimentalStdlibApi::class, NativeRuntimeApi::class)
@file:Suppress("FunctionName", "unused", "UNUSED_PARAMETER")

package com.airobot.device.yanapi.com.airobot.device.yanapi.com.airobot.device.yanapi.vosk

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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.posix.FILE
import platform.posix.fgets
import platform.posix.pclose
import platform.posix.popen
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

/**
 * YAN设备Vosk语音服务
 *
 * 该类封装了YanVoskSpeechRecognizer，提供了更高级别的API接口，
 * 使得在Kotlin Native环境中更容易使用ALSA麦克风采集和Vosk语音识别功能。
 *
 * 增强功能：
 * 1. 音频信号增强和降噪
 * 2. 麦克风状态诊断
 * 3. 详细的调试信息输出
 */
class YanVoskSpeechService {
    companion object {
        // 语音识别器的默认模型路径
        const val DEFAULT_MODEL = "vosk-model-small-cn-0.22"

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
            timeoutMs: Long = 1000,
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

    // 语音识别器实例
    private val recognizer = YanVoskSpeechRecognizer()

    // 协程作用域和任务
    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private var recognitionJob: Job? = null

    // 识别结果流
    private val _recognitionText = MutableStateFlow<String?>(null)
    val recognitionText: StateFlow<String?> = _recognitionText.asStateFlow()

    // 识别状态流
    val recognitionState: StateFlow<YanVoskSpeechRecognizer.RecognitionState>
        get() = recognizer.recognitionState

    /**
     * 初始化语音服务
     *
     * @param deviceName ALSA设备名称
     * @param modelPath Vosk模型路径
     * @param sampleRate 音频采样率
     * @return 初始化是否成功
     */
    fun initialize(
        deviceName: String = "default",
        modelPath: String = DEFAULT_MODEL,
        sampleRate: Int = 16000,
        micVolume: Int = 80
    ): Boolean {
        return recognizer.initialize(deviceName, "/usr/local/share/yanshee-model/$modelPath".apply {
            println("Model path: $this\n")
        }, sampleRate, micVolume)
    }

    /**
     * 开始语音识别
     *
     * @return 是否成功启动识别
     */
    fun startRecognition(): Boolean {
        if (!recognizer.startRecognition()) {
            return false
        }

        // 监听识别结果
        recognitionJob = serviceScope.launch {
            recognizer.recognitionResult.collect { result ->
                result?.let {
                    if (!it.isPartial) {
                        // 只处理最终结果
                        _recognitionText.value = it.text
                    }
                }
            }
        }

        return true
    }

    /**
     * 停止语音识别
     */
    fun stopRecognition() {
        recognizer.stopRecognition(true)
        recognitionJob?.cancel()
        recognitionJob = null
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
        var result: String? = null

        // 重置结果
        _recognitionText.value = null

        // 启动识别
        if (!startRecognition()) {
            return null
        }

        try {
            // 等待结果或超时
            withTimeout(timeoutMs) {
                while (_recognitionText.value == null) {
                    kotlinx.coroutines.delay(100)
                }
                result = _recognitionText.value
            }
        } catch (e: TimeoutCancellationException) {
            // 超时处理
        } finally {
            // 停止识别
            stopRecognition()
        }

        return result
    }

    /**
     * 设置麦克风音量
     *
     * @param volume 音量值 (0-100)
     * @return 设置是否成功
     */
    fun setMicrophoneVolume(volume: Int): Boolean {
        return recognizer.setMicrophoneVolume(volume)
    }

    /**
     * 获取当前麦克风音量
     *
     * @return 当前音量值 (0-100)
     */
    fun getMicrophoneVolume(): Int {
        return recognizer.getMicrophoneVolume()
    }

    /**
     * 检查麦克风状态
     *
     * 该方法会尝试捕获一段音频并分析其振幅，以检查麦克风是否正常工作
     * 同时执行arecord -l命令检查系统麦克风设备
     *
     * @param timeout 检查超时时间（毫秒），默认1000ms
     * @return 麦克风状态信息
     */
    suspend fun checkMicrophoneStatus(timeout: Long = 1000): String {
        // 执行arecord -l命令检查系统麦克风设备，设置超时
        val arecordOutput = executeCommand("arecord -l", timeoutMs = 500)
        println("[INFO] 系统麦克风设备检查结果:")
        println(arecordOutput)
        var recognitionStarted = false
        try {
            // 启动识别以触发音频捕获和分析
            if (!startRecognition()) {
                return "麦克风状态检查失败：无法启动语音识别\n系统麦克风设备检查结果:\n$arecordOutput"
            }
            recognitionStarted = true
            // 使用withTimeout避免长时间阻塞
            withTimeout(timeout+500) { // 增加500ms以确保有足够时间捕获音频,因为循环刚好是timeout的总时间,加上GC的执行时间, 最后一个循环会导致超时
                // 短暂等待以收集音频数据，分段收集避免内存压力
                repeat(5) {
                    kotlinx.coroutines.delay(timeout / 5)
                    // 强制GC回收内存
                    GC.collect()
                }
            }
        } catch (e: TimeoutCancellationException) {
            println("[WARNING] 麦克风状态检查超时")
        } catch (e: Exception) {
            println("[ERROR] 麦克风状态检查异常: ${e.message}")
        } finally {
            // 确保停止识别并释放资源
            if (recognitionStarted) {
                stopRecognition()
            }
        }

        // 返回状态信息
        return "麦克风状态检查完成，请查看日志输出中的[DEBUG]音频数据信息以诊断问题\n系统麦克风设备检查结果:\n$arecordOutput"
    }


    /**
     * 释放资源
     */
    fun release() {
        stopRecognition()
        recognizer.release()
    }
}