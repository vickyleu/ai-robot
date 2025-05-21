package voice.audio.processing

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.nativeHeap
import kotlinx.coroutines.*
import kotlinx.datetime.Clock.System
import voice.acquisition.portaudio.PortAudioAcquisition
import voice.acquisition.portaudio.PortAudioDevice
import voice.api.SpeechRecognizerApi
import voice.audio.AudioMetrics
import voice.audio.AudioProcessingPipeline
import voice.audio.VADMetrics
import voice.audio.recognition.VoskSpeechRecognizer
import voice.audio.vad.VoiceActivityDetection
import voice.util.AudioUtils
import voice.util.DiagnosticsCollector
import voice.util.LogManager
import kotlin.time.ExperimentalTime

/**
 * 音频处理管理器
 * 负责协调音频采集、预处理、VAD和语音识别的流程
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
class AudioProcessingManager(
    private val vadProcessor: VoiceActivityDetection? = null
) : AudioProcessingPipeline {
    private val logger = LogManager.getLogger("AudioProcessingManager")

    // 音频流水线组件
    private val acquisition = PortAudioAcquisition(
        PortAudioAcquisition.AudioConfig(sampleRate = 48000, channels = 2)
    )
    private val preprocessor = AudioPreprocessor()
    private val recognizer: SpeechRecognizerApi = VoskSpeechRecognizer()

    // 诊断收集器 - 充分利用现有架构
    private val diagnostics: AudioProcessingPipeline.Diagnostics = DiagnosticsCollector()

    // 专用的播放设备用于识别回放，与录音设备分开可避免冲突
    private val playbackDevice: PortAudioDevice = PortAudioDevice.getInstance()
    private var playerReady = false

    // 回调处理
    private var keywordDetectedCallback: ((String) -> Unit)? = null

    // 状态标志
    private var isInitialized = false
    private var isRunning = false

    // 处理统计
    private var frameCount = 0
    private var speechFrameCount = 0
    private var lastFrameTime = 0L
    private var recognitionCallCount = 0

    // 记录处理开始的时间，用于计算识别延迟
    private var processingStartTime = 0L

    // 识别控制
    private var lastRecognitionTime = 0L
    private var lastKeywordDetectedTime = 0L
    private val recognitionCooldownMs = 300L
    private val keywordCooldownMs = 1500L

    // VAD阈值调整
    private var adaptiveVadThreshold = 0.8f

    // 关键词识别最低置信度阈值（0-1）
    private val minKeywordConfidence = 0.92f

    // 连续重复检测要求
    private var lastDetectedCandidate: String? = null
    private var repeatCount = 0
    private val requiredRepeat = 2

    // 处理状态
    private var isProcessing = false
    private var processingScope: CoroutineScope? = null

    // 输入和输出音频队列
    private val inputQueue = ArrayDeque<Pair<ByteArray, Int>>()
    private val outputQueue = ArrayDeque<Triple<ByteArray, Int, VADMetrics>>()

    // WebRTC APM 处理器
    private var webRtcApm = WebRtcApm()

    // VAD 处理回调
    private var onVadResult: ((VoiceActivityDetection.DetectionResult) -> Unit)? = null

    // 协程作用域
    private val processingJob: Job? = null

    /**
     * 获取处理统计信息
     */
    override fun getStats(): AudioProcessingPipeline.ProcessingStats {
        return AudioProcessingPipeline.ProcessingStats(
            frameCount = frameCount,
            speechFrameCount = speechFrameCount,
            recognitionCallCount = recognitionCallCount,
            lastFrameTime = lastFrameTime
        )
    }

    /**
     * 初始化音频处理管理器
     * @return 初始化是否成功
     */
    override fun initialize(): Boolean {
        logger.info("AudioProcessingManager.initialize() 被调用")
        if (isInitialized) {
            logger.warn("音频处理管理器已初始化")
            return true
        }
        logger.info("初始化音频处理管理器")

        if (!acquisition.initialize("default", 16000)) {
            logger.error("音频采集初始化失败")
            return false
        }

        // 假设recognizer的initialize需要一个模型路径参数
        // 这里从其他地方获取模型路径或使用默认值
        val defaultModelPath = "/usr/local/share/yanshee-model/vosk/vosk-model-small-cn-0.22"
        if (!recognizer.initialize(defaultModelPath)) {
            logger.error("语音识别器初始化失败")
            acquisition.release()
            return false
        }

        // 初始化 WebRTC APM
        val apmInitialized = webRtcApm.initialize(16000, 1)
        if (!apmInitialized) {
            logger.error("WebRTC APM 初始化失败")
            // 继续执行，但性能会受到影响
        }

        preprocessor.initialize()

        // 初始化专用的播放设备 (与录音设备分离)
        logger.info("⚠️  跳过专用播放设备的即时启动，将在首次需要播放时再尝试初始化 (lazy start)")
        playerReady = false

        isInitialized = true
        logger.info("音频处理管理器初始化成功")
        return true
    }

    /**
     * 设置关键词检测回调
     */
    override fun setKeywordDetectedCallback(callback: (String) -> Unit) {
        keywordDetectedCallback = callback
    }

    /**
     * 更新关键词列表
     * @param keywords 关键词列表
     */
    override fun updateKeywords(keywords: List<String>) {
        logger.info("更新关键词列表: ${keywords.joinToString(", ")}")

        if (!isInitialized) {
            logger.warn("音频处理管理器未初始化，无法更新关键词")
            return
        }

        recognizer.updateKeywords(keywords)
    }

    /**
     * 启动音频处理链
     * @return 启动是否成功
     */
    override suspend fun start(): Boolean {
        if (!isInitialized) {
            logger.error("音频处理管理器未初始化")
            return false
        }

        if (isRunning) {
            logger.warn("音频处理流水线已在运行")
            return true
        }

        logger.info("启动音频处理流水线")

        isRunning = true
        processingScope = CoroutineScope(Dispatchers.Default)

        // 启动采集
        if (!acquisition.start()) {
            logger.error("启动音频采集失败")
            isRunning = false
            return false
        }

        // 启动处理协程
        processingScope?.launch {
            try {
                logger.info("音频处理协程已启动")
                while (isRunning) {
                    try {
                        // 读取音频数据
                        val buffer = nativeHeap.allocArray<ShortVar>(4096)
                        val framesRead = acquisition.readAudio(buffer, 4096)
                        if (framesRead <= 0) {
                            delay(10)
                            continue
                        }

                        // 使用AudioUtils现有方法从C指针创建ByteArray
                        val audioDataBytes = AudioUtils.shortArrayToByteArray(buffer, framesRead)
                        val length = audioDataBytes.size

                        // 记录帧计数和时间
                        frameCount++
                        lastFrameTime = System.now().toEpochMilliseconds()

                        // 预处理音频数据
                        val processed = preprocessor.process(audioDataBytes, length)

                        // 获取处理后的数据和长度
                        val processedData = processed.processedAudio
                        val processLength = processed.processedLength

                        // 将ByteArray转换为ShortArray
                        val shorts = AudioUtils.byteArrayToShortArray(processedData, processLength)

                        // 使用WebRTC APM处理音频并进行VAD检测
                        val processedShorts = WebRtcApmSingleton.processFrame(shorts)
                        val hasVoice = WebRtcApmSingleton.isVoiceDetected()

                        // 创建VADMetrics
                        val vadMetrics = VADMetrics(
                            energy = 0f,
                            noiseFloor = 0f,
                            signalToNoiseRatio = 0f,
                            speechProbability = if (hasVoice) 0.95f else 0.05f,
                            hasVoice = hasVoice
                        )

                        if (hasVoice) {
                            // 检测到语音
                            speechFrameCount++

                            // 传递给语音识别器
                            val result = recognizer.recognize(processedData, processLength, System.now().toEpochMilliseconds())
                            if (result.text.isNotEmpty()) {
                                // 尝试提取关键词
                                tryExtractKeyword(result.text)
                            }
                        }

                        // 将处理后的数据转换回ByteArray
                        // 修复：使用简化版的shortArrayToByteArray方法
                        val outputBytes = AudioUtils.shortArrayToByteArray(processedShorts)

                        // 添加到输出队列 - Kotlin Native支持synchronized, 需要传入一个val lock = SynchronizedObject()
                        outputQueue.add(Triple(outputBytes, outputBytes.size, vadMetrics))
                        // 限制队列大小避免内存泄漏
                        while (outputQueue.size > 10) {
                            outputQueue.removeFirst()
                        }
                    } catch (ce: CancellationException) {
                        // 协程被取消，正常退出
                        logger.info("音频处理协程被取消")
                        break
                    } catch (e: Exception) {
                        logger.error("音频处理过程中出错: ${e.message}")
                        e.printStackTrace()
                        // 短暂延迟后继续
                        delay(100)
                    }
                }
            } catch (e: Exception) {
                logger.error("音频处理协程异常: ${e.message}")
                e.printStackTrace()
            } finally {
                logger.info("音频处理协程结束")
            }
        }

        logger.info("音频处理流水线启动完成")
        return true
    }

    /**
     * 停止音频处理
     */
    override fun stop() {
        if (!isRunning) {
            return
        }

        logger.info("停止音频处理流水线")
        isRunning = false
        processingScope?.cancel()
        processingScope = null

        try {
            acquisition.stop()
            webRtcApm.release()

            // 清空队列 - 不使用synchronized
            inputQueue.clear()
            outputQueue.clear()
        } catch (e: Exception) {
            logger.error("停止音频处理时发生错误: ${e.message}")
        }

        logger.info("音频处理流水线已停止")
    }

    /**
     * 释放资源
     */
    override fun release() {
        stop()
        acquisition.release()
        preprocessor.release()
        recognizer.release()
        logger.info("音频处理资源已释放")
        isInitialized = false
    }

    /**
     * 处理音频数据
     * 直接的单帧处理，通常用于外部提供的音频（如测试）
     * @param rawAudio 原始音频数据
     * @param length 数据长度
     * @return 处理结果
     */
    override fun process(rawAudio: ByteArray, length: Int): AudioProcessingPipeline.ProcessResult {
        if (!isInitialized) {
            logger.warn("音频处理器未初始化")
            return AudioProcessingPipeline.ProcessResult(
                processedAudio = ByteArray(0),
                processedLength = 0,
                metrics = AudioMetrics(0.0, 0, 0, 0.0, 0.0),
                shouldContinue = false
            )
        }

        // 预处理
        val preprocessResult = preprocessor.process(rawAudio, length)
        if (!preprocessResult.shouldContinue) {
            return preprocessResult
        }

        val processedData = preprocessResult.processedAudio
        val processLength = preprocessResult.processedLength

        // 将ByteArray转换为ShortArray
        val shorts = AudioUtils.byteArrayToShortArray(processedData, processLength)

        // VAD检测
        val processedShorts = WebRtcApmSingleton.processFrame(shorts)
        val hasVoice = WebRtcApmSingleton.isVoiceDetected()

        // 转回ByteArray
        // 修复：使用简化版的shortArrayToByteArray方法
        val outputBytes = AudioUtils.shortArrayToByteArray(processedShorts)

        // 创建处理结果
        return AudioProcessingPipeline.ProcessResult(
            processedAudio = outputBytes,
            processedLength = outputBytes.size,
            metrics = preprocessResult.metrics,
            shouldContinue = true
        )
    }

    /**
     * 尝试从识别结果中提取关键词，并触发回调
     * @param text 识别到的文本
     */
    private fun tryExtractKeyword(text: String) {
        if (text.isEmpty()) return

        // 记录识别调用计数
        recognitionCallCount++

        // 检查是否在冷却期内
        val now = System.now().toEpochMilliseconds()
        if (now - lastKeywordDetectedTime < keywordCooldownMs) {
            return
        }

        // 这里可以实现更复杂的关键词匹配逻辑
        // 为简单起见，先仅做基本字符串匹配
        keywordDetectedCallback?.invoke(text)
        lastKeywordDetectedTime = now
    }

    /**
     * 播放音频数据
     * @param audioData 音频数据
     * @return 播放是否成功
     */
    fun playAudio(audioData: ShortArray): Boolean {
        if (!playerReady) {
            // 延迟初始化播放设备
            playerReady = playbackDevice.initialize("default", 16000)
            if (!playerReady) {
                logger.error("无法初始化播放设备")
                return false
            }
        }

        return playbackDevice.playAudio(audioData)
    }

    /**
     * 生成诊断报告
     * @return 诊断报告字符串
     */
    override fun generateDiagnosticReport(): String {
        val sb = StringBuilder()
        sb.appendLine("====== 音频处理诊断报告 ======")
        sb.appendLine("初始化状态: $isInitialized")
        sb.appendLine("运行状态: $isRunning")
        sb.appendLine("总处理帧数: $frameCount")
        sb.appendLine("检测到语音帧数: $speechFrameCount")
        sb.appendLine("语音识别调用次数: $recognitionCallCount")
        sb.appendLine("最后处理时间: $lastFrameTime")

        return sb.toString()
    }
}