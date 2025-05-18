package voice.audio.recognition

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
import voice.api.recognition.ISpeechRecognizer
import voice.audio.AudioPipeline
import voice.audio.RecognitionMetrics
import voice.util.LogManager
import kotlin.time.ExperimentalTime
import kotlin.math.sqrt

// 用于解析JSON
import kotlinx.serialization.json.*

/**
 * Vosk语音识别实现
 * 负责对音频进行语音识别，包括关键词检测和完整语音识别
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
class VoskSpeechRecognizer : AudioPipeline.SpeechRecognition {
    private val logger = LogManager.getLogger("VoskSpeechRecognizer")
    
    // Vosk模型和识别器
    private var voskModel: CPointer<VoskModel>? = null
    private var voskRecognizer: CPointer<VoskRecognizer>? = null
    
    // 配置
    private val sampleRate = 16000
    
    // 内部状态
    internal var isInitialized = false
    private var recognitionCount = 0
    private var accumulatedAudio = ByteArray(0)
    private var lastRecognitionTime = 0L
    private val minAudioBufferSize = 1024 // 最小积累音频大小
    private val maxAudioBufferSize = 32768 // 最大积累音频大小 (2秒@16kHz, 16位)
    private val recognitionCooldownMs = 300L // 识别间隔
    
    // 关键词和命令管理 
    private val registeredKeywords = mutableListOf<String>()
    private val shortCommandKeywords = listOf(
        "你好", "嗨", "哈喽", "开始", "停止", "暂停", "继续", 
        "音量大", "音量小", "关闭", "打开", "启动", "退出"
    )
    
    // Vosk JSON结果解析
    private data class VoskResult(
        val text: String,             // 完整文本
        val partialText: String,      // 部分识别文本
        val confidence: Float,        // 置信度
        val foundKeywords: List<String>, // 检测到的关键词
        val alternatives: List<String>, // 替代文本
        val resultType: ResultType    // 结果类型
    )
    
    enum class ResultType {
        FINAL,      // 最终结果
        PARTIAL,    // 部分结果
        EMPTY,      // 空结果
        ERROR       // 错误
    }
    
    /**
     * 获取当前已注册的关键词列表
     */
    fun getCurrentKeywords(): List<String> {
        return registeredKeywords.toList()
    }
    
    /**
     * 处理音频数据
     * @param audio 音频数据
     * @param length 数据长度
     * @return 识别结果
     */
    override fun recognize(audio: ByteArray, length: Int): AudioPipeline.SpeechRecognition.RecognitionResult {
        if (!isInitialized || voskRecognizer == null) {
            logger.error("Vosk识别器未初始化或已失效")
            return createErrorResult("识别器未初始化") 
        }
        
        recognitionCount++
        val startTime = LogManager.getCurrentTimeMillis()
        
        logger.debug("音频数据接收: ${length}字节, 第${recognitionCount}次调用")
        
        try {
            // 只在能量高时处理音频（避免处理静音）
            val energy = calculateEnergy(audio, length)
            if (energy < 100 && recognitionCount % 10 != 0) {
                return createEmptyResult()
            }
            
            // 累积音频数据
            accumulateAudio(audio, length)
            
            // 检查是否应该执行识别（避免过于频繁的调用）
            val timeElapsed = startTime - lastRecognitionTime
            val bufferSizeAdequate = accumulatedAudio.size >= minAudioBufferSize
            
            if (!bufferSizeAdequate || timeElapsed < recognitionCooldownMs) {
                return createEmptyResult()
            }
            
            // 处理音频数据
            val voskResult = processAudioWithVosk(accumulatedAudio)
            val endTime = LogManager.getCurrentTimeMillis()
            lastRecognitionTime = endTime
            
            // 根据Vosk处理结果创建统一的返回结果
            return when (voskResult.resultType) {
                ResultType.FINAL -> {
                    // 完成一轮完整识别，清空已处理的音频
                    accumulatedAudio = ByteArray(0)
                    
                    // 记录识别结果及关键词
                    if (voskResult.text.isNotBlank()) {
                        logger.info("识别结果: \"${voskResult.text}\"")
                        if (voskResult.foundKeywords.isNotEmpty()) {
                            logger.info("检测到关键词: ${voskResult.foundKeywords.joinToString(", ")}")
                        }
                    }
                    
                    // 创建最终结果
                    AudioPipeline.SpeechRecognition.RecognitionResult(
                        success = true,
                        text = voskResult.text,
                        isPartial = false,
                        metrics = RecognitionMetrics(
                            processingTimeMs = endTime - startTime,
                            confidenceScore = voskResult.confidence,
                            errorCode = 0,
                            errorMessage = ""
                        )
                    )
                }
                
                ResultType.PARTIAL -> {
                    // 部分结果不清空音频缓冲
                    AudioPipeline.SpeechRecognition.RecognitionResult(
                        success = true,
                        text = voskResult.partialText,
                        isPartial = true,
                        metrics = RecognitionMetrics(
                            processingTimeMs = endTime - startTime,
                            confidenceScore = voskResult.confidence,
                            errorCode = 0,
                            errorMessage = ""
                        )
                    )
                }
                
                ResultType.EMPTY -> {
                    createEmptyResult()
                }
                
                ResultType.ERROR -> {
                    createErrorResult("处理过程中出错")
                }
            }
        } catch (e: Exception) {
            logger.error("识别处理异常: ${e.message}")
            return createErrorResult("识别处理异常: ${e.message}")
        }
    }
    
    /**
     * 使用Vosk处理音频数据
     */
    private fun processAudioWithVosk(audioData: ByteArray): VoskResult {
        if (voskRecognizer == null) {
            return VoskResult("", "", 0f, emptyList(), emptyList(), ResultType.ERROR)
        }
        
        try {
            // 准备处理音频 - 将字节数组转换为短整型数组
            val samples = convertAudioToShorts(audioData)
            
            // 调用Vosk处理音频
            val processResult = vosk_recognizer_accept_waveform_s(
                voskRecognizer, 
                samples,
                (audioData.size / 2)
            )
            
            // 释放临时缓冲区
            nativeHeap.free(samples)
            
            // 获取并解析JSON结果
            return if (processResult == 0) {
                // 部分结果
                val partialJson = vosk_recognizer_partial_result(voskRecognizer)?.toKString() ?: "{}"
                parsePartialResult(partialJson)
            } else {
                // 最终结果
                val finalJson = vosk_recognizer_result(voskRecognizer)?.toKString() ?: "{}"
                parseFinalResult(finalJson)
            }
        } catch (e: Exception) {
            logger.error("Vosk处理错误: ${e.message}")
            return VoskResult("", "", 0f, emptyList(), emptyList(), ResultType.ERROR)
        }
    }
    
    /**
     * 解析Vosk最终结果JSON
     */
    private fun parseFinalResult(jsonStr: String): VoskResult {
        try {
            logger.debug("Vosk最终JSON: $jsonStr")
            val json = Json.parseToJsonElement(jsonStr).jsonObject
            
            // 提取主要文本
            val text = json["text"]?.jsonPrimitive?.content ?: ""
            
            // 提取置信度 - 从Vosk返回的JSON中提取实际置信度值
            val confidence = extractConfidence(json, text)
            
            // 提取替代结果数组
            val alternatives = mutableListOf<String>()
            json["alternatives"]?.jsonArray?.forEach { alt ->
                alt.jsonObject["text"]?.jsonPrimitive?.content?.let { 
                    alternatives.add(it) 
                }
            }
            
            // 检查文本中是否包含已注册的关键词
            val foundKeywords = findKeywordsInText(text, alternatives)
            
            return VoskResult(
                text = text,
                partialText = "",
                confidence = confidence,
                foundKeywords = foundKeywords,
                alternatives = alternatives,
                resultType = if (text.isBlank() && alternatives.isEmpty()) ResultType.EMPTY else ResultType.FINAL
            )
        } catch (e: Exception) {
            logger.error("解析最终JSON失败: ${e.message}")
            return VoskResult("", "", 0f, emptyList(), emptyList(), ResultType.ERROR)
        }
    }
    
    /**
     * 解析Vosk部分结果JSON
     */
    private fun parsePartialResult(jsonStr: String): VoskResult {
        try {
            val json = Json.parseToJsonElement(jsonStr).jsonObject
            val partialText = json["partial"]?.jsonPrimitive?.content ?: ""
            
            // 提取置信度 - 部分结果通常置信度较低
            val confidence = extractConfidence(json, partialText, isPartial = true)
            
            // 在部分结果中也检查关键词
            val foundKeywords = findKeywordsInText(partialText, emptyList())
            
            return VoskResult(
                text = "",
                partialText = partialText,
                confidence = confidence,
                foundKeywords = foundKeywords,
                alternatives = emptyList(),
                resultType = if (partialText.isBlank()) ResultType.EMPTY else ResultType.PARTIAL
            )
        } catch (e: Exception) {
            logger.error("解析部分JSON失败: ${e.message}")
            return VoskResult("", "", 0f, emptyList(), emptyList(), ResultType.ERROR)
        }
    }
    
    /**
     * 从JSON中提取置信度
     * 根据Vosk不同版本可能包含不同字段，此处做了兼容处理
     */
    private fun extractConfidence(json: JsonObject, text: String, isPartial: Boolean = false): Float {
        // 1. 尝试从JSON中直接获取置信度
        val jsonConfidence = json["confidence"]?.jsonPrimitive?.floatOrNull
        if (jsonConfidence != null) {
            return jsonConfidence
        }
        
        // 2. 检查是否有替代结果的置信度数组
        val confidenceArray = mutableListOf<Float>()
        json["alternatives"]?.jsonArray?.forEach { alt ->
            alt.jsonObject["confidence"]?.jsonPrimitive?.floatOrNull?.let {
                confidenceArray.add(it)
            }
        }
        
        if (confidenceArray.isNotEmpty()) {
            return confidenceArray.average().toFloat() // 使用平均值
        }
        
        // 3. 基于文本长度和单词数量计算置信度
        if (text.isNotBlank()) {
            val words = text.split(Regex("\\s+"))
            // 考虑单词数量和长度 - 长度越长，单词越多，通常置信度越高
            val lengthFactor = minOf(text.length / 20.0f, 1.0f) // 最长考虑20个字符
            val wordsFactor = minOf(words.size / 5.0f, 1.0f)    // 最多考虑5个单词
            
            // 使用两个因素的加权平均
            val baseConfidence = (lengthFactor * 0.3f + wordsFactor * 0.5f).coerceIn(0.1f, 0.95f)
            
            // 部分结果的置信度打折
            return if (isPartial) baseConfidence * 0.7f else baseConfidence
        }
        
        // 4. 默认值
        return if (isPartial) 0.3f else 0.0f
    }
    
    /**
     * 在文本和替代文本中智能查找已注册的关键词
     */
    private fun findKeywordsInText(text: String, alternatives: List<String>): List<String> {
        if (registeredKeywords.isEmpty() || (text.isBlank() && alternatives.isEmpty())) {
            return emptyList()
        }
        
        val result = mutableListOf<String>()
        
        // 提取文本中的单词和短语
        val mainTextWords = extractWords(text)
        val mainTextPhrases = extractPhrases(text)
        
        // 检查主文本
        for (keyword in registeredKeywords) {
            if (keyword.isBlank()) continue
            
            // 分词匹配策略
            if (isKeywordMatch(keyword, text, mainTextWords, mainTextPhrases)) {
                result.add(keyword)
            }
        }
        
        // 检查替代文本
        for (altText in alternatives) {
            if (altText.isBlank()) continue
            
            val altWords = extractWords(altText)
            val altPhrases = extractPhrases(altText)
            
            for (keyword in registeredKeywords) {
                if (keyword.isBlank() || result.contains(keyword)) continue
                
                if (isKeywordMatch(keyword, altText, altWords, altPhrases)) {
                    result.add(keyword)
                }
            }
        }
        
        return result
    }
    
    /**
     * 提取文本中的单词
     */
    private fun extractWords(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        
        // 中文分词简化版：按字符分割
        return text.lowercase().split(Regex("[\\s,.!?;，。！？；]"))
            .filter { it.isNotBlank() }
    }
    
    /**
     * 提取文本中的短语
     */
    private fun extractPhrases(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        
        val phrases = mutableListOf<String>()
        val lowerText = text.lowercase()
        
        // 提取2-4个字的短语
        for (len in 2..4) {
            for (i in 0..lowerText.length - len) {
                phrases.add(lowerText.substring(i, i + len))
            }
        }
        
        return phrases
    }
    
    /**
     * 判断关键词是否匹配
     */
    private fun isKeywordMatch(
        keyword: String, 
        fullText: String, 
        words: List<String>, 
        phrases: List<String>
    ): Boolean {
        val lowerKeyword = keyword.lowercase()
        val lowerText = fullText.lowercase()
        
        // 1. 完全匹配
        if (lowerKeyword == lowerText) {
            return true
        }
        
        // 2. 包含匹配，但要检查边界
        if (lowerText.contains(lowerKeyword)) {
            // 检查边界 - 确保关键词是一个完整的词或短语
            val index = lowerText.indexOf(lowerKeyword)
            val endIndex = index + lowerKeyword.length
            
            // 如果关键词在开头或结尾，或者边界是非字母字符，则认为是有效匹配
            val validStart = index == 0 || !lowerText[index - 1].isLetterOrDigit()
            val validEnd = endIndex == lowerText.length || !lowerText[endIndex].isLetterOrDigit()
            
            if (validStart && validEnd) {
                return true
            }
        }
        
        // 3. 对于较短的关键词，检查它是否作为一个独立的词出现
        if (lowerKeyword.length <= 4 && words.contains(lowerKeyword)) {
            return true
        }
        
        // 4. 对于较长的关键词，检查它是否作为一个短语出现
        if (lowerKeyword.length > 2 && phrases.contains(lowerKeyword)) {
            return true
        }
        
        // 5. 特殊处理：检查否定前缀
        val negationWords = listOf("不", "不要", "不能", "别", "没有")
        for (negation in negationWords) {
            if (lowerText.contains("$negation$lowerKeyword")) {
                // 含有否定词时，不要触发关键词
                return false
            }
        }
        
        return false
    }
    
    /**
     * 积累音频数据，控制缓冲区大小
     */
    private fun accumulateAudio(newData: ByteArray, length: Int) {
        if (length <= 0) return
        
        // 积累音频数据
        val newBuffer = ByteArray(accumulatedAudio.size + length)
        accumulatedAudio.copyInto(newBuffer, 0, 0, accumulatedAudio.size)
        newData.copyInto(newBuffer, accumulatedAudio.size, 0, length)
        accumulatedAudio = newBuffer
        
        // 限制积累的最大大小
        if (accumulatedAudio.size > maxAudioBufferSize) {
            // 保留后半部分，丢弃旧数据
            val newSize = maxAudioBufferSize / 2
            val tempBuffer = ByteArray(newSize)
            accumulatedAudio.copyInto(tempBuffer, 0, accumulatedAudio.size - newSize, accumulatedAudio.size)
            accumulatedAudio = tempBuffer
        }
    }
    
    /**
     * 将字节数组转换为短整型数组（16位PCM）
     */
    private fun convertAudioToShorts(audioData: ByteArray): CPointer<ShortVar> {
        val halfLength = audioData.size / 2
        val samples = nativeHeap.allocArray<ShortVar>(halfLength)
        
        for (i in 0 until halfLength) {
            val lowByte = audioData[i * 2].toInt() and 0xFF
            val highByte = audioData[i * 2 + 1].toInt() and 0xFF
            samples[i] = ((highByte shl 8) or lowByte).toShort()
        }
        
        return samples
    }
    
    /**
     * 计算音频能量
     */
    private fun calculateEnergy(audio: ByteArray, length: Int): Double {
        if (length < 2) return 0.0
        
        var sum = 0.0
        for (i in 0 until length step 2) {
            if (i + 1 < length) {
                val sample = (audio[i].toInt() and 0xFF) or ((audio[i + 1].toInt() and 0xFF) shl 8)
                val value = if (sample and 0x8000 != 0) sample - 0x10000 else sample
                sum += value * value
            }
        }
        
        return sqrt(sum / (length / 2))
    }
    
    /**
     * 创建空结果
     */
    private fun createEmptyResult(): AudioPipeline.SpeechRecognition.RecognitionResult {
        return AudioPipeline.SpeechRecognition.RecognitionResult(
            success = true,
            text = "",
            isPartial = true,
            metrics = RecognitionMetrics(
                processingTimeMs = 0,
                confidenceScore = 0.0f,
                errorCode = 0,
                errorMessage = ""
            )
        )
    }
    
    /**
     * 创建错误结果
     */
    private fun createErrorResult(errorMessage: String): AudioPipeline.SpeechRecognition.RecognitionResult {
        return AudioPipeline.SpeechRecognition.RecognitionResult(
            success = false,
            text = "",
            isPartial = false,
            metrics = RecognitionMetrics(
                processingTimeMs = 0,
                confidenceScore = 0.0f,
                errorCode = 1,
                errorMessage = errorMessage
            )
        )
    }
    
    /**
     * 初始化语音识别器
     * @param modelPath 模型文件路径
     * @return 初始化是否成功
     */
    fun initialize(modelPath: String): Boolean {
        logger.info("初始化Vosk语音识别器，模型路径: $modelPath")
        
        try {
            // 加载Vosk模型
            voskModel = vosk_model_new(modelPath)
            if (voskModel == null) {
                logger.error("Vosk模型加载失败")
                return false
            }
            
            // 创建Vosk识别器
            voskRecognizer = vosk_recognizer_new(voskModel, sampleRate.toFloat())
            if (voskRecognizer == null) {
                logger.error("Vosk识别器创建失败")
                vosk_model_free(voskModel)
                voskModel = null
                return false
            }
            
            // 启用部分结果和关键词提取
            vosk_recognizer_set_words(voskRecognizer, 1)
            
            // 添加默认的短命令关键词
            registeredKeywords.addAll(shortCommandKeywords)
            updateRecognizerKeywords()
            
            isInitialized = true
            logger.info("Vosk语音识别器初始化成功，已加载${registeredKeywords.size}个默认关键词")
            return true
        } catch (e: Exception) {
            logger.error("Vosk语音识别器初始化异常: ${e.message}")
            cleanup()
            return false
        }
    }
    
    /**
     * 更新关键词
     * @param keywords 关键词列表，逗号分隔
     * @return 更新是否成功
     */
    fun updateKeywords(keywords: String): Boolean {
        if (!isInitialized || voskRecognizer == null) {
            logger.error("Vosk识别器未初始化，无法更新关键词")
            return false
        }
        
        try {
            // 清空并重新注册关键词
            registeredKeywords.clear()
            
            // 解析并添加新关键词
            keywords.split(",").forEach { keyword ->
                val trimmed = keyword.trim()
                if (trimmed.isNotBlank() && !registeredKeywords.contains(trimmed)) {
                    registeredKeywords.add(trimmed)
                }
            }
            
            // 添加默认的短命令关键词
            shortCommandKeywords.forEach { command ->
                if (!registeredKeywords.contains(command)) {
                    registeredKeywords.add(command)
                }
            }
            
            // 更新Vosk识别器的关键词
            updateRecognizerKeywords()
            
            logger.info("已更新关键词，共${registeredKeywords.size}个：${registeredKeywords.joinToString(", ")}")
            return true
        } catch (e: Exception) {
            logger.error("更新关键词异常: ${e.message}")
            return false
        }
    }
    
    /**
     * 更新Vosk识别器的关键词配置
     */
    private fun updateRecognizerKeywords() {
        if (voskRecognizer == null || registeredKeywords.isEmpty()) {
            return
        }
        
        try {
            // 构建关键词JSON数组 
            val keywordsJson = buildString {
                append("[")
                registeredKeywords.forEachIndexed { index, keyword ->
                    if (index > 0) append(", ")
                    append("\"").append(keyword).append("\"")
                }
                append("]")
            }
            
            // 设置Vosk语法
            vosk_recognizer_set_grm(voskRecognizer, keywordsJson)
            logger.debug("已更新Vosk关键词语法: $keywordsJson")
        } catch (e: Exception) {
            logger.error("设置Vosk关键词失败: ${e.message}")
        }
    }
    
    /**
     * 重置识别器状态
     */
    fun reset() {
        if (isInitialized && voskRecognizer != null) {
            vosk_recognizer_reset(voskRecognizer)
            accumulatedAudio = ByteArray(0)
            lastRecognitionTime = 0L
            logger.info("已重置Vosk识别器状态")
        }
    }
    
    /**
     * 释放资源
     */
    fun release() {
        logger.info("释放Vosk识别器资源")
        cleanup()
        isInitialized = false
    }
    
    /**
     * 清理资源
     */
    private fun cleanup() {
        if (voskRecognizer != null) {
            vosk_recognizer_free(voskRecognizer)
            voskRecognizer = null
        }
        
        if (voskModel != null) {
            vosk_model_free(voskModel)
            voskModel = null
        }
        
        accumulatedAudio = ByteArray(0)
    }
} 