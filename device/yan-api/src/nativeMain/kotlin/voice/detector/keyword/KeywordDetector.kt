@file:OptIn(ExperimentalForeignApi::class)

package voice.detector.keyword

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.posix.log
import voice.api.KeywordDetectorApi
import voice.audio.processing.WebRtcApmSingleton
import voice.util.LogManager
import voice.util.AudioDefaults
import voice.util.AudioUtils
import voice.audio.recognition.VoskSpeechRecognizer
import voice.acquisition.portaudio.PortAudioAcquisition
import voice.acquisition.portaudio.PortAudioDevice
import voice.audio.processing.CallbackAudioProcessor

/**
 * 关键词检测器
 * 使用 Vosk 进行关键词检测，通过 WebRTC APM 进行前处理
 */
class KeywordDetector : KeywordDetectorApi {
    private val logger = LogManager.getLogger("KeywordDetector")
    
    // Vosk 检测器实例
    private val voskDetector = VoskKeywordDetector()
    
    // 增加回调式音频处理器
    private val audioProcessor = CallbackAudioProcessor()
    
    // 当前状态
    private var isListening = false
    private var isInitialized = false
    private val _detectorState = MutableStateFlow(KeywordDetectorApi.DetectorState.IDLE)
    override val detectorState: StateFlow<KeywordDetectorApi.DetectorState> = _detectorState.asStateFlow()
    
    // 检测配置
    private var sensitivity: Float = 0.75f
    
    // 回调
    private var keywordCallback: ((String) -> Unit)? = null
    
    // 关键词列表
    private val keywords = mutableListOf<String>()
    
    // 音频采集器
    private val acquisition = PortAudioAcquisition()
    
    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Default)
    
    // VAD参数 - 使用WebRTC提供的VAD功能
    private val vadDebounceFrames = 5   // 从10降低到5，减少所需的连续帧数
    private var consecutiveVoiceFrames = 0
    
    // 音频质量判断参数
    // 当 calculateRmsEnergy 归一化到 0~1 区间后，正常语音 RMS ≈ 0.03~0.3。
    // 设置 0.02 作为下限，过滤极低噪声。
    private val minValidRms = 0.02
    
    // 添加计数器以限制日志
    private var audioReadCounter = 0
    
    /**
     * 初始化关键词检测器
     * @param modelPath 模型路径
     * @param sensitivity 敏感度 [0,1]
     * @return 初始化是否成功
     */
    override fun initialize(modelPath: String, sensitivity: Float): Boolean {
        logger.info("KeywordDetector.initialize() 被调用")
        
        if (isInitialized) {
            logger.warn("关键词检测器已经初始化")
            return true
        }
        
        this.sensitivity = sensitivity
        
        // 初始化 Vosk 检测器
        if (!voskDetector.initialize(modelPath, sensitivity)) {
            logger.error("Vosk 关键词检测器初始化失败")
            return false
        }
        
        // 获取 PortAudioDevice 的单例，并从中获取音频参数
        val paDevice = PortAudioDevice.getInstance()
        val currentRate = paDevice.getSampleRate()
        val currentChannels = paDevice.getChannels()
        
        // 初始化音频处理器
        if (!audioProcessor.initialize(currentRate, currentChannels)) {
            logger.error("音频处理器初始化失败")
            return false
        }
        
        // 配置音频处理器回调
        audioProcessor.setProcessedAudioCallback { processedData, size ->
            // 使用Vosk检测处理后的音频
            if (isListening && size > 0) {
                voskDetector.detect(processedData)
            }
        }
        
        // 配置VAD回调
        audioProcessor.setVadCallback { hasVoice ->
            // 可选：处理VAD状态变化
        }
        
        isInitialized = true
        _detectorState.value = KeywordDetectorApi.DetectorState.IDLE
        logger.info("关键词检测器初始化成功")
        return true
    }

    /**
     * 添加关键词
     * @param keyword 关键词
     */
    override fun addKeyword(keyword: String) {
        if (!keywords.contains(keyword)) {
            keywords.add(keyword)
            voskDetector.addKeyword(keyword)
            logger.info("添加关键词: $keyword")
        }
    }
    
    /**
     * 设置检测到关键词时的回调
     * @param callback 回调函数
     */
    fun setKeywordCallback(callback: (String) -> Unit) {
        this.keywordCallback = callback
        voskDetector.setKeywordCallback(callback)
        logger.info("已设置关键词检测回调")
    }
    
    /**
     * 开始监听关键词
     * @return 是否成功启动
     */
    override suspend fun startListening(): Boolean {
        logger.info("KeywordDetector.startListening() 被调用")
        
        if (!isInitialized) {
            logger.error("关键词检测器未初始化")
            return false
        }
        
        if (isListening) {
            logger.warn("关键词检测器已经在监听中")
            return true
        }
        
        // 使用回调式处理器启动音频处理
        val success = audioProcessor.startProcessing()
        if (!success) {
            logger.error("启动音频处理器失败")
            return false
        }

        isListening = true
        _detectorState.value = KeywordDetectorApi.DetectorState.LISTENING
        logger.info("startListening流程结束，状态: LISTENING")
        return true
    }
    
    /**
     * 停止监听关键词
     */
    override fun stopListening() {
        logger.info("KeywordDetector.stopListening() 被调用")
        
        if (!isListening) {
            logger.warn("关键词检测器未在监听")
            return
        }
        
        // 停止音频处理器
        audioProcessor.stopProcessing()

        isListening = false
        _detectorState.value = KeywordDetectorApi.DetectorState.IDLE
        logger.info("关键词检测器已停止监听")
    }
    
    /**
     * 用于API兼容性的检测方法 - 字节数组版本
     * 此方法不在接口中定义，但为了兼容性提供
     * @param audioData 音频数据(字节数组)
     * @param length 数据长度
     * @return 是否检测到关键词
     */
    fun detect(audioData: ByteArray, length: Int): Boolean {
        // 在回调模式下，该方法仅作为兼容性API存在
        // 实际处理已在audioProcessor.onAudioInput中完成
        return false
    }
    
    /**
     * 处理音频帧 - 短整型数组版本
     * @param audioFrame 音频数据(短整型数组)
     * @param frameSize 帧大小
     * @return 是否检测到关键词
     */
    override fun processAudioFrame(audioFrame: ShortArray, frameSize: Int): Boolean {
        if (!isInitialized) {
            return false
        }

        try {
            // 基本输入检查
            if (audioFrame.isEmpty() || frameSize <= 0) {
                return false
            }

            // 简单的数据质量检查
            var validSamples = 0
            for (sample in audioFrame) {
                if (kotlin.math.abs(sample.toInt()) > 50) { // 超过基本噪声阈值
                    validSamples++
                }
            }

            val validRatio = validSamples.toDouble() / audioFrame.size

            // 如果有效数据太少，跳过处理
            if (validRatio < 0.01) {
                if (audioReadCounter++ % 500 == 0) {
                    logger.debug("有效样本比例过低: ${(validRatio * 100).toInt()}%")
                }
                return false
            }
            // 获取当前设备实际采样率和通道数
            val paDevice = PortAudioDevice.getInstance()
            val currentRate = paDevice.getSampleRate()
            val currentChannels = paDevice.getChannels()

            // 获取/创建对应的 WebRTC APM 实例
            // APM实例将根据实际的currentRate和currentChannels进行内部配置或重采样
            val apm = WebRtcApmSingleton.getInstance(currentRate, currentChannels) ?: return false

            // 使用APM处理音频
            // WebRtcApm.processFrame 内部会处理重采样到APM的目标速率（如16kHz）
            val processedData = apm.processFrame(audioFrame)

            // 查询VAD结果 (APM内部的VAD是基于其处理速率的，例如16kHz)
            val hasVoiceActivity = apm.isVoiceDetected()

            // 计算处理后的能量 (能量计算也是基于APM处理后的数据)
            val processedRms = apm.calculateEnergy(processedData)

            // 语音判定：VAD + 能量阈值 + 数据质量
            val isRealVoice = hasVoiceActivity &&
                    processedRms > minValidRms &&
                    processedRms < 1.0 && // 避免异常高能量
                    validRatio > 0.1

            // 简化日志输出
            if (audioReadCounter++ % 1000 == 0 || (isRealVoice && audioReadCounter % 100 == 0)) {
                logger.debug("音频状态: RMS=$processedRms, VAD=$hasVoiceActivity, 有效=${(validRatio*100).toInt()}%, 语音=$isRealVoice")
            }

            // 只有检测到真实语音时才进行关键词检测
            return if (isRealVoice) {
                voskDetector.detect(processedData)
            } else {
                false
            }

        } catch (e: Exception) {
            logger.error("处理音频帧异常: ${e.message}")
            return false
        }
    }

    /**
     * 设置敏感度
     * @param sensitivity 敏感度值 [0,1]
     */
    override fun setSensitivity(sensitivity: Float) {
        this.sensitivity = sensitivity.coerceIn(0f, 1f)
        voskDetector.setSensitivity(this.sensitivity)
        logger.info("设置敏感度: $sensitivity")
    }
    
    /**
     * 获取当前敏感度
     * @return 当前敏感度值
     */
    override fun getSensitivity(): Float {
        return sensitivity
    }
    
    /**
     * 释放资源
     */
    override fun release() {
        logger.info("KeywordDetector.release() 被调用")
        
        if (isListening) {
            stopListening()
        }
        
        if (isInitialized) {
            voskDetector.release()
            audioProcessor.release()
            isInitialized = false
        }
        
        _detectorState.value = KeywordDetectorApi.DetectorState.IDLE
        logger.info("关键词检测器资源已释放")
    }

    /**
     * 生成诊断报告
     */
    fun generateDiagnostics(): String {
        val sb = StringBuilder()
        sb.appendLine("===== 关键词检测器诊断 =====")
        sb.appendLine("初始化状态: $isInitialized")
        sb.appendLine("监听状态: $isListening")
        sb.appendLine("检测器状态: ${detectorState.value}")
        sb.appendLine("敏感度: $sensitivity")
        sb.appendLine("关键词列表: ${keywords.joinToString(", ")}")
        return sb.toString()
    }
} 