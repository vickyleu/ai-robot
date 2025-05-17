package voice.audio.processing

import kotlinx.cinterop.ExperimentalForeignApi
import voice.api.AudioProcessingApi
import voice.api.vad.IVoiceActivityDetector
import voice.audio.AudioPipeline
import voice.audio.acquisition.PortAudioAcquisition
import voice.audio.recognition.VoskSpeechRecognizer
import voice.audio.vad.VoiceActivityDetector
import voice.util.DiagnosticsCollector
import voice.util.LogManager
import kotlin.time.ExperimentalTime

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
        // 降低采样率以提高性能
        AudioPipeline.Acquisition.Config(sampleRate = 16000, channels = 1)
    )
    private val preprocessor = AudioPreprocessor()
    private val vad = VoiceActivityDetector()
    private val recognizer = VoskSpeechRecognizer()
    private val diagnostics = DiagnosticsCollector()
    
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
        
        // 构建关键词字符串，以逗号分隔
        val keywordsString = keywords.joinToString(",")
        
        // 更新Vosk识别器的关键词
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
        
        logger.info("启动音频处理流水线")
        
        // 启动音频采集
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
        
        logger.info("【调试】音频帧#$frameCount: 长度=${length}字节, 时间戳=$timestamp")
        logger.info("【调试】原始音频能量: ${calculateRms(audioData, length)}")
        
        // 输出状态信息
        println("【系统状态】音频帧#$frameCount: 能量=${calculateRms(audioData, length)}")
        
        // 1. 音频预处理
        val processResult = preprocessor.process(audioData, length)
        logger.info("【调试】音频预处理结果: shouldContinue=${processResult.shouldContinue}, RMS=${processResult.metrics.rms}, 处理后长度=${processResult.processedLength}")
        
        // 如果预处理判断为不应继续，则跳过后续处理
        if (!processResult.shouldContinue || processResult.processedLength == 0) {
            return
        }
        
        // 2. 语音活动检测
        val vadResult = vad.detect(processResult.processedAudio, processResult.processedLength)
        logger.info("【调试】VAD结果: 语音=${vadResult.hasSpeech}, 概率=${vadResult.confidence}, 能量=${vadResult.metrics.energyLevel}")
        
        // 只在VAD确认检测到语音，且有语音边界事件时才处理语音识别
        // 这样可以减少频繁的处理和错误识别
        if (vadResult.hasSpeech) {
            // 3. 语音识别
            val recognitionResult = recognizer.recognize(
                processResult.processedAudio,
                processResult.processedLength
            )
            recognitionCallCount++
            
            logger.info("【调试】语音识别结果: 成功=${recognitionResult.success}, 文本=\"${recognitionResult.text}\", 部分=${recognitionResult.isPartial}")
            
            // 检测到关键词
            if (recognitionResult.success && !recognitionResult.isPartial && 
                recognitionResult.text.isNotBlank() && frameCount % 5 == 0) {
                // 调用关键词回调
                keywordDetectedCallback?.invoke(recognitionResult.text)
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
     * @return 诊断信息文本
     */
    override fun generateDiagnosticReport(): String {
        val report = StringBuilder()
        report.appendLine("音频处理管理器诊断报告")
        report.appendLine("==========================")
        report.appendLine("初始化状态: ${if (isInitialized) "已初始化" else "未初始化"}")
        report.appendLine("运行状态: ${if (isRunning) "运行中" else "未运行"}")
        report.appendLine("处理总帧数: $frameCount")
        report.appendLine("识别到语音的帧数: $speechFrameCount")
        report.appendLine("语音比例: ${if (frameCount > 0) "${speechFrameCount * 100 / frameCount}%" else "N/A"}")
        report.appendLine("最后一帧处理时间: $lastFrameTime")
        report.appendLine("识别调用次数: $recognitionCallCount")
        
        // 添加采集器诊断信息
        report.appendLine("\n音频采集器状态:")
        report.appendLine("------------------")
        report.appendLine("采样率: 16000Hz")
        
        // 添加VAD诊断信息
        report.appendLine("\nVAD状态:")
        report.appendLine("------------------")
        report.appendLine("VAD已初始化: ${vad != null}")
        
        // 添加识别器诊断信息
        report.appendLine("\n识别器状态:")
        report.appendLine("------------------")
        report.appendLine("识别器已初始化: ${recognizer != null}")
        
        return report.toString()
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
            isInitialized = false
        }
    }
    
    /**
     * 计算音频数据的RMS能量
     * @param audioData 音频数据
     * @param length 数据长度
     * @return RMS能量值
     */
    private fun calculateRms(audioData: ByteArray, length: Int): Double {
        var sumSquares = 0.0
        val sampleCount = length / 2 // 16位音频每样本2字节
        
        for (i in 0 until length step 2) {
            if (i + 1 < length) {
                val sample = (audioData[i].toInt() and 0xFF) or ((audioData[i + 1].toInt() and 0xFF) shl 8)
                val signedSample = if (sample and 0x8000 != 0) {
                    sample - 0x10000
                } else {
                    sample
                }
                sumSquares += signedSample * signedSample
            }
        }
        
        return kotlin.math.sqrt(sumSquares / sampleCount)
    }
} 