package voice.audio.processing

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.*
import kotlinx.datetime.Clock.System
import voice.acquisition.portaudio.PortAudioAcquisition
import voice.acquisition.portaudio.PortAudioDevice
import voice.api.SpeechRecognizerApi
import voice.audio.AudioProcessingPipeline
import voice.audio.recognition.VoskSpeechRecognizer
import voice.audio.vad.VoiceActivityDetector
import voice.util.AudioUtils
import voice.util.DiagnosticsCollector
import voice.util.LogManager
import kotlin.time.ExperimentalTime
import voice.audio.AudioMetrics
import voice.audio.VADMetrics
import voice.audio.RecognitionMetrics

/**
 * 音频处理管理器
 * 负责协调音频采集、预处理、VAD和语音识别的流程
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
class AudioProcessingManager(private val modelPath: String) : AudioProcessingPipeline {
    private val logger = LogManager.getLogger("AudioProcessingManager")

    // 音频流水线组件
    private val acquisition = PortAudioAcquisition(
        PortAudioAcquisition.AudioConfig(sampleRate = 16000, channels = 2)
    )
    private val preprocessor = AudioPreprocessor()
    private val vad = VoiceActivityDetector()
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
        logger.info("AudioProcessingManager.initialize() 被调用, ModelPath: $modelPath")
        if (isInitialized) {
            logger.warn("音频处理管理器已初始化")
            return true
        }
        logger.info("初始化音频处理管理器")

        if (!acquisition.initialize("default", 16000)) {
            logger.error("音频采集初始化失败")
            return false
        }

        if (!recognizer.initialize(modelPath)) {
            logger.error("语音识别器初始化失败")
            acquisition.release()
            return false
        }

        preprocessor.initialize()
        vad.setSensitivity(0.4f)
        logger.info("已设置VAD灵敏度为中低灵敏度(0.4)，减少误触发")

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
     * 开始音频处理
     */
    override fun start() : Boolean{
        if (!isInitialized) {
            logger.error("音频处理管理器未初始化")
            return false
        }

        if (isRunning) {
            logger.warn("音频处理已经在运行中")
            return true
        }

        logger.info("启动音频处理流水线")
        isRunning = true
        processingStartTime = LogManager.getCurrentTimeMillis()

        // 创建新的协程作用域
        processingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        
        processingScope?.launch {
            try {
                acquisition.startCapture { audioData, length ->
                    if (!isRunning) return@startCapture
                    
                    try {
                        // 预处理音频数据
                        val processedData = preprocessor.process(audioData, length)
                        
                        // VAD检测
                        val vadResult = vad.detect(processedData.processedAudio, processedData.processedLength)
                        
                        // 更新VAD阈值
                        adaptiveVadThreshold = (adaptiveVadThreshold * 0.9f + vadResult.confidence * 0.1f)
                            .coerceIn(0.5f, 0.95f)
                        
                        // 更新统计信息
                        frameCount++
                        if (vadResult.hasSpeech) {
                            speechFrameCount++
                        }
                        lastFrameTime = LogManager.getCurrentTimeMillis()
                        
                        // 处理音频数据
                        if (vadResult.hasSpeech) {
                            val recognitionResult = recognizer.recognize(
                                processedData.processedAudio, processedData.processedLength,
                                lastFrameTime
                            )
                            
                            recognitionCallCount++
                            
                            // 检查是否检测到关键词
                            if (recognitionResult.success && recognitionResult.confidence >= minKeywordConfidence) {
                                val keywords = recognizer.getCurrentKeywords()
                                val matched = keywords.firstOrNull { kw ->
                                    recognitionResult.text.contains(kw, ignoreCase = true)
                                }

                                if (matched != null) {
                                    if (matched == lastDetectedCandidate) {
                                        repeatCount++
                                    } else {
                                        lastDetectedCandidate = matched
                                        repeatCount = 1
                                    }

                                    if (repeatCount >= requiredRepeat && (lastFrameTime - lastKeywordDetectedTime) >= keywordCooldownMs) {
                                        logger.info("✅ 关键词确认: $matched (conf=${recognitionResult.confidence})")
                                        lastKeywordDetectedTime = lastFrameTime
                                        repeatCount = 0
                                        lastDetectedCandidate = null
                                        keywordDetectedCallback?.invoke(matched)
                                    } else {
                                        logger.debug("候选关键词 $matched 第 $repeatCount 次，等待确认…")
                                    }
                                } else {
                                    lastDetectedCandidate = null
                                    repeatCount = 0
                                }
                            }
                        }
                    } catch (e: Exception) {
                        logger.error("音频处理异常: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                logger.error("启动音频捕获失败: ${e.message}")
                isRunning = false
            }
        }
        return true
    }

    /**
     * 停止音频处理
     */
    override fun stop() {
        if (!isRunning) {
            logger.warn("音频处理未在运行")
            return
        }

        logger.info("停止音频处理流水线")
        isRunning = false
        
        // 取消所有协程
        processingScope?.cancel()
        processingScope = null
        
        // 停止音频捕获
        acquisition.stopCapture()
        
        // 重置状态
        frameCount = 0
        speechFrameCount = 0
        recognitionCallCount = 0
        lastFrameTime = 0L
        processingStartTime = 0L
        lastRecognitionTime = 0L
        lastKeywordDetectedTime = 0L
        adaptiveVadThreshold = 0.8f
    }

    /**
     * 播放识别的音频（用于调试）
     */
    private fun playRecognizedAudio(audioData: ByteArray) {
        if (!playerReady || audioData.isEmpty()) return

        try {
            val shortArray = AudioUtils.byteArrayToShortArray(audioData)
            playbackDevice.playAudio(shortArray)
            logger.debug("回放识别的音频片段，长度: ${audioData.size}字节")
        } catch (e: Exception) {
            logger.warn("回放音频时出错: ${e.message}")
        }
    }

    /**
     * 生成诊断报告
     */
    override fun generateDiagnosticReport(): String {
        return diagnostics.generateReport()
    }

    /**
     * 处理来自KeywordDetector的音频数据
     * @param audioData Short数组音频数据
     * @return Boolean 是否检测到关键词
     */
    fun processAudio(audioData: ShortArray): Boolean {
        if (!isInitialized || !isRunning) {
            return false
        }

        try {
            // 转换为字节数组用于处理
            val byteData = AudioUtils.shortArrayToByteArray(audioData)

            // 使用标准处理流程
            val timestamp = LogManager.getCurrentTimeMillis()

            // 记录音频采集指标
            diagnostics.recordAcquisitionMetrics(
                deviceInfo = "KeywordDetector",
                bufferSize = byteData.size,
                timestamp = timestamp
            )

            // 1. 音频预处理
            val processResult = preprocessor.process(byteData, byteData.size)

            // 记录预处理指标
            diagnostics.recordPreprocessingMetrics(processResult.metrics, timestamp)

            // 如果预处理判断为不应继续，则返回false
            if (!processResult.shouldContinue || processResult.processedLength == 0) {
                return false
            }

            // 2. 语音活动检测
            val vadResult = vad.detect(processResult.processedAudio, processResult.processedLength)

            // 记录VAD指标
            diagnostics.recordVADMetrics(vadResult.metrics, timestamp)

            // 使用VAD结果决定是否继续处理
            if (!vadResult.hasSpeech || vadResult.confidence < 0.6f) {
                // 添加详细日志
                if (frameCount % 20 == 0) {
                    logger.info("无语音活动或置信度不足: hasSpeech=${vadResult.hasSpeech}, confidence=${vadResult.confidence}, energy=${vadResult.metrics.energy}")
                }
                return false
            }

            // 检测到语音活动，记录详细信息
            logger.info("检测到语音活动! energy=${vadResult.metrics.energy}, SNR=${vadResult.metrics.signalToNoiseRatio}, confidence=${vadResult.confidence}")

            // 3. 语音识别
            speechFrameCount++
            recognitionCallCount++

            val audioToRecognize =
                processResult.processedAudio.copyOfRange(0, processResult.processedLength)
            val recognitionResult = recognizer.recognize(audioToRecognize, audioToRecognize.size)

            // 记录识别指标
            diagnostics.recordRecognitionMetrics(recognitionResult.metrics, timestamp)

            // 4. 处理识别结果（添加置信度与冷却过滤）
            if (recognitionResult.success && recognitionResult.confidence >= minKeywordConfidence) {
                val keywords = recognizer.getCurrentKeywords()
                if (keywords.isNotEmpty()) {
                    val matched = keywords.firstOrNull { kw ->
                        recognitionResult.text.contains(kw, ignoreCase = true)
                    }

                    if (matched != null) {
                        if (matched == lastDetectedCandidate) {
                            repeatCount++
                        } else {
                            lastDetectedCandidate = matched
                            repeatCount = 1
                        }

                        if (repeatCount >= requiredRepeat && (timestamp - lastKeywordDetectedTime) >= keywordCooldownMs) {
                            logger.info("✅ 关键词确认: $matched (conf=${recognitionResult.confidence})")
                            lastKeywordDetectedTime = timestamp
                            repeatCount = 0
                            lastDetectedCandidate = null

                            playRecognizedAudio(audioToRecognize)
                            keywordDetectedCallback?.invoke(matched)
                            return true
                        } else {
                            logger.debug("候选关键词 $matched 第 $repeatCount 次 (conf=${recognitionResult.confidence})")
                        }
                    } else {
                        lastDetectedCandidate = null
                        repeatCount = 0
                    }
                }
            }

            return false
        } catch (e: Exception) {
            logger.error("处理Short音频数据时出错: ${e.message}")
            return false
        }
    }

    /**
     * 释放资源
     */
    override fun release() {
        logger.info("AudioProcessingManager.release() 被调用")
        if (isRunning) {
            stop()
        }

        if (isInitialized) {
            acquisition.release()
            recognizer.release()

            if (playerReady) {
                logger.info("释放专用播放设备...")
                playbackDevice.stopPlayback()
                playbackDevice.release()
                playerReady = false
            }

            isInitialized = false
            logger.info("音频处理管理器资源已释放")
        }
    }

    /**
     * 更新自适应VAD阈值
     */
    private fun updateAdaptiveThreshold(noiseLevel: Double) {
        // 根据环境噪声水平调整VAD阈值
        val baseThreshold = 0.8f
        val adjustment = (noiseLevel / 1000.0).coerceIn(0.0, 0.15)
        adaptiveVadThreshold = (baseThreshold - adjustment.toFloat()).coerceIn(0.65f, 0.95f)

        logger.debug("更新VAD自适应阈值: $adaptiveVadThreshold (噪声水平: $noiseLevel)")
    }

    // 确保 process 方法的签名与接口完全一致并添加 override
    override fun process(rawAudio: ByteArray, length: Int): AudioProcessingPipeline.ProcessResult {
        logger.info("AudioProcessingManager.process() 被直接调用。通常数据来自内部采集。 Length: $length")
        val timestamp = System.now().toEpochMilliseconds()
        diagnostics.recordAcquisitionMetrics("ExternalDirect", length, timestamp)
        
        // 调用 preprocessor 的 process 方法，它也实现了 AudioProcessingPipeline
        // preprocessor 的 process 方法会返回 AudioProcessingPipeline.ProcessResult
        val preprocessResult = preprocessor.process(rawAudio, length)
        diagnostics.recordPreprocessingMetrics(preprocessResult.metrics, timestamp)
        
        // 直接返回预处理器的结果，因为这是process方法在此上下文中的主要职责
        return preprocessResult 
    }

    /**
     * 在文本中查找关键词
     */
    private fun findKeywordsInText(text: String, keywords: List<String>): List<String> {
        val lowerText = text.lowercase()
        return keywords.filter { kw -> lowerText.contains(kw.lowercase()) }
    }
}