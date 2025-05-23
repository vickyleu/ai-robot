@file:OptIn(ExperimentalForeignApi::class)

package voice.detector.keyword

import kotlinx.cinterop.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Clock.System
import voice.util.LogManager
import voice.audio.recognition.VoskSpeechRecognizer
import kotlinx.serialization.json.*
import kotlin.time.ExperimentalTime
import voice.util.AudioUtils

/**
 * Vosk 关键词检测器
 * 基于Vosk实现关键词检测功能，替代Porcupine
 */
@OptIn(ExperimentalTime::class)
class VoskKeywordDetector {
    private val logger = LogManager.getLogger("VoskKeywordDetector")
    
    // Vosk识别器
    private val recognizer = VoskSpeechRecognizer()
    
    // 状态
    private var isInitialized = false
    private var sensitivity = 0.5f
    
    // 关键词和回调
    private val keywords = mutableListOf<String>()
    private var keywordCallback: ((String) -> Unit)? = null
    
    // 音频缓冲区
    private var audioBuffer = ByteArray(0)
    private val maxBufferSize = 16000 // 1秒@16kHz
    
    // 检测控制
    private var lastDetectionTime = 0L
    private val detectionCooldownMs = 1500L // 检测冷却期

    // 关键词缓存和优化
    private val keywordCache = mutableMapOf<String, Long>()
    private val keywordCacheDuration = 1000L // 1秒内不重复触发同一关键词

    /**
     * 初始化Vosk关键词检测器
     * @param modelPath Vosk模型路径
     * @param sensitivity 敏感度 [0,1]
     * @return 初始化是否成功
     */
    fun initialize(modelPath: String, sensitivity: Float): Boolean {
        if (isInitialized) {
            logger.warn("Vosk关键词检测器已经初始化")
            return true
        }
        
        this.sensitivity = sensitivity
        
        try {
            // 初始化Vosk识别器
            if (!recognizer.initialize(modelPath)) {
                logger.error("Vosk识别器初始化失败")
                return false
            }
            
            // 预定义一些关键词
            val defaultKeywords = listOf("小度", "你好", "嗨", "在吗", "小兔子")
            keywords.addAll(defaultKeywords)
            
            // 更新关键词
            recognizer.updateKeywords(keywords)
            
            isInitialized = true
            logger.info("Vosk关键词检测器初始化成功：模型=$modelPath, 敏感度=$sensitivity")
            return true
        } catch (e: Exception) {
            logger.error("Vosk关键词检测器初始化异常: ${e.message}")
            return false
        }
    }
    
    /**
     * 添加关键词
     * @param keyword 关键词
     */
    fun addKeyword(keyword: String) {
        if (keyword.isBlank() || keywords.contains(keyword)) {
            return
        }
        
        keywords.add(keyword)
        
        // 更新识别器关键词
        if (isInitialized) {
            recognizer.updateKeywords(keywords)
            logger.info("添加关键词: $keyword")
        }
    }
    
    /**
     * 设置关键词回调
     * @param callback 回调函数
     */
    fun setKeywordCallback(callback: (String) -> Unit) {
        this.keywordCallback = callback
    }
    
    /**
     * 设置敏感度
     * @param sensitivity 敏感度值 [0,1]
     */
    fun setSensitivity(sensitivity: Float) {
        this.sensitivity = sensitivity.coerceIn(0f, 1f)
    }

    fun detect(audioData: ShortArray): Boolean {
        if (!isInitialized) {
            return false
        }

        try {
            // 转换 ShortArray 到 ByteArray
            val byteData = voice.util.AudioUtils.shortArrayToByteArray(audioData)

            // 调用识别器
            val result = recognizer.recognize(byteData, byteData.size, System.now().toEpochMilliseconds())

            // 检查结果中的关键词
            if (result.success && result.text.isNotBlank()) {
                // 检测关键词的逻辑
                for (keyword in keywords) {
                    if (result.text.contains(keyword)) {
                        logger.info("检测到关键词: $keyword")
                        keywordCallback?.invoke(keyword)
                        return true
                    }
                }
            }

            return false
        } catch (e: Exception) {
            logger.error("关键词检测异常: ${e.message}")
            return false
        }
    }

    /**
     * 重置音频缓冲区
     */
    private fun resetBuffer() {
        audioBuffer = ByteArray(0)
    }
    
    /**
     * 释放资源
     */
    fun release() {
        if (isInitialized) {
            recognizer.release()
            resetBuffer()
            isInitialized = false
            logger.info("Vosk关键词检测器资源已释放")
        }
    }
} 