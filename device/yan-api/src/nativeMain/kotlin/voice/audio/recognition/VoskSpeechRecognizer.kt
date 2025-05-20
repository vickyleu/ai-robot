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
import voice.api.SpeechRecognizerApi
import voice.audio.RecognitionMetrics
import voice.util.LogManager
import kotlin.time.ExperimentalTime
import kotlin.math.sqrt
import platform.posix.*
import kotlinx.cinterop.*

// 用于解析JSON
import kotlinx.serialization.json.*

/**
 * Vosk语音识别实现
 * 负责对音频进行语音识别，包括关键词检测和完整语音识别
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
class VoskSpeechRecognizer : SpeechRecognizerApi {
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
        "音量大", "音量小", "音量增大", "音量减小", "放大声音", "调小声音",
        "关闭", "打开", "启动", "退出"
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
    override fun getCurrentKeywords(): List<String> {
        return registeredKeywords.toList()
    }
    
    /**
     * 处理音频数据
     * @param audio 音频数据
     * @param length 数据长度
     * @param timestamp 时间戳
     * @return 识别结果
     */
    override fun recognize(audio: ByteArray, length: Int, timestamp: Long): SpeechRecognizerApi.RecognitionResult {
        if (!isInitialized || voskRecognizer == null) {
            logger.error("Vosk识别器未初始化或已失效")
            return createErrorResult("识别器未初始化", timestamp) 
        }
        
        recognitionCount++
        val startTime = LogManager.getCurrentTimeMillis()
        
        logger.debug("音频数据接收: ${length}字节, 第${recognitionCount}次调用")
        
        try {
            // 只在能量高时处理音频（避免处理静音）
            val energy = calculateEnergy(audio, length)
            if (energy < 100 && recognitionCount % 10 != 0) {
                return createEmptyResult(timestamp)
            }
            
            // 累积音频数据
            accumulateAudio(audio, length)
            
            // 检查是否应该执行识别（避免过于频繁的调用）
            val timeElapsed = startTime - lastRecognitionTime
            val bufferSizeAdequate = accumulatedAudio.size >= minAudioBufferSize
            
            if (!bufferSizeAdequate || timeElapsed < recognitionCooldownMs) {
                return createEmptyResult(timestamp)
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
                    SpeechRecognizerApi.RecognitionResult(
                        success = true,
                        text = voskResult.text,
                        isPartial = false,
                        confidence = voskResult.confidence,
                        metrics = RecognitionMetrics(
                            processingTimeMs = endTime - startTime,
                            confidenceScore = voskResult.confidence,
                            errorCode = 0,
                            errorMessage = "",
                            timestamp = timestamp
                        )
                    )
                }
                
                ResultType.PARTIAL -> {
                    // 部分结果不清空音频缓冲
                    SpeechRecognizerApi.RecognitionResult(
                        success = true,
                        text = voskResult.partialText,
                        isPartial = true,
                        confidence = voskResult.confidence,
                        metrics = RecognitionMetrics(
                            processingTimeMs = endTime - startTime,
                            confidenceScore = voskResult.confidence,
                            errorCode = 0,
                            errorMessage = "",
                            timestamp = timestamp
                        )
                    )
                }
                
                ResultType.EMPTY -> {
                    createEmptyResult(timestamp)
                }
                
                ResultType.ERROR -> {
                    createErrorResult("处理过程中出错", timestamp)
                }
            }
        } catch (e: Exception) {
            logger.error("识别处理异常: ${e.message}")
            return createErrorResult("识别处理异常: ${e.message}", timestamp)
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
            nativeHeap.free(samples.rawValue)
            
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
        } else {
            // 未提供置信度时视为 0，避免误判
            return 0.0f
        }
        
        // 2. 检查是否有替代结果的置信度数组
        val confidenceArray = mutableListOf<Float>()
        json["alternatives"]?.jsonArray?.forEach { alt ->
            alt.jsonObject["confidence"]?.jsonPrimitive?.floatOrNull?.let {
                confidenceArray.add(it)
            }
        }
        
        if (confidenceArray.isNotEmpty()) {
            return confidenceArray.average().toFloat()
        }
        
        // 3. 如果仍无法得到置信度，返回极低值
        return 0.0f
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
    private fun createEmptyResult(timestamp: Long): SpeechRecognizerApi.RecognitionResult {
        return SpeechRecognizerApi.RecognitionResult(
            success = true,
            text = "",
            isPartial = true,
            confidence = 0.0f,
            metrics = RecognitionMetrics(
                processingTimeMs = 0,
                confidenceScore = 0.0f,
                errorCode = 0,
                errorMessage = "",
                timestamp = timestamp
            )
        )
    }
    
    /**
     * 创建错误结果
     */
    private fun createErrorResult(errorMessage: String, timestamp: Long): SpeechRecognizerApi.RecognitionResult {
        return SpeechRecognizerApi.RecognitionResult(
            success = false,
            text = "",
            isPartial = false,
            confidence = 0.0f,
            metrics = RecognitionMetrics(
                processingTimeMs = 0,
                confidenceScore = 0.0f,
                errorCode = 1,
                errorMessage = errorMessage,
                timestamp = timestamp
            )
        )
    }
    
    /**
     * 初始化语音识别器
     * @param modelPath 模型文件路径
     * @return 初始化是否成功
     */
    override fun initialize(modelPath: String): Boolean {
        logger.info("初始化Vosk语音识别器，模型路径: $modelPath")
        
        try {
            // 加载Vosk模型
            voskModel = vosk_model_new(modelPath)
            if (voskModel == null) {
                logger.error("Vosk模型加载失败")
                return false
            }
            
            // 自动补全词表
            ensureVoskVocabulary(modelPath, registeredKeywords + shortCommandKeywords)
            
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
    override fun updateKeywords(keywords: List<String>): Boolean {
        if (!isInitialized || voskRecognizer == null) {
            logger.error("Vosk识别器未初始化，无法更新关键词")
            return false
        }
        
        try {
            // 清空并重新注册关键词
            registeredKeywords.clear()
            
            // 解析并添加新关键词
            keywords.forEach { keyword ->
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
                    append("\"").append(keyword.replace("\"", "\\\"")).append("\"")
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
    override fun reset() {
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
    override fun release() {
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

    private fun ensureVoskVocabulary(modelPath: String, keywords: List<String>) {
        val vocabPath = "$modelPath/words.txt"
        val lines = mutableSetOf<String>()
        logger.info("检查Vosk词表文件: $vocabPath")
        logger.info("需要确保的关键词: ${keywords.joinToString(", ")}")
        
        // 首先检查词表文件是否存在
        val file = fopen(vocabPath, "r")
        if (file == null) {
            // 文件不存在，自动创建并写入所有关键词
            logger.info("词表文件不存在，尝试创建新文件...")
            val wfile = fopen(vocabPath, "w")
            if (wfile != null) {
                for (word in keywords.distinct()) {
                    fputs("$word 1.0\n", wfile)
                    lines.add("$word 1.0")
                    logger.info("已添加词: $word")
                }
                fclose(wfile)
                logger.info("已成功创建词表文件并写入${keywords.size}个关键词")
            } else {
                logger.error("""
                    无法创建Vosk词表文件: $vocabPath 
                    请手动执行以下命令:
                      sudo touch $vocabPath
                      sudo chown pi:pi $vocabPath
                      sudo chmod 666 $vocabPath
                """.trimIndent())
            }
            return
        }
        
        // 读取现有词表文件内容
        memScoped {
            val buffer = allocArray<ByteVar>(512)
            while (fgets(buffer, 512, file) != null) {
                val line = buffer.toKString().trim()
                if (line.isNotEmpty()) lines.add(line)
            }
        }
        fclose(file)
        logger.info("已读取词表文件，包含${lines.size}个词条")

        // 检查并添加缺失的关键词
        var changed = false
        val missingWords = mutableListOf<String>()
        for (word in keywords) {
            if (lines.none { it.split(" ")[0] == word }) {
                lines.add("$word 1.0")
                missingWords.add(word)
                changed = true
                logger.info("发现缺失词: $word，将添加到词表")
            }
        }
        
        // 如果有新增词，更新词表文件
        if (changed) {
            logger.info("需要更新词表文件，添加${missingWords.size}个缺失词...")
            val wfile = fopen(vocabPath, "w")
            if (wfile != null) {
                for (line in lines) {
                    fputs(line + "\n", wfile)
                }
                fclose(wfile)
                logger.info("词表文件更新成功！已添加: ${missingWords.joinToString(", ")}")
                
                // 检查关键词"音量大"和"音量小"是否被成功添加
                val criticalWords = listOf("音量大", "音量小")
                val addedCriticalWords = criticalWords.filter { word -> lines.any { it.startsWith(word) } }
                if (addedCriticalWords.size == criticalWords.size) {
                    logger.info("关键词「音量大」和「音量小」已成功添加到词表中")
                } else {
                    val missing = criticalWords.filter { !addedCriticalWords.contains(it) }
                    logger.error("关键词添加不完整，仍缺少: ${missing.joinToString(", ")}")
                    
                    // 再次尝试添加缺失的关键词
                    val retryFile = fopen(vocabPath, "a")
                    if (retryFile != null) {
                        for (word in missing) {
                            fputs("$word 1.0\n", retryFile)
                            logger.info("重试添加词: $word")
                        }
                        fclose(retryFile)
                        logger.info("已重试添加缺失的关键词")
                    }
                }
            } else {
                logger.error("无法打开词表文件进行写入，请检查文件权限: $vocabPath")
            }
        } else {
            logger.info("词表已包含所有关键词，无需更新")
            
            // 即使不需要更新，也检查关键词是否存在
            val criticalWords = listOf("音量大", "音量小")
            for (word in criticalWords) {
                if (lines.any { it.startsWith(word) }) {
                    logger.info("词表中已包含关键词: $word")
                } else {
                    logger.warn("词表中缺少关键词: $word，但未能添加")
                }
            }
        }
    }
} 