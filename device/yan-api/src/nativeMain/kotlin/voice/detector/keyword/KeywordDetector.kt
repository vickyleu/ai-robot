@file:OptIn(ExperimentalForeignApi::class)

package voice.detector.keyword

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import voice.api.KeywordDetectorApi
import voice.audio.processing.WebRtcApmSingleton
import voice.util.LogManager
import voice.util.AudioDefaults
import voice.util.AudioUtils
import voice.audio.recognition.VoskSpeechRecognizer

/**
 * 关键词检测器
 * 使用 Vosk 进行关键词检测，通过 WebRTC APM 进行前处理
 */
class KeywordDetector : KeywordDetectorApi {
    private val logger = LogManager.getLogger("KeywordDetector")
    
    // Vosk 检测器实例
    private val voskDetector = VoskKeywordDetector()
    
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
    
    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Default)
    
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
        
        // 初始化 WebRTC APM
        WebRtcApmSingleton.getInstance(
            sampleRate = AudioDefaults.TARGET_SAMPLE_RATE,
            channels = 1
        )
        
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
        if (!isInitialized || !isListening) {
            return false
        }
        
        try {
            // 将ByteArray转换为ShortArray
            val shorts = AudioUtils.byteArrayToShortArray(audioData, length)
            
            // 使用ShortArray版本处理
            return processAudioFrame(shorts, shorts.size)
        } catch (e: Exception) {
            logger.error("关键词检测出错: ${e.message}")
            return false
        }
    }
    
    /**
     * 处理音频帧 - 短整型数组版本
     * @param audioFrame 音频数据(短整型数组)
     * @param frameSize 帧大小
     * @return 是否检测到关键词
     */
    override fun processAudioFrame(audioFrame: ShortArray, frameSize: Int): Boolean {
        if (!isInitialized || !isListening) {
            return false
        }
        
        try {
            // 使用 WebRTC APM 进行音频预处理
            val processedData = WebRtcApmSingleton.processFrame(audioFrame)
            
            // 使用 Vosk 检测关键词
            val detected = voskDetector.detect(processedData)
            
            if (detected) {
                _detectorState.value = KeywordDetectorApi.DetectorState.DETECTED
            }
            
            return detected
        } catch (e: Exception) {
            logger.error("关键词处理出错: ${e.message}")
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