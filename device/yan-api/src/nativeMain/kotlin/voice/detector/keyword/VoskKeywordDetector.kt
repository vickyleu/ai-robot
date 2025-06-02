@file:OptIn(ExperimentalForeignApi::class)

package voice.detector.keyword

import com.airobot.core.utils.format
import kotlinx.cinterop.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Clock.System
import voice.util.LogManager
import voice.audio.recognition.VoskSpeechRecognizer
import kotlinx.serialization.json.*
import voice.util.AudioDefaults
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
    private val maxBufferSize = AudioDefaults.VOSK_KEYWORD_MAX_BUFFER_SIZE // 增加到32000，即2秒@16kHz，减少处理频率
    
    // 检测控制
    private var lastDetectionTime = 0L
    private val detectionCooldownMs = 1000L // 从2000L减少到1000L，提高响应速度

    // 关键词缓存和优化
    private val keywordCache = mutableMapOf<String, Long>()
    private val keywordCacheDuration = AudioDefaults.KEYWORD_CACHE_DURATION_MS // 从1000L减少到500L，减少重复触发间隔

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
            val defaultKeywords = listOf("小度", "你好")
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

            // 累积音频数据到缓冲区
            val newBuffer = audioBuffer + byteData
            audioBuffer = if (newBuffer.size > maxBufferSize) {
                // 如果缓冲区太大，保留最新的数据
                newBuffer.takeLast(maxBufferSize).toByteArray()
            } else {
                newBuffer
            }

            // 添加调试日志
            if (audioBuffer.size % 3200 == 0) { // 从每100ms改为每200ms记录一次
                logger.debug("音频缓冲区状态: 当前大小=${audioBuffer.size}字节 (${audioBuffer.size/32}ms)")
            }

            // 只有当缓冲区有足够数据时才进行识别
            val minBufferForRecognition = 3200 // 从6400减少到3200（200ms @ 16kHz），提高响应速度
            if (audioBuffer.size < minBufferForRecognition) {
                // 减少日志频率
                if (audioBuffer.size % 1600 == 0) { // 只在特定大小时记录
                    logger.debug("缓冲区不足: ${audioBuffer.size}/${minBufferForRecognition}字节")
                }
                return false
            }

            // 检查冷却期
            val currentTime = System.now().toEpochMilliseconds()
            if (currentTime - lastDetectionTime < detectionCooldownMs) {
                return false
            }

            // 减少识别开始的日志频率
            if (audioBuffer.size % 1600 == 0) { // 只在特定大小时记录
                logger.info("开始Vosk识别: 缓冲区大小=${audioBuffer.size}字节 (${audioBuffer.size/32}ms)")
            }

            // 🔧 调试：检查音频数据质量
            val audioShorts = voice.util.AudioUtils.byteArrayToShortArray(audioBuffer)
            val maxAmp = audioShorts.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
            val nonZeroCount = audioShorts.count { it != 0.toShort() }
            val zeroRatio = (audioShorts.size - nonZeroCount).toFloat() / audioShorts.size
            logger.debug("🔧 Vosk音频质量: 最大振幅=$maxAmp, 零值比例=${"%.3f".format(zeroRatio)}, 样本数=${audioShorts.size}")

            // 调用识别器处理整个缓冲区
            val result = recognizer.recognize(audioBuffer, audioBuffer.size, currentTime)
            // 检查结果中的关键词
            if (result.success && result.text.isNotBlank()) {
                logger.info("Vosk识别结果: \"${result.text}\"")
                
                // 检测关键词的逻辑
                for (keyword in keywords) {
                    if (result.text.contains(keyword, ignoreCase = true)) {
                        // 检查关键词缓存，避免重复触发
                        val keywordLastTime = keywordCache[keyword] ?: 0L
                        if (currentTime - keywordLastTime > keywordCacheDuration) {
                            logger.info("检测到关键词: $keyword")
                            keywordCache[keyword] = currentTime
                            lastDetectionTime = currentTime
                            
                            // 清空缓冲区，避免重复识别
                            audioBuffer = ByteArray(0)
                            
                            keywordCallback?.invoke(keyword)
                            return true
                        }
                    }
                }
            } else {
                logger.debug("Vosk识别无结果: success=${result.success}, text=\"${result.text}\"")
                // 🔧 新增：更详细的失败原因分析
                if (result.success && result.text.isBlank()) {
                    logger.warn("⚠️ Vosk处理成功但返回空文本，可能原因: 1)音频质量不足 2)非语音内容 3)模型语言不匹配 4)置信度过低")
                } else if (!result.success) {
                    logger.warn("⚠️ Vosk处理失败: ${result.errorMessage}")
                }
            }

            // 如果没有检测到关键词，但缓冲区已满，清理一部分旧数据
            if (audioBuffer.size >= maxBufferSize) {
                val keepSize = maxBufferSize / 2
                audioBuffer = audioBuffer.takeLast(keepSize).toByteArray()
                logger.debug("缓冲区已满，清理到${keepSize}字节")
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