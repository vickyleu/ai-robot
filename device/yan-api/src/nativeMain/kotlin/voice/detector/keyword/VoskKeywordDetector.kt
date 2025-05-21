@file:OptIn(ExperimentalForeignApi::class)

package voice.detector.keyword

import kotlinx.cinterop.*
import kotlinx.datetime.Clock
import voice.util.LogManager
import voice.audio.recognition.VoskSpeechRecognizer
import kotlinx.serialization.json.*
import kotlin.time.ExperimentalTime

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
    
    /**
     * 检测音频中是否含有关键词
     * @param audioData 音频数据
     * @return 是否检测到关键词
     */
    fun detect(audioData: ShortArray): Boolean {
        if (!isInitialized) {
            return false
        }
        
        try {
            // 短整型数组转字节数组
            val audioBytes = ByteArray(audioData.size * 2)
            for (i in audioData.indices) {
                val value = audioData[i]
                audioBytes[i * 2] = (value.toInt() and 0xFF).toByte()
                audioBytes[i * 2 + 1] = (value.toInt() shr 8).toByte()
            }
            
            // 积累音频数据
            accumulateAudio(audioBytes)
            
            // 检查冷却时间
            val now = Clock.System.now().toEpochMilliseconds()
            if (now - lastDetectionTime < detectionCooldownMs) {
                return false
            }
            
            // 处理音频数据
            val result = recognizer.recognize(audioBuffer, audioBuffer.size, now)
            
            // 检查是否有文本结果
            if (result.text.isNotEmpty()) {
                // 检测文本中是否包含关键词
                val detectedKeyword = findKeyword(result.text)
                
                if (detectedKeyword != null) {
                    logger.info("检测到关键词: $detectedKeyword, 置信度: ${result.confidence}")
                    
                    // 只有置信度达到阈值时才触发回调
                    if (result.confidence >= sensitivity) {
                        keywordCallback?.invoke(detectedKeyword)
                        lastDetectionTime = now
                        resetBuffer() // 重置缓冲区
                        return true
                    }
                }
            }
            
            return false
        } catch (e: Exception) {
            logger.error("Vosk关键词检测异常: ${e.message}")
            return false
        }
    }
    
    /**
     * 在文本中查找关键词
     * @param text 输入文本
     * @return 找到的关键词，未找到则返回null
     */
    private fun findKeyword(text: String): String? {
        if (text.isBlank() || keywords.isEmpty()) {
            return null
        }
        
        val lowerText = text.lowercase()
        for (keyword in keywords) {
            if (keyword.isBlank()) continue
            
            // 检查文本是否包含关键词
            if (lowerText.contains(keyword.lowercase())) {
                return keyword
            }
        }
        
        return null
    }
    
    /**
     * 积累音频数据
     */
    private fun accumulateAudio(newData: ByteArray) {
        // 将新数据添加到缓冲区
        val combined = ByteArray(audioBuffer.size + newData.size)
        audioBuffer.copyInto(combined)
        newData.copyInto(combined, audioBuffer.size)
        audioBuffer = combined
        
        // 如果缓冲区过大，保留最新的部分
        if (audioBuffer.size > maxBufferSize) {
            val newBuffer = ByteArray(maxBufferSize)
            audioBuffer.copyInto(
                newBuffer, 
                0, 
                audioBuffer.size - maxBufferSize, 
                audioBuffer.size
            )
            audioBuffer = newBuffer
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