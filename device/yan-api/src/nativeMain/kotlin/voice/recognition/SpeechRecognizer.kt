package voice.processor

import com.airobot.voskinterop.VoskModel
import com.airobot.voskinterop.VoskRecognizer
import com.airobot.voskinterop.vosk_model_free
import com.airobot.voskinterop.vosk_model_new
import com.airobot.voskinterop.vosk_recognizer_accept_waveform_s
import com.airobot.voskinterop.vosk_recognizer_free
import com.airobot.voskinterop.vosk_recognizer_new
import com.airobot.voskinterop.vosk_recognizer_partial_result
import com.airobot.voskinterop.vosk_recognizer_reset
import com.airobot.voskinterop.vosk_recognizer_result
import com.airobot.voskinterop.vosk_recognizer_set_words
import com.airobot.voskinterop.vosk_recognizer_set_grm
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.toKString
import kotlinx.cinterop.set
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import voice.audio.AudioPipeline
import voice.audio.RecognitionMetrics
import voice.util.LogManager
import kotlin.time.ExperimentalTime

/**
 * 语音识别实现
 * 负责对音频进行语音识别，包括关键词检测和完整语音识别
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
class SpeechRecognizer : AudioPipeline.SpeechRecognition {
    private val logger = LogManager.getLogger("SpeechRecognizer")
    
    // 模型和识别器状态
    private var model: CPointer<VoskModel>? = null
    private var recognizer: CPointer<VoskRecognizer>? = null
    private var isInitialized = false
    
    // 诊断统计
    private var totalCalls = 0
    private var successfulCalls = 0
    private var failedCalls = 0
    private var lastErrorLogging = 0L
    private val ERROR_LOG_INTERVAL = 5000L  // 5秒内不重复记录相同错误
    
    /**
     * 初始化语音识别器
     */
    fun initialize(modelPath: String): Boolean {
        if (isInitialized) {
            logger.warn("语音识别器已经初始化")
            return true
        }
        
        logger.info("初始化语音识别器，模型路径: $modelPath")
        
        try {
            // 加载模型
            model = vosk_model_new(modelPath)
            if (model == null) {
                logger.error("无法加载语音模型: $modelPath")
                return false
            }
            
            // 创建识别器
            recognizer = vosk_recognizer_new(model, 16000f)
            if (recognizer == null) {
                logger.error("无法创建语音识别器")
                vosk_model_free(model)
                model = null
                return false
            }
            
            // 设置默认关键词
            val keywords = "小样,嘿小样,你好小样,小样小样"
            updateKeywords(keywords)
            
            isInitialized = true
            logger.info("语音识别器初始化成功，默认关键词: $keywords")
            return true
        } catch (e: Exception) {
            logger.error("初始化语音识别器失败: ${e.message}")
            cleanupResources()
            return false
        }
    }
    
    /**
     * 更新关键词列表
     * @param keywords 以逗号分隔的关键词字符串
     */
    fun updateKeywords(keywords: String) {
        if (!isInitialized || recognizer == null) {
            logger.warn("语音识别器未初始化，无法更新关键词")
            return
        }
        
        try {
            // 设置启用带时间信息的单词输出(1表示启用)
            vosk_recognizer_set_words(recognizer, 1)
            
            // 将逗号分隔的字符串拆分成关键词列表
            val keywordList = keywords.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            
            // 构建JSON格式的关键词数组，使用更健壮的处理方式
            val jsonArray = buildString {
                append("[")
                keywordList.forEachIndexed { index, keyword ->
                    if (index > 0) append(",")
                    append("\"")
                    // 转义JSON特殊字符
                    append(keyword.replace("\\", "\\\\")   // 先处理反斜杠
                                 .replace("\"", "\\\"")    // 处理引号
                                 .replace("\n", "\\n")     // 处理换行
                                 .replace("\r", "\\r")     // 处理回车
                                 .replace("\t", "\\t"))    // 处理制表符
                    append("\"")
                }
                append("]")
            }
            
            // 设置关键词，使用JSON数组格式
            vosk_recognizer_set_grm(recognizer, jsonArray)
            logger.info("成功更新语音关键词: $jsonArray")
        } catch (e: Exception) {
            logger.error("更新语音关键词时发生异常: ${e.message}")
        }
    }
    
    /**
     * 进行语音识别
     */
    override fun recognize(audio: ByteArray, length: Int): AudioPipeline.SpeechRecognition.RecognitionResult {
        if (!isInitialized) {
            val error = "语音识别器未初始化"
            logger.error(error)
            return createErrorResult(error, -1)
        }
        
        totalCalls++
        val startTime = LogManager.getCurrentTimeMillis()
        logger.info("【调试】语音识别开始 #${totalCalls}: 音频长度=${length}, 时间=${startTime}")
        
        try {
            // 异常情况检查
            if (audio.isEmpty() || length <= 0) {
                logger.info("【调试】语音识别收到空音频: 长度=${length}")
                if (shouldLogError()) {
                    logger.warn("无效的音频数据: 长度=$length")
                }
                return createErrorResult("无效的音频数据", 1)
            }
            
            // 检查识别器状态
            if (recognizer == null) {
                logger.info("【调试】语音识别器无效")
                if (shouldLogError()) {
                    logger.error("语音识别器无效")
                }
                return createErrorResult("识别器无效", 2)
            }
            
            // 计算音频能量以确定是否有声音
            var energy = 0.0
            for (i in 0 until length step 2) {
                if (i + 1 < length) {
                    val sample = (audio[i].toInt() and 0xFF) or ((audio[i + 1].toInt() and 0xFF) shl 8)
                    val signedSample = if (sample and 0x8000 != 0) {
                        sample - 0x10000
                    } else {
                        sample
                    }
                    energy += signedSample * signedSample
                }
            }
            energy = kotlin.math.sqrt(energy / (length / 2))
            logger.info("【调试】语音音频能量: $energy")
            
            // 将ByteArray转换为16位PCM格式的ShortArray
            val shortData = ShortArray(length / 2)
            for (i in shortData.indices) {
                val lo = audio[i * 2].toInt() and 0xff
                val hi = audio[i * 2 + 1].toInt() and 0xff
                shortData[i] = ((hi shl 8) or lo).toShort()
            }
            
            // 创建临时缓冲区
            val shortBuffer = nativeHeap.allocArray<ShortVar>(shortData.size)
            
            try {
                // 复制数据到缓冲区
                for (i in shortData.indices) {
                    shortBuffer[i] = shortData[i]
                }
                
                logger.info("【调试】语音处理音频: 大小=${shortData.size}")
                
                // 使用short接口处理音频
                val result = vosk_recognizer_accept_waveform_s(recognizer, shortBuffer, shortData.size)
                
                logger.info("【调试】语音处理结果: 状态=${result}")
                
                // 获取识别结果
                val json = when (result) {
                    0 -> vosk_recognizer_partial_result(recognizer) // 部分结果
                    1 -> vosk_recognizer_result(recognizer)         // 最终结果
                    else -> null
                }?.toKString()
                
                // 处理结果
                if (json != null) {
                    // 记录所有JSON结果以便调试
                    logger.info("【调试】语音识别原始JSON: $json")
                    
                    // 提取文本
                    val text = extractTextFromJson(json)
                    logger.info("【调试】语音提取文本: \"$text\"")
                    
                    // 记录成功
                    successfulCalls++
                    
                    // 记录诊断
                    if (totalCalls % 100 == 0) {
                        logger.debug("语音识别统计: 总调用=${totalCalls}, 成功=${successfulCalls}, " +
                                "失败=${failedCalls}, 成功率=${successfulCalls.toFloat() / totalCalls.toFloat() * 100f}%")
                    }
                    
                    val processingTime = LogManager.getCurrentTimeMillis() - startTime
                    
                    // 如果找到文本，记录
                    if (text.isNotEmpty()) {
                        logger.info("识别到文本: \"$text\", 处理时间: ${processingTime}ms")
                    }
                    
                    // 创建结果
                    return AudioPipeline.SpeechRecognition.RecognitionResult(
                        success = true,
                        text = text,
                        isPartial = result == 0,
                        metrics = RecognitionMetrics(
                            processingTimeMs = processingTime,
                            confidenceScore = 1.0f,
                            errorCode = 0,
                            errorMessage = ""
                        )
                    )
                } else {
                    failedCalls++
                    logger.info("【调试】语音返回空结果")
                    if (shouldLogError()) {
                        logger.error("语音处理音频失败: null结果")
                    }
                    return createErrorResult("处理失败", 3)
                }
            } finally {
                // 释放临时缓冲区
                nativeHeap.free(shortBuffer)
            }
        } catch (e: Exception) {
            failedCalls++
            logger.info("【调试】语音处理异常: ${e.message}")
            if (shouldLogError()) {
                logger.error("语音处理异常: ${e.message}")
            }
            return createErrorResult("处理异常: ${e.message}", 4)
        }
    }
    
    /**
     * 重置识别器状态
     */
    fun reset() {
        if (isInitialized && recognizer != null) {
            vosk_recognizer_reset(recognizer)
            logger.info("语音识别器已重置")
        }
    }
    
    /**
     * 释放资源
     */
    fun release() {
        logger.info("释放语音识别资源")
        cleanupResources()
        isInitialized = false
    }
    
    /**
     * 清理资源
     */
    private fun cleanupResources() {
        if (recognizer != null) {
            vosk_recognizer_free(recognizer)
            recognizer = null
        }
        
        if (model != null) {
            vosk_model_free(model)
            model = null
        }
    }
    
    /**
     * 从JSON字符串中提取文本
     */
    private fun extractTextFromJson(json: String): String {
        // 简单解析，在JSON库不可用的情况下使用正则表达式
        val textPattern = "\"text\"\\s*:\\s*\"([^\"]*)\""
        val regex = Regex(textPattern)
        val match = regex.find(json)
        return match?.groupValues?.getOrNull(1) ?: ""
    }
    
    /**
     * 创建错误结果
     */
    private fun createErrorResult(errorMessage: String, errorCode: Int): AudioPipeline.SpeechRecognition.RecognitionResult {
        return AudioPipeline.SpeechRecognition.RecognitionResult(
            success = false,
            text = "",
            isPartial = false,
            metrics = RecognitionMetrics(
                processingTimeMs = 0,
                confidenceScore = 0.0f,
                errorCode = errorCode,
                errorMessage = errorMessage
            )
        )
    }
    
    /**
     * 判断是否应该记录错误
     * 用于限制错误日志频率
     */
    private fun shouldLogError(): Boolean {
        val now = LogManager.getCurrentTimeMillis()
        if (now - lastErrorLogging > ERROR_LOG_INTERVAL) {
            lastErrorLogging = now
            return true
        }
        return false
    }
}