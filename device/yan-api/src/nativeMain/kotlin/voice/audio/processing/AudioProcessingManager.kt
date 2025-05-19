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
    private val recognitionCooldownMs = 300L

    // VAD阈值调整
    private var adaptiveVadThreshold = 0.8f

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
    override fun start() {
        logger.info("⭐⭐⭐ AudioProcessingManager.start() 被调用")
        if (isRunning) {
            logger.warn("音频处理流水线已经在运行中")
            return
        }
        if (!isInitialized) {
            logger.error("音频处理管理器未初始化，无法启动！")
            return
        }

        logger.info("⭐⭐⭐ 启动音频处理流水线")
        processingStartTime = System.now().toEpochMilliseconds()
        frameCount = 0
        speechFrameCount = 0
        recognitionCallCount = 0
        lastFrameTime = 0L
        lastRecognitionTime = 0L
        (diagnostics as? DiagnosticsCollector)?.clear()

        logger.info("⭐⭐⭐ 创建处理协程...")
        processingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        processingScope?.launch {
            logger.info("⭐⭐⭐ 处理协程已启动")
            isRunning = true
            logger.info("⭐⭐⭐ 准备启动音频采集循环...")
            
            try {
                logger.info("⭐⭐⭐ 调用 acquisition.startCapture 设置回调...")
                acquisition.startCapture { audioData, length ->
                    if (frameCount == 0) {
                        logger.info("⭐⭐⭐ 首次收到音频数据回调！长度=$length")
                    }
                    
                    if (isRunning) {
                        processAudioFrame(audioData, length, System.now().toEpochMilliseconds())
                    } else {
                        logger.warn("收到音频数据但处理管理器已停止")
                    }
                }
                logger.info("⭐⭐⭐ acquisition.startCapture 返回成功，等待音频数据...")
            } catch (e: Exception) {
                logger.error("⭐⭐⭐ 启动音频采集时发生异常: ${e.message}")
                e.printStackTrace()
                isRunning = false
            }
        }?.invokeOnCompletion { throwable ->
            isRunning = false
            if (throwable is CancellationException) {
                logger.info("音频处理主协程被取消")
            } else if (throwable != null) {
                logger.error("音频处理主协程异常结束: ${throwable.message}")
                throwable.printStackTrace()
            }
            logger.info("音频处理流水线已停止.")
        }
        
        logger.info("⭐⭐⭐ AudioProcessingManager.start() 完成，等待音频数据处理...")
    }

    /**
     * 处理音频帧
     */
    private fun processAudioFrame(audioData: ByteArray, length: Int, timestamp: Long) {
        if (!isRunning) return
        if (length <= 0) {
            logger.debug("收到无效音频帧，长度=$length")
            return
        }

        try {
            frameCount++
            if (frameCount == 1) {
                logger.info("⭐⭐⭐ 收到第一帧音频数据，长度=$length, 时间戳=$timestamp")
            }
            
            lastFrameTime = timestamp
            diagnostics.recordAcquisitionMetrics("PortAudio", length, timestamp)

            val preprocessResult = preprocessor.process(audioData, length)
            diagnostics.recordPreprocessingMetrics(preprocessResult.metrics, timestamp)

            if (!preprocessResult.shouldContinue || preprocessResult.processedLength == 0) {
                return
            }

            val vadResult =
                vad.detect(preprocessResult.processedAudio, preprocessResult.processedLength)
            diagnostics.recordVADMetrics(vadResult.metrics, timestamp)

            if (!vadResult.hasSpeech || vadResult.confidence < 0.6f) {
                if (frameCount % 20 == 0) {
                    logger.debug("无语音活动或置信度不足: hasSpeech=${vadResult.hasSpeech}, confidence=${vadResult.confidence}, energy=${vadResult.metrics.energy}")
                }
                return
            }
            logger.debug("检测到语音活动! energy=${vadResult.metrics.energy}, SNR=${vadResult.metrics.signalToNoiseRatio}, confidence=${vadResult.confidence}")

            val currentTimeMs = System.now().toEpochMilliseconds()
            if (currentTimeMs - lastRecognitionTime > recognitionCooldownMs) {
                lastRecognitionTime = currentTimeMs
                recognitionCallCount++
                speechFrameCount++

                val audioToRecognize =
                    preprocessResult.processedAudio.copyOfRange(0, preprocessResult.processedLength)
                val recognitionResult =
                    recognizer.recognize(audioToRecognize, audioToRecognize.size, timestamp)
                diagnostics.recordRecognitionMetrics(recognitionResult.metrics, timestamp)
                handleRecognitionResult(recognitionResult)
            }
        } catch (e: Exception) {
            logger.error("处理音频帧时发生异常: ${e.message}")
        }
    }

    /**
     * 处理识别结果
     */
    private fun handleRecognitionResult(result: SpeechRecognizerApi.RecognitionResult) {
        if (!result.success || result.text.isBlank()) {
            return
        }
        logger.info("识别结果: \"${result.text}\", 置信度: ${result.confidence}")

        val currentKeywords =
            (recognizer as? VoskSpeechRecognizer)?.getCurrentKeywords() ?: emptyList()
        if (currentKeywords.isEmpty()) return

        val detectedKeywords = findKeywordsInText(result.text, currentKeywords)
        if (detectedKeywords.isNotEmpty()) {
            logger.info("检测到关键词: ${detectedKeywords.joinToString(", ")}")
            keywordDetectedCallback?.invoke(detectedKeywords.first())
        }
    }

    /**
     * 在文本中查找关键词
     */
    private fun findKeywordsInText(text: String, keywords: List<String>): List<String> {
        val lowerText = text.lowercase()
        return keywords.filter { kw -> lowerText.contains(kw.lowercase()) }
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
     * 停止音频处理
     */
    override fun stop() {
        logger.info("AudioProcessingManager.stop() 被调用")
        if (!isRunning) {
            logger.warn("音频处理管理器未在运行")
            return
        }
        isRunning = false
        processingScope?.cancel("停止请求")
        processingScope = null
        acquisition.stopCapture()
        logger.info("音频处理流水线已请求停止")
        val report = diagnostics.generateReport()
        logger.info("停止时生成的诊断报告:\n$report")
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

            // 4. 处理识别结果
            if (recognitionResult.success && !recognitionResult.text.isBlank()) {
                // 检查是否包含关键词
                val keywords = recognizer.getCurrentKeywords()
                if (keywords.isEmpty()) {
                    return false
                }

                val detectedKeywords = findKeywordsInText(recognitionResult.text, keywords)
                if (detectedKeywords.isNotEmpty()) {
                    // 找到关键词，触发回调
                    logger.info("检测到关键词: ${detectedKeywords.joinToString(", ")}")

                    // 回放识别的音频片段
                    playRecognizedAudio(audioToRecognize)

                    // 调用回调
                    keywordDetectedCallback?.invoke(detectedKeywords.first())
                    return true
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
}