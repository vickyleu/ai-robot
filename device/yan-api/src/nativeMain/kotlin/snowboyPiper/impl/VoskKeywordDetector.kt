@file:OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)

package snowboyPiper.impl

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.set
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import snowboyPiper.interfaces.AudioAnalyzer
import snowboyPiper.interfaces.AudioPlayer
import snowboyPiper.interfaces.KeywordDetector
import snowboyPiper.interfaces.KeywordDetector.DetectorState
import snowboyPiper.interfaces.KeywordDetector.DetectorState.ERROR
import snowboyPiper.interfaces.KeywordDetector.DetectorState.NoEvent
import snowboyPiper.interfaces.VoiceStateManager
import kotlin.math.abs
import kotlin.math.min
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime

/**
 * 基于Vosk的关键词检测器实现
 * 使用Vosk语音识别引擎进行关键词检测
 */
class VoskKeywordDetector(
    private val voskRecognizer: VoskSpeechRecognizer,
    private val audioAnalyzer: AudioAnalyzer,
    private val voiceStateManager: VoiceStateManager
) : KeywordDetector {

    // 检测状态
    private val _detectionState = MutableStateFlow(KeywordDetector.DetectionState.IDLE)
    override val detectionState: StateFlow<KeywordDetector.DetectionState> =
        _detectionState.asStateFlow()

    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Default)

    // 去抖动控制
    private var lastDetectionTime = 0L
    private val debounceTimeMs = 500L // 0.5秒去抖动时间

    // 回声消除相关变量
    private var lastPlaybackTime = 0L
    private val echoSuppressionTime = 1000L // 回声抑制时间

    // 音频活动检测变量
    private var lastVoiceActivityTime = 0L
    private var isInContinuousSpeech = false
    private val voiceContinuityThreshold = 800L // 800毫秒内的声音视为连续语音
    private val silencePauseThreshold = 1000L // 1秒无声视为停顿


    // 关键词列表
    private val keywords = mutableListOf<String>()

    // 最后一次识别结果
    private var lastRecognizedText = ""

    // 检测模式
    private enum class DetectionMode {
        COMMAND_MODE,  // 命令模式：检测特定关键词，例如"嘿，机器人"
        CONTINUOUS_MODE // 连续模式：持续识别语音，适用于对话
    }

    // 当前检测模式
    private var detectionMode = DetectionMode.COMMAND_MODE

    // 指示是否已初始化
    private var isInitialized = false

    // 调试计数器
    private var totalFramesProcessed = 0
    private var voiceFramesDetected = 0
    private var keywordDetections = 0
    private var lastDebugLogTime = 0L
    private val debugLogIntervalMs = 30000L // 改为30秒输出一次调试日志，原来是5秒

    // 设备选择器，用于获取系统信息
    private val deviceSelector = LinuxAudioDeviceSelector()

    // 是否启用详细日志
    private var verboseLogging = false

    // 错误计数器
    private var voskErrorCount = 0

    init {
        println("[DEBUG] VoskKeywordDetector 初始化，检查系统环境")

        if (deviceSelector.isRaspberryPi()) {
            println(
                "[DEBUG] 运行在树莓派环境下，硬件: ${
                    deviceSelector.executeSystemCommand("cat /proc/device-tree/model 2>/dev/null || echo '未知'")
                        .trim()
                }"
            )
            println("[DEBUG] 内存信息: ${deviceSelector.executeSystemCommand("free -h").trim()}")
            println(
                "[DEBUG] CPU信息: ${
                    deviceSelector.executeSystemCommand("lscpu | grep 'Model name\\|MHz\\|Core'")
                        .trim()
                }"
            )
        } else {
            println("[DEBUG] 运行在非树莓派环境")
        }

        // 监听语音识别结果
        scope.launch {
            voskRecognizer.recognitionText.collect { text ->
                if (text != null && text.isNotEmpty()) {
                    val processingTime = measureTime {
                        processRecognizedText(text)
                    }
                    println("[DEBUG] 处理识别文本耗时: ${processingTime.inWholeMilliseconds}ms，文本长度: ${text.length}")
                }
            }
        }

        // 每隔一段时间输出调试统计信息
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(debugLogIntervalMs)
                logDebugStats()
            }
        }

        // 启动控制台命令处理协程
        scope.launch {
            startCommandProcessor()
        }

        println("[INFO] 输入 'help' 查看可用命令")
    }

    /**
     * 输出调试统计信息
     */
    private fun logDebugStats() {
        val currentTime = Clock.System.now().toEpochMilliseconds()

        // 计算帧率
        val timeElapsed = (currentTime - lastDebugLogTime) / 1000.0
        if (timeElapsed > 0 && lastDebugLogTime > 0) {
            val fps = totalFramesProcessed / timeElapsed

            // 简化输出，只输出必要信息
            println("[STATS] 处理帧率: ${fps} fps, 检测到语音的帧: $voiceFramesDetected/${totalFramesProcessed}")
            println("[STATS] 模式: $detectionMode, 状态: ${_detectionState.value}")

            // 只在详细模式下输出关键词列表
            if (verboseLogging) {
                println("[STATS] 已添加的关键词: ${keywords.joinToString(", ")}")
            }

            // 重置计数器
            totalFramesProcessed = 0
            voiceFramesDetected = 0
            keywordDetections = 0
        }

        lastDebugLogTime = currentTime
    }

    /**
     * 初始化检测器
     * @param resourcePath 资源文件路径
     * @param modelPath 模型文件路径
     * @param sensitivity 灵敏度，范围0-1
     * @return 初始化是否成功
     */
    override fun initialize(resourcePath: String, modelPath: String, sensitivity: Float): Boolean {
        _detectionState.value = KeywordDetector.DetectionState.INITIALIZING

        try {
            println("[DEBUG] 开始初始化Vosk关键词检测器，模型路径: $modelPath")

            // 记录初始化时间
            val initTime = measureTime {
                // 初始化Vosk语音识别器
                val recordDevice = voskRecognizer.recordDevice()
                if (!voskRecognizer.initialize(recordDevice, modelPath, "default", 16000, 80)) {
                    println("[ERROR] Vosk语音识别器初始化失败")
                    _detectionState.value = KeywordDetector.DetectionState.ERROR
                    return false
                }
            }

            println("[INFO] Vosk关键词检测器初始化成功，耗时: ${initTime.inWholeMilliseconds}ms")

            // 列出当前的音频设备状态
            if (deviceSelector.isRaspberryPi()) {
                println("[DEBUG] ALSA音频设备列表:")
                val alsaDevices = deviceSelector.getALSADevices()
                alsaDevices.forEachIndexed { index, device ->
                    println("[DEBUG] 设备 #${index}: ID=${device.id}, 名称=${device.name}, 输入=${device.isInput}, 输出=${device.isOutput}")
                    println("[DEBUG]   描述: ${device.description}")
                }

                // 检查ALSA配置
                val alsaConfig =
                    deviceSelector.executeSystemCommand("cat ~/.asoundrc 2>/dev/null || echo '未找到ALSA配置'")
                println("[DEBUG] 当前ALSA配置:\n$alsaConfig")
            }

            isInitialized = true
            _detectionState.value = KeywordDetector.DetectionState.LISTENING
            return true
        } catch (e: Exception) {
            println("[ERROR] Vosk关键词检测器初始化异常: ${e.message}")
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
        if (!isInitialized) {
            println("[ERROR] Vosk关键词检测器未初始化")
            return ERROR
        }

        if (_detectionState.value != KeywordDetector.DetectionState.LISTENING) {
            _detectionState.value = KeywordDetector.DetectionState.LISTENING
        }

        try {
            // 更详细的输入数据日志 - 仅在详细模式下输出
            if (verboseLogging && totalFramesProcessed % 500 == 0) {
                println("[DEBUG-INPUT] 收到音频数据: 缓冲区大小=${buffer.size}, 帧数=$frameCount")
            }

            // 检查输入音频数据是否有效
            if (frameCount <= 0 || buffer.isEmpty()) {
                // 仅在详细模式下输出警告
                if (verboseLogging) {
                    println("[WARN] 收到无效的音频数据: frameCount=$frameCount, bufferSize=${buffer.size}")
                }
                return NoEvent
            }

            // 更新统计信息
            totalFramesProcessed++

            // 检查是否在回声抑制时间内
            val currentTime = Clock.System.now().toEpochMilliseconds()
            if (currentTime - lastPlaybackTime < echoSuppressionTime) {
                // 在回声抑制时间内直接忽略，不做任何处理
                return NoEvent
            }

            // 计算音频能量
            var sumSquares = 0.0
            var maxSample = 0.0
            var zeroCrossings = 0
            var clippedSamples = 0  // 检测截幅
            val clippingThreshold = 30000  // 超过这个值认为可能发生截幅

            // 检查缓冲区中是否有数据样本 - 仅在详细模式下输出警告
            if (verboseLogging && buffer.all { it == 0.toShort() } && totalFramesProcessed % 500 == 0) {
                println("[WARN] 收到全0音频数据帧，可能是麦克风未工作或静音")
            }

            for (i in 0 until frameCount - 1) {
                val sampleValue = buffer[i].toDouble()
                sumSquares += (sampleValue * sampleValue)
                maxSample = maxOf(maxSample, abs(sampleValue))

                // 计算过零率 (zero-crossing rate)
                if ((buffer[i] >= 0 && buffer[i + 1] < 0) || (buffer[i] < 0 && buffer[i + 1] >= 0)) {
                    zeroCrossings++
                }

                // 检测截幅
                if (abs(buffer[i].toInt()) > clippingThreshold) {
                    clippedSamples++
                }
            }

            val rms = kotlin.math.sqrt(sumSquares / frameCount)
            val zcr = zeroCrossings.toDouble() / (frameCount - 1)
            val clippingRatio = clippedSamples.toDouble() / frameCount
            // 能量阈值检查，确保能捕捉到正常音量的人声
            val hasCurrentVoiceActivity = rms >= 25.0

            // 输出音频特征 - 只在详细模式下输出，且频率降低
            if (verboseLogging && (totalFramesProcessed % 1000 == 0 ||
                        (hasCurrentVoiceActivity && totalFramesProcessed % 200 == 0))
            ) {
                println("[DEBUG-AUDIO] RMS能量: ${rms}, 最大振幅: ${maxSample}")
            }


            // 更新统计信息
            if (hasCurrentVoiceActivity) {
                voiceFramesDetected++

                // 只在有语音且详细模式下输出，降低频率
                if (verboseLogging && totalFramesProcessed % 100 == 0) {
                    println("[DEBUG-VOICE] 检测到语音活动: RMS=${rms}")
                }
            }

            // 管理连续语音状态
            if (hasCurrentVoiceActivity) {
                // 更新高能量音频时间戳
                lastVoiceActivityTime = currentTime

                // 如果时间足够近，判定为连续语音
                if (currentTime - lastVoiceActivityTime < voiceContinuityThreshold) {
                    isInContinuousSpeech = true
                } else if (currentTime - lastVoiceActivityTime > silencePauseThreshold) {
                    isInContinuousSpeech = false
                }
            }

            // 能量阈值过滤
            if (rms < 25.0) {
                return NoEvent
            }

            // 记录处理音频的时间 - 但不输出详细日志
            val processingTime = measureTime {
                // 使用Vosk处理音频，进行语音识别
                val processResult = voskRecognizer.processAudio(buffer)
                // 只有在处理失败时才输出警告，并添加恢复机制
                if (!processResult) {
                    // 记录Vosk处理失败次数
                    voskErrorCount++

                    // 降低警告输出频率
                    if (voskErrorCount % 50 == 1) {
                        println("[WARN] Vosk处理音频失败 (${voskErrorCount}次)")
                    }

                    // 当错误次数达到阈值时尝试修复
                    if (voskErrorCount > 500) {
                        voskErrorCount = 0
                        // 异步尝试修复
                        scope.launch {
                            println("[INFO] 尝试重新初始化语音识别器...")
                            runDiagnostics() // 运行诊断

                            // 尝试重新初始化
                            val success = reinitializeVosk()
                            if (success) {
                                println("[INFO] 语音识别器重新初始化成功")
                            } else {
                                println("[ERROR] 语音识别器重新初始化失败")
                            }
                        }
                    }
                } else {
                    // 成功处理时重置错误计数
                    if (voskErrorCount > 0) {
                        voskErrorCount = 0
                    }
                }
            }

            // 根据当前模式决定是否播放音频
            if (detectionMode == DetectionMode.CONTINUOUS_MODE && isInContinuousSpeech) {
                // 创建一个临时缓冲区
                val bufferOriginal = buffer.copyOf(min(frameCount, 8000))

                // 播放原始录音数据
                val playResult = player.playAudio(bufferOriginal.refToArray(), bufferOriginal.size)

                // 记录回声抑制时间
                lastPlaybackTime = currentTime

                // 只在详细模式下输出
                if (verboseLogging) {
                    println("[DEBUG] 播放音频，帧数: ${bufferOriginal.size}, 结果: $playResult")
                }
            }

            // 返回检测状态
            // 由于Vosk的结果是异步的，实际检测结果会在processRecognizedText中处理
            return NoEvent
        } catch (e: Exception) {
            println("[ERROR] 关键词检测异常: ${e.message}")
            e.printStackTrace()
            _detectionState.value = KeywordDetector.DetectionState.ERROR
            return ERROR
        }
    }

    /**
     * 处理识别文本，检查是否包含关键词
     */
    private fun processRecognizedText(text: String) {
        if (text.isEmpty() || text == lastRecognizedText) {
            return
        }

        lastRecognizedText = text
        println("[INFO] Vosk识别文本: $text")

        // 转换为小写进行匹配
        val lowerText = text.lowercase()

        // 检查是否包含任何关键词
        val detectedKeyword = keywords.firstOrNull { keyword ->
            lowerText.contains(keyword.lowercase())
        }

        if (detectedKeyword != null) {
            keywordDetections++
            println("[INFO] 检测到关键词: $detectedKeyword")

            // 去抖动
            val currentTime = Clock.System.now().toEpochMilliseconds()
            if (currentTime - lastDetectionTime < debounceTimeMs) {
                return
            }

            // 更新状态
            lastDetectionTime = currentTime
            _detectionState.value = KeywordDetector.DetectionState.DETECTED

            // 如果在命令模式下检测到关键词，切换到连续模式
            if (detectionMode == DetectionMode.COMMAND_MODE) {
                detectionMode = DetectionMode.CONTINUOUS_MODE
                println("[INFO] 切换到连续识别模式")
            }
        } else if (detectionMode == DetectionMode.CONTINUOUS_MODE) {
            // 在连续模式下，检查是否超过了沉默阈值
            val currentTime = Clock.System.now().toEpochMilliseconds()
            if (currentTime - lastVoiceActivityTime > silencePauseThreshold * 3) {
                detectionMode = DetectionMode.COMMAND_MODE
                println("[INFO] 检测到长时间沉默，切换回命令模式")
            }
        }
    }

    /**
     * 添加关键词
     * @param keyword 关键词
     */
    fun addKeyword(keyword: String) {
        keywords.add(keyword)
        println("[DEBUG] 添加关键词: $keyword, 当前关键词列表: ${keywords.joinToString(", ")}")
    }

    /**
     * 移除关键词
     * @param keyword 关键词
     */
    fun removeKeyword(keyword: String) {
        keywords.remove(keyword)
        println("[DEBUG] 移除关键词: $keyword, 当前关键词列表: ${keywords.joinToString(", ")}")
    }

    /**
     * 清空关键词列表
     */
    fun clearKeywords() {
        println("[DEBUG] 清空所有关键词")
        keywords.clear()
    }

    /**
     * 释放资源
     */
    override fun release() {
        try {
            println("[DEBUG] 释放Vosk关键词检测器资源")
            isInitialized = false
            _detectionState.value = KeywordDetector.DetectionState.IDLE
        } catch (e: Exception) {
            println("[WARN] 释放资源时出错: ${e.message}")
            _detectionState.value = KeywordDetector.DetectionState.ERROR
        }
    }

    // 扩展函数：将ShortArray转换为CPointer<ShortVar>
    private fun ShortArray.refToArray() = memScoped {
        val pointer = nativeHeap.allocArray<ShortVar>(this@refToArray.size)
        for (i in this@refToArray.indices) {
            pointer[i] = this@refToArray[i]
        }
        pointer
    }

    /**
     * 运行系统诊断，检查可能的问题
     * 这个函数可以通过外部调用来进行故障排查
     */
    fun runDiagnostics() {
        println("\n[DIAGNOSTICS] ======== 开始系统诊断 ========")

        try {
            // 1. 检查系统环境
            val isRaspberryPi = deviceSelector.isRaspberryPi()
            println("[DIAGNOSTICS] 运行环境: ${if (isRaspberryPi) "树莓派" else "其他系统"}")

            // 2. 音频设备状态
            val alsaDevices = deviceSelector.getALSADevices()
            val inputDevices = alsaDevices.filter { it.isInput }
            val outputDevices = alsaDevices.filter { it.isOutput }

            println("[DIAGNOSTICS] 检测到: 输入设备=${inputDevices.size}, 输出设备=${outputDevices.size}")

            // 3. 检查音频流程状态
            println("[DIAGNOSTICS] 初始化状态: $isInitialized, 检测状态: ${_detectionState.value}")
            println("[DIAGNOSTICS] 当前模式: $detectionMode, 关键词数量: ${keywords.size}")

            // 4. 检查统计信息
            println("[DIAGNOSTICS] 统计: 总帧数=$totalFramesProcessed, 语音帧=$voiceFramesDetected, 检测次数=$keywordDetections")

            // 5. 检测潜在问题 - 只输出存在的问题
            var problemsFound = false

            // 检查是否没有音频帧处理
            if (totalFramesProcessed == 0) {
                problemsFound = true
                println("[DIAGNOSTICS] 警告: 未处理任何音频帧，可能是音频采集未工作")
                println("[DIAGNOSTICS] 建议: 检查麦克风连接和权限，确保ALSA设备正确配置")
            }

            // 检查帧率是否过低
            val currentTime = Clock.System.now().toEpochMilliseconds()
            val timeElapsed = (currentTime - lastDebugLogTime) / 1000.0
            if (timeElapsed > 0 && lastDebugLogTime > 0) {
                val fps = totalFramesProcessed / timeElapsed
                if (fps < 1.0) {
                    problemsFound = true
                    println("[DIAGNOSTICS] 警告: 帧率过低 ($fps fps)，可能影响检测性能")
                    println("[DIAGNOSTICS] 建议: 检查系统负载和音频配置")
                }
            }

            // 检查是否检测到语音但未识别关键词
            if (voiceFramesDetected > 100 && keywordDetections == 0) {
                problemsFound = true
                println("[DIAGNOSTICS] 警告: 检测到语音活动但未识别关键词")
                println("[DIAGNOSTICS] 建议: 确认关键词列表是否正确，或调整灵敏度")
            }

            if (!problemsFound) {
                println("[DIAGNOSTICS] 未发现明显问题")
            }

            println("[DIAGNOSTICS] ======== 诊断完成 ========\n")
        } catch (e: Exception) {
            println("[DIAGNOSTICS] 诊断过程出错: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 启动一个命令处理器，允许通过控制台命令控制检测器
     */
    private fun startCommandProcessor() {
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000)

                // 尝试从标准输入读取命令
                val input = readlnOrNull()
                if (input != null && input.isNotEmpty()) {
                    processCommand(input.trim())
                }
            }
        }
    }

    /**
     * 处理命令输入
     */
    private fun processCommand(command: String) {
        when (command.lowercase()) {
            "help" -> {
                println("\n可用命令:")
                println("help          - 显示帮助信息")
                println("status        - 显示当前状态")
                println("verbose on    - 启用详细日志")
                println("verbose off   - 禁用详细日志")
                println("diag          - 运行诊断")
                println("keywords      - 显示当前关键词列表")
                println("stats         - 显示统计信息")
                println("reset         - 重置计数器")
                println()
            }

            "status" -> {
                println("[STATUS] 初始化状态: $isInitialized")
                println("[STATUS] 检测状态: ${_detectionState.value}")
                println("[STATUS] 当前模式: $detectionMode")
                println("[STATUS] 详细日志: ${if (verboseLogging) "已启用" else "已禁用"}")
            }

            "verbose on" -> {
                verboseLogging = true
                println("[CONFIG] 已启用详细日志")
            }

            "verbose off" -> {
                verboseLogging = false
                println("[CONFIG] 已禁用详细日志")
            }

            "diag" -> {
                println("[INFO] 运行诊断...")
                runDiagnostics()
            }

            "keywords" -> {
                println("[INFO] 当前关键词列表:")
                keywords.forEachIndexed { index, keyword ->
                    println("  ${index + 1}. $keyword")
                }
            }

            "stats" -> {
                println("[STATS] 总处理帧数: $totalFramesProcessed")
                println("[STATS] 检测到语音的帧数: $voiceFramesDetected")
                println("[STATS] 关键词检测次数: $keywordDetections")
            }

            "reset" -> {
                totalFramesProcessed = 0
                voiceFramesDetected = 0
                keywordDetections = 0
                println("[INFO] 已重置计数器")
            }

            else -> {
                if (command.isNotEmpty()) {
                    println("[ERROR] 未知命令: $command. 输入 'help' 查看可用命令")
                }
            }
        }
    }

    /**
     * 重新初始化Vosk
     * @return 是否成功
     */
    private fun reinitializeVosk(): Boolean {
        try {
            println("[DEBUG] 开始重新初始化Vosk关键词检测器")

            // 先尝试使用VoskSpeechService的内部修复功能
            // 尝试获取VoskSpeechService实例
            val voskService = voskRecognizer.getVoskService()
            if (voskService != null) {
                // 使用VoskSpeechService的重初始化功能
                val success = voskService.reinitializeVosk()
                if (success) {
                    println("[INFO] 使用VoskSpeechService重初始化成功")
                    return true
                } else {
                    println("[WARN] VoskSpeechService重初始化失败，尝试备用方案")
                }
            }
            // 备用方案：完全重新初始化
            // 释放资源
            release()

            // 重新初始化
            return initialize("", "", 0.5f)
        } catch (e: Exception) {
            println("[ERROR] 重新初始化Vosk关键词检测器异常: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
} 