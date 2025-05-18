package voice.audio.processing

import com.airobot.core.utils.format
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.datetime.Clock.System
import voice.api.AudioProcessingApi
import voice.api.vad.IVoiceActivityDetector
import voice.audio.AudioPipeline
import voice.acquisition.portaudio.PortAudioAcquisition
import voice.audio.recognition.VoskSpeechRecognizer
import voice.audio.vad.VoiceActivityDetector
import voice.util.AudioUtils
import voice.util.DiagnosticsCollector
import voice.util.LogManager
import kotlin.time.ExperimentalTime
import voice.acquisition.portaudio.PortAudioDevice

/**
 * 音频处理管理器
 * 负责协调音频处理流水线中的各个组件
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
class AudioProcessingManager(
    private val modelPath: String
) : AudioProcessingApi {
    private val logger = LogManager.getLogger("AudioProcessingManager")
    
    // 音频流水线组件
    private val acquisition = PortAudioAcquisition(
        AudioPipeline.Acquisition.Config(sampleRate = 16000, channels = 2)
    )
    private val preprocessor = AudioPreprocessor()
    private val vad = VoiceActivityDetector()
    private val recognizer = VoskSpeechRecognizer()
    private val diagnostics = DiagnosticsCollector()

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
    
    // 是否处于调试模式，只有调试模式才会输出部分日志
    private val debugMode = false

    /**
     * 获取处理统计信息
     */
    override fun getStats(): AudioProcessingApi.ProcessingStats {
        return AudioProcessingApi.ProcessingStats(
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
        if (isInitialized) {
            logger.warn("音频处理管理器已初始化")
            return true
        }
        
        logger.info("初始化音频处理管理器")
        
        // 初始化各组件
        if (!acquisition.initialize()) {
            logger.error("音频采集初始化失败")
            return false
        }
        
        if (!recognizer.initialize(modelPath)) {
            logger.error("语音识别器初始化失败")
            acquisition.release()
            return false
        }
        
        // 配置VAD灵敏度 - 设置为中低灵敏度(0.4)以减少误触发
        vad.setSensitivity(0.4f)
        logger.info("已设置VAD灵敏度为中低灵敏度(0.4)，减少误触发")

        // 初始化专用的播放设备 (与录音设备分离)
        if (playbackDevice.initialize("回放设备", 16000)) {
            if (playbackDevice.start()) {
                playerReady = true
                logger.info("专用播放设备初始化成功")
            } else {
                logger.warn("启动专用播放设备失败，回放功能将不可用")
                playerReady = false
            }
        } else {
            logger.warn("初始化专用播放设备失败，回放功能将不可用")
            playerReady = false
        }
        
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
        
        val keywordsString = keywords.joinToString(",")
        recognizer.updateKeywords(keywordsString)
    }
    
    /**
     * 开始音频处理
     */
    override fun start() {
        if (isRunning) {
            logger.warn("音频处理流水线已经在运行中")
            return
        }
        if (!isInitialized) {
            logger.error("音频处理管理器未初始化，无法启动！")
            return
        }
        
        logger.info("启动音频处理流水线")
        processingStartTime = System.now().toEpochMilliseconds()
        
        acquisition.startCapture { audioData, length ->
            val timestamp = LogManager.getCurrentTimeMillis()
            processAudioFrame(audioData, length, timestamp)
        }
        
        isRunning = true
    }
    
    /**
     * 处理音频帧
     * @param audioData 原始音频数据
     * @param length 数据长度
     * @param timestamp 时间戳
     */
    private fun processAudioFrame(audioData: ByteArray, length: Int, timestamp: Long) {
        frameCount++
        lastFrameTime = timestamp
        
        // 仅在调试模式下每100帧输出系统状态
        if (debugMode && frameCount % 100 == 0) {
            val energy = calculateRms(audioData, length)
            println("【系统状态】音频帧#$frameCount: 能量=$energy")
        }
        
        // 1. 音频预处理
        val processResult = preprocessor.process(audioData, length)
        
        // 如果预处理判断为不应继续，则跳过后续处理
        if (!processResult.shouldContinue || processResult.processedLength == 0) {
            return
        }
        
        // 2. 语音活动检测
        val vadResult = vad.detect(processResult.processedAudio, processResult.processedLength)

        // 仅当检测到语音且置信度高时输出VAD结果
        if (vadResult.hasSpeech && vadResult.confidence > 0.80) {
            logger.info("VAD: 检测到语音！置信度=${vadResult.confidence}, 能量=${vadResult.metrics.energyLevel}")
        }
        
        // 使用更严格的条件进行语音识别，避免误触发
        if (vadResult.hasSpeech && vadResult.confidence > 0.80) {
            if (processResult.processedLength == 0) {
                return
            }

            // 复制一份用于识别和播放的音频数据
            val audioToRecognizeAndPlay = processResult.processedAudio.copyOfRange(0, processResult.processedLength)
            
            // 只在识别开始时记录日志，减少输出
            logger.info("识别开始: 置信度=${"%.2f".format(vadResult.confidence.toDouble())}, 音频长度=${audioToRecognizeAndPlay.size}字节")
            
            // 3. 语音识别
            val recognitionResult = recognizer.recognize(
                audioToRecognizeAndPlay,
                audioToRecognizeAndPlay.size
            )
            recognitionCallCount++
            
            // 只记录有结果的识别或明确失败的识别
            if (recognitionResult.success && recognitionResult.text.isNotBlank()) {
                logger.info("识别结果: \"${recognitionResult.text}\"${if(recognitionResult.isPartial) " (部分结果)" else ""}")
                
                if (!recognitionResult.isPartial) {
                    speechFrameCount++ 
                    
                    // 播放被识别的音频片段
                    if (playerReady) {
                        val shortArrayToPlay = AudioUtils.byteArrayToShortArray(audioToRecognizeAndPlay)
                        if (shortArrayToPlay.isNotEmpty()) {
                            playbackDevice.playAudio(shortArrayToPlay)
                            logger.info("回放识别的音频片段 (${shortArrayToPlay.size}采样点)")
                        }
                    }
                    
                    // 触发关键词回调 (每个非部分结果都会触发，不再限制为每3次)
                    keywordDetectedCallback?.invoke(recognitionResult.text)
                }
            } else if (!recognitionResult.success) {
                logger.warn("识别失败")
            }
        }
    }
    
    /**
     * 停止音频处理
     */
    override fun stop() {
        if (!isRunning) {
            logger.warn("音频处理管理器未在运行")
            return
        }
        
        logger.info("停止音频处理流水线")
        acquisition.stopCapture()
        isRunning = false
    }
    
    /**
     * 生成诊断报告
     */
    override fun generateDiagnosticReport(): String {
        val report = StringBuilder()
        report.appendLine("音频处理管理器诊断报告")
        report.appendLine("==========================")
        report.appendLine("初始化状态: ${if (isInitialized) "已初始化" else "未初始化"}")
        report.appendLine("运行状态: ${if (isRunning) "运行中" else "未运行"}")
        report.appendLine("处理总帧数: $frameCount")
        report.appendLine("识别到语音的帧数: $speechFrameCount")
        report.appendLine("语音比例: ${if (frameCount > 0 && speechFrameCount > 0) "${"%.2f".format(speechFrameCount.toDouble() * 100.0 / frameCount)}%" else "N/A"}")
        report.appendLine("最后一帧处理时间: $lastFrameTime")
        report.appendLine("识别调用次数: $recognitionCallCount")
        report.appendLine("运行时间: ${(System.now().toEpochMilliseconds() - processingStartTime) / 1000}秒")
        
        report.appendLine("音频采集器状态:")
        report.appendLine("------------------")
        report.appendLine("采样率: ${acquisition.config.sampleRate}Hz, 通道数: ${acquisition.config.channels}")
        
        report.appendLine("VAD状态:")
        report.appendLine("------------------")
        report.appendLine("VAD已配置")
        
        report.appendLine("识别器状态:")
        report.appendLine("------------------")
        report.appendLine("识别器已初始化: ${recognizer.isInitialized}")
        
        report.appendLine("播放器状态:")
        report.appendLine("------------------")
        report.appendLine("专用播放器已就绪: $playerReady")

        return report.toString()
    }
    
    /**
     * 处理来自KeywordDetector的音频数据
     * 更高效的音频处理接口，直接处理Short数组
     * @param audioData Short数组音频数据
     * @return Boolean 是否检测到语音（有效语音帧）
     */
    fun processAudio(audioData: ShortArray): Boolean {
        if (!isInitialized || !isRunning) {
            return false
        }
        
        // 转换为字节数组，使用AudioUtils工具类
        val byteData = AudioUtils.shortArrayToByteArray(audioData)
        
        // 直接处理音频数据
        val timestamp = LogManager.getCurrentTimeMillis()
        processAudioFrame(byteData, byteData.size, timestamp)
        
        // 返回检测结果
        return true  // 返回true表示已处理，回调会处理实际的检测结果
    }
    
    /**
     * 释放资源
     */
    override fun release() {
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
     * 计算音频数据的RMS能量
     */
    private fun calculateRms(audioData: ByteArray, length: Int): Double {
        if (length == 0 || length % 2 != 0) return 0.0
        var sumSquares = 0.0
        val sampleCount = length / 2 
        
        for (i in 0 until length step 2) {
            val byte1 = audioData[i].toInt() and 0xFF
            val byte2 = audioData[i + 1].toInt() and 0xFF
            var sample = (byte2 shl 8) or byte1
            // 将无符号16位PCM转换为有符号
            if (sample > 32767) {
                sample -= 65536
            }
            sumSquares += sample * sample.toDouble()
        }
        
        return if (sampleCount > 0) kotlin.math.sqrt(sumSquares / sampleCount) else 0.0
    }
}