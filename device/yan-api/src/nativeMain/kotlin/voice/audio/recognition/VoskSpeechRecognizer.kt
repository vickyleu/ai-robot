package voice.audio.recognition

import com.airobot.device.yanapi.voice.util.AudioBufferPool
import com.airobot.voskinterop.VOSK_EP_ANSWER_SHORT
import com.airobot.voskinterop.VOSK_EP_ANSWER_VERY_LONG
import com.airobot.voskinterop.VoskModel
import com.airobot.voskinterop.VoskRecognizer
import com.airobot.voskinterop.vosk_model_free
import com.airobot.voskinterop.vosk_model_new
import com.airobot.voskinterop.vosk_recognizer_accept_waveform_s
import com.airobot.voskinterop.vosk_recognizer_final_result
import com.airobot.voskinterop.vosk_recognizer_free
import com.airobot.voskinterop.vosk_recognizer_new
import com.airobot.voskinterop.vosk_recognizer_partial_result
import com.airobot.voskinterop.vosk_recognizer_reset
import com.airobot.voskinterop.vosk_recognizer_result
import com.airobot.voskinterop.vosk_recognizer_set_endpointer_delays
import com.airobot.voskinterop.vosk_recognizer_set_endpointer_mode
import com.airobot.voskinterop.vosk_recognizer_set_words
import com.airobot.voskinterop.vosk_recognizer_set_grm
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.toKString
import kotlinx.cinterop.set
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import voice.api.SpeechRecognizerApi
import voice.util.LogManager
import kotlin.time.ExperimentalTime
import kotlin.math.sqrt
import platform.posix.*
import kotlinx.cinterop.*
import kotlinx.datetime.Clock.System

// 用于解析JSON
import kotlinx.serialization.json.*
import voice.util.AudioDefaults
import voice.util.PerformanceMonitorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Vosk语音识别实现
 * 负责对音频进行语音识别，包括关键词检测和完整语音识别
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
class VoskSpeechRecognizer : SpeechRecognizerApi {
    private val logger = LogManager.getLogger("VoskSpeechRecognizer")
    // 添加性能监控
    private val performanceMonitor = PerformanceMonitorManager.getMonitor("VoskRecognizer")
    
    // 暂时保留但不使用
    private val voskProcessingDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val voskProcessingScope = CoroutineScope(voskProcessingDispatcher)
    
    // Vosk模型和识别器
    private var voskModel: CPointer<VoskModel>? = null
    private var voskRecognizer: CPointer<VoskRecognizer>? = null
    
    // 配置
    private val sampleRate = AudioDefaults.INPUT_DEVICE_SAMPLE_RATE
    
    // 内部状态
    internal var isInitialized = false
    private var recognitionCount = 0
    private var accumulatedAudio = ByteArray(0)
    private var lastRecognitionTime = 0L

    private val minAudioBufferSize = 320 // 20ms数据量
    private val maxAudioBufferSize = 6400 // 增大到400ms数据量，原来是3200
    private val recognitionCooldownMs = 150L // 增加到150ms，原来是100ms


    private var isStreamProcessing = false
    private var silenceFrames = 0
    private val maxSilenceFrames = 10 // 100ms静音触发识别

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
    private val recognizerLock = ReentrantLock()

    /**
     * 处理音频数据
     * @param audio 音频数据
     * @param length 数据长度
     * @param timestamp 时间戳
     * @return 识别结果
     */
    override fun recognize(audio: ByteArray, length: Int, timestamp: Long): SpeechRecognizerApi.RecognitionResult {
        recognizerLock.lock()
        try {
            if (!isInitialized || voskRecognizer == null) return createErrorResult("识别器未初始化", timestamp)
            recognitionCount++
            // 移除跳过逻辑，处理所有音频数据
            // if (recognitionCount % 2 != 0) return createEmptyResult(timestamp)

            val energy = calculateEnergy(audio, length)
            val hasVoice = energy > 50  // 从150降低到50，适应低能量语音
            if (!hasVoice) silenceFrames++ else silenceFrames = 0

            // 添加能量检测日志
            if (recognitionCount % 200 == 0) { // 从50改为200，减少日志频率
                logger.debug("音频能量检测: energy=$energy, hasVoice=$hasVoice, 数据长度=$length")
            }

            // 累积数据
            if (hasVoice || accumulatedAudio.isNotEmpty()) {
                accumulateAudio(audio, length)
                if (accumulatedAudio.size > maxAudioBufferSize) {
                    silenceFrames = 0
                    val res = processAccumulatedAudio(timestamp, LogManager.getCurrentTimeMillis(), forceFinal = true)
                    accumulatedAudio = ByteArray(0)
                    return res
                }
            }

            val shouldCut = accumulatedAudio.size >= maxAudioBufferSize ||
                    silenceFrames >= maxSilenceFrames ||
                    (hasVoice && accumulatedAudio.size >= minAudioBufferSize)
            if (!shouldCut) return createEmptyResult(timestamp)

            return processAccumulatedAudio(timestamp, LogManager.getCurrentTimeMillis(), forceFinal = true)
        }finally {
            recognizerLock.unlock()
        }
    }
    /**
     * 处理累积的音频数据
     */
    private fun processAccumulatedAudio(
        timestamp: Long,
        startTime: Long,
        forceFinal: Boolean = false
    ): SpeechRecognizerApi.RecognitionResult {
        // 记录处理前的缓冲区状态
        val bufferSizeBefore = accumulatedAudio.size
        logger.debug("开始处理累积音频: 缓冲区大小=${bufferSizeBefore}字节, forceFinal=$forceFinal")
        
        // 创建缓冲区副本用于处理，避免在处理过程中被修改
        val audioToProcess = accumulatedAudio.copyOf()
        
        // 立即清空累积缓冲区，避免重复处理
        accumulatedAudio = ByteArray(0)
        silenceFrames = 0
        
        val voskResult = processAudioWithVosk(audioToProcess, forceFinal)
        val cost = System.now().toEpochMilliseconds() - startTime
        lastRecognitionTime = System.now().toEpochMilliseconds()
        
        logger.debug("音频处理完成: 处理了${audioToProcess.size}字节, 耗时${cost}ms, 结果类型=${voskResult.resultType}")

        return when (voskResult.resultType) {
            ResultType.FINAL -> createFinalResult(voskResult, timestamp, cost)
            ResultType.PARTIAL -> createPartialResult(voskResult, timestamp, cost)
            else -> createEmptyResult(timestamp)
        }
    }

    /**
     * 创建最终结果
     */
    private fun createFinalResult(
        voskResult: VoskResult,
        timestamp: Long,
        processingTime: Long
    ): SpeechRecognizerApi.RecognitionResult {
        // 记录识别结果
        if (voskResult.text.isNotBlank()) {
            logger.info("识别结果: \"${voskResult.text}\"")
            if (voskResult.foundKeywords.isNotEmpty()) {
                logger.info("检测到关键词: ${voskResult.foundKeywords.joinToString(", ")}")
            }
        }

        return SpeechRecognizerApi.RecognitionResult(
            success = true,
            text = voskResult.text,
            isPartial = false,
            confidence = voskResult.confidence,
            metrics = SpeechRecognizerApi.RecognitionMetrics(
                processingTimeMs = processingTime,
                confidenceScore = voskResult.confidence,
                errorCode = 0,
                errorMessage = "",
                timestamp = timestamp
            )
        )
    }

    /**
     * 创建部分结果
     */
    private fun createPartialResult(
        voskResult: VoskResult,
        timestamp: Long,
        processingTime: Long
    ): SpeechRecognizerApi.RecognitionResult {
        return SpeechRecognizerApi.RecognitionResult(
            success = true,
            text = voskResult.partialText,
            isPartial = true,
            confidence = voskResult.confidence,
            metrics = SpeechRecognizerApi.RecognitionMetrics(
                processingTimeMs = processingTime,
                confidenceScore = voskResult.confidence,
                errorCode = 0,
                errorMessage = "",
                timestamp = timestamp
            )
        )
    }


    /**
     * 使用Vosk处理音频数据
     */
    private fun processAudioWithVosk(audioData: ByteArray, forceFinal: Boolean = false): VoskResult {
        logger.debug("Vosk处理音频: 数据长度=${audioData.size}字节, forceFinal=$forceFinal")

        // 检查音频数据质量
        if (audioData.size < 640) { // 至少40ms的音频
            logger.warn("音频数据太短，跳过处理: ${audioData.size}字节")
            return VoskResult("", "", 0f, emptyList(), emptyList(), ResultType.EMPTY)
        }

        // 预处理：增强音频信号
        val enhancedAudio = enhanceAudioSignal(audioData)

        val samples = convertAudioToShorts(enhancedAudio)
        val sampleCount = enhancedAudio.size / 2

        // 检查样本质量
        if (!validateAudioQuality(samples, sampleCount)) {
            logger.warn("音频质量不足，可能无法识别")
        }

        // 继续原有处理...
        val r = vosk_recognizer_accept_waveform_s(voskRecognizer, samples, sampleCount)
        nativeHeap.free(samples.rawValue)
        
        logger.debug("Vosk accept_waveform 返回值: $r")
        
        // 只有 r!=0 或 外部 forceFinal 时，才真正调用 result()+reset()
        if (r != 0 || forceFinal) {
            val json = vosk_recognizer_result(voskRecognizer)?.toKString() ?: "{}"
            logger.debug("Vosk最终结果JSON: $json")
            val result = parseFinalResult(json)
            vosk_recognizer_reset(voskRecognizer)
            return result
        } else {
            val partial = vosk_recognizer_partial_result(voskRecognizer)?.toKString() ?: "{}"
            logger.debug("Vosk部分结果JSON: $partial")
            val partialResult = parsePartialResult(partial)
            
            // 如果部分结果有文本内容，并且是强制最终处理，则获取最终结果
            if (forceFinal && partialResult.partialText.isNotBlank()) {
                logger.debug("部分结果有内容且强制最终处理，获取最终结果")
                val json = vosk_recognizer_result(voskRecognizer)?.toKString() ?: "{}"
                logger.debug("强制获取的最终结果JSON: $json")
                val finalResult = parseFinalResult(json)
                vosk_recognizer_reset(voskRecognizer)
                return finalResult
            }
            
            return partialResult
        }
    }
    // 新增：音频信号增强函数
    private fun enhanceAudioSignal(audioData: ByteArray): ByteArray {
        return audioData  // 直接返回原始数据，不做任何处理
        val enhanced = ByteArray(audioData.size)

        // 应用简单的增益和去直流偏移
        var sum = 0L
        for (i in audioData.indices step 2) {
            if (i + 1 < audioData.size) {
                val lowByte = audioData[i].toInt() and 0xFF
                val highByte = audioData[i + 1].toInt() and 0xFF
                val sample = lowByte or (highByte shl 8)
                val signedSample = if (sample and 0x8000 != 0) sample - 0x10000 else sample
                sum += signedSample
            }
        }

        val dcOffset = (sum / (audioData.size / 2)).toInt()
        logger.debug("检测到直流偏移: $dcOffset")

        // 去除直流偏移并应用适度增益
        for (i in audioData.indices step 2) {
            if (i + 1 < audioData.size) {
                val lowByte = audioData[i].toInt() and 0xFF
                val highByte = audioData[i + 1].toInt() and 0xFF
                val sample = lowByte or (highByte shl 8)
                var signedSample = if (sample and 0x8000 != 0) sample - 0x10000 else sample

                // 去直流偏移
                signedSample -= dcOffset

                // 应用适度增益（2倍），但避免削波
                signedSample = (signedSample * 2).coerceIn(-32767, 32767)

                // 转回无符号16位
                val unsignedSample = if (signedSample < 0) signedSample + 0x10000 else signedSample

                enhanced[i] = (unsignedSample and 0xFF).toByte()
                enhanced[i + 1] = ((unsignedSample shr 8) and 0xFF).toByte()
            }
        }

        return enhanced
    }

    // 新增：音频质量验证函数
    private fun validateAudioQuality(samples: CPointer<ShortVar>, sampleCount: Int): Boolean {
        if (sampleCount < 100) return false

        var maxAbs = 0
        var dynamicRange = 0
        var minVal = Int.MAX_VALUE
        var maxVal = Int.MIN_VALUE

        for (i in 0 until sampleCount) {
            val sample = samples[i].toInt()
            val abs = kotlin.math.abs(sample)
            if (abs > maxAbs) maxAbs = abs
            if (sample < minVal) minVal = sample
            if (sample > maxVal) maxVal = sample
        }

        dynamicRange = maxVal - minVal

        val hasEnoughAmplitude = maxAbs > 200  // 最小振幅要求
        val hasEnoughDynamicRange = dynamicRange > 100  // 最小动态范围要求

        if (!hasEnoughAmplitude) {
            logger.warn("音频振幅不足: maxAbs=$maxAbs (需要>200)")
        }
        if (!hasEnoughDynamicRange) {
            logger.warn("音频动态范围不足: range=$dynamicRange (需要>100)")
        }

        return hasEnoughAmplitude && hasEnoughDynamicRange
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

        try {
            // 确保不超过最大缓冲区大小
            val actualLength = minOf(length, newData.size)
            
            // 如果累积的数据会超过最大缓冲区，先清理旧数据
            if (accumulatedAudio.size + actualLength > maxAudioBufferSize) {
                // 保留最新的一半数据，为新数据腾出空间
                val keepSize = maxAudioBufferSize / 2
                accumulatedAudio = accumulatedAudio.takeLast(keepSize).toByteArray()
                logger.debug("缓冲区即将溢出，清理到${keepSize}字节")
            }
            
            // 创建新的缓冲区
            val newBuffer = ByteArray(accumulatedAudio.size + actualLength)
            
            // 复制旧数据
            if (accumulatedAudio.isNotEmpty()) {
                accumulatedAudio.copyInto(newBuffer, 0, 0, accumulatedAudio.size)
            }
            
            // 复制新数据 - 确保只复制有效长度
            newData.copyInto(newBuffer, accumulatedAudio.size, 0, actualLength)

            // 更新引用 - 这是关键，确保引用被正确更新
            accumulatedAudio = newBuffer
            
            // 定期记录缓冲区大小变化
            if (recognitionCount % 200 == 0) {
                logger.debug("音频累积: 新增${actualLength}字节, 总计${accumulatedAudio.size}字节")
            }
        } catch (e: Exception) {
            val errorMsg = "音频累积异常: ${e.message ?: "未知错误"}"
            logger.error(errorMsg)
            
            // 发生异常时，重置缓冲区避免数据损坏
            accumulatedAudio = ByteArray(0)
            logger.warn("由于异常重置音频缓冲区")
        }
    }
    
    /**
     * 将字节数组转换为短整型数组（16位PCM）
     */
    private fun convertAudioToShorts(audioData: ByteArray): CPointer<ShortVar> {
        val halfLength = audioData.size / 2
        val samples = nativeHeap.allocArray<ShortVar>(halfLength)

        for (i in 0 until halfLength) {
            // 修复字节序问题 - 确保正确的小端序转换
            val lowByte = audioData[i * 2].toInt() and 0xFF
            val highByte = audioData[i * 2 + 1].toInt() and 0xFF

            // 正确的16位PCM转换（小端序）
            val sample = (lowByte or (highByte shl 8)).toShort()
            samples[i] = sample
        }

        // 添加详细调试
        if (halfLength > 0) {
            // 计算统计信息
            var maxAbs = 0
            var minVal = Short.MAX_VALUE.toInt()
            var maxVal = Short.MIN_VALUE.toInt()
            var nonZeroCount = 0
            var sum = 0.0

            for (i in 0 until halfLength) {
                val sample = samples[i].toInt()
                val abs = kotlin.math.abs(sample)
                if (abs > maxAbs) maxAbs = abs
                if (sample < minVal) minVal = sample
                if (sample > maxVal) maxVal = sample
                if (sample != 0) nonZeroCount++
                sum += sample * sample
            }

            val rms = kotlin.math.sqrt(sum / halfLength)

            logger.debug("""
            音频统计详情:
            - 样本数: $halfLength
            - 振幅范围: $minVal 到 $maxVal
            - 最大绝对值: $maxAbs
            - RMS能量: ${rms.toInt()}
            - 非零样本: $nonZeroCount/${halfLength} (${(nonZeroCount*100/halfLength)}%)
            - 前5个样本: ${(0 until minOf(5, halfLength)).map { samples[it] }.joinToString(", ")}
        """.trimIndent())

            // 检查音频质量
            if (maxAbs < 500) {
                logger.warn("音频振幅过低，可能影响识别效果: maxAbs=$maxAbs")
            }
            if (rms < 100) {
                logger.warn("音频RMS能量过低: rms=${rms.toInt()}")
            }
        }

        return samples
    }

    /**
     * 计算音频能量
     */
    private fun calculateEnergy(audio: ByteArray, length: Int): Double {
        if (length < 2) return 0.0
        
        // 直接计算RMS能量，不再调用WebRTC（由KeywordDetector负责VAD）
        var sum = 0.0
        for (i in 0 until length step 2) {
            if (i + 1 < length) {
                val sample = (audio[i].toInt() and 0xFF) or ((audio[i + 1].toInt() and 0xFF) shl 8)
                val value = if (sample and 0x8000 != 0) sample - 0x10000 else sample
                sum += value * value
            }
        }
        
        // 计算RMS值，用于质量检测而非VAD（VAD由WebRTC在KeywordDetector中完成）
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
            metrics = SpeechRecognizerApi.RecognitionMetrics(
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
    private fun createErrorResult(message: String, timestamp: Long): SpeechRecognizerApi.RecognitionResult {
        return SpeechRecognizerApi.RecognitionResult(
            success = false,
            text = "",
            isPartial = false,
            confidence = 0.0f,
            errorCode = 1,
            errorMessage = message,
            metrics = SpeechRecognizerApi.RecognitionMetrics(
                processingTimeMs = 0,
                confidenceScore = 0.0f,
                errorCode = 1,
                errorMessage = message,
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
            // 检查模型路径是否存在
            logger.info("检查模型路径是否存在: $modelPath")
            
            // 加载Vosk模型
            logger.info("正在加载Vosk模型...")
            voskModel = vosk_model_new(modelPath)
            if (voskModel == null) {
                logger.error("Vosk模型加载失败，路径: $modelPath")
                return false
            }
            logger.info("✅ Vosk模型加载成功")
            
            // 自动补全词表
            ensureVoskVocabulary(modelPath, registeredKeywords + shortCommandKeywords)
            
            // 创建Vosk识别器
            logger.info("正在创建Vosk识别器，采样率: ${sampleRate}Hz")
            voskRecognizer = vosk_recognizer_new(voskModel, sampleRate.toFloat())
            if (voskRecognizer == null) {
                logger.error("Vosk识别器创建失败")
                vosk_model_free(voskModel)
                voskModel = null
                return false
            }
            logger.info("✅ Vosk识别器创建成功")

            logger.info("配置Vosk识别器参数...")
            vosk_recognizer_set_endpointer_mode(voskRecognizer, VOSK_EP_ANSWER_SHORT)
            vosk_recognizer_set_endpointer_delays(voskRecognizer,
                /*t_start_max=*/0.5f,    // 从1.0f进一步降低到0.5f，更快开始识别
                /*t_end=*/0.1f,          // 从0.3f降低到0.1f，更快结束
                /*t_max=*/1.5f           // 从3.0f降低到1.5f，避免过长等待
            )

            // 启用部分结果和关键词提取
            vosk_recognizer_set_words(voskRecognizer, 1)
            logger.info("✅ Vosk识别器参数配置完成")
            
            // 添加默认的短命令关键词
            registeredKeywords.addAll(shortCommandKeywords)
            updateRecognizerKeywords()
            
            isInitialized = true
            logger.info("✅ Vosk语音识别器初始化成功，已加载${registeredKeywords.size}个默认关键词")
            logger.info("关键词列表: ${registeredKeywords.joinToString(", ")}")
            
            // 运行测试验证Vosk是否正常工作
            testVoskRecognizer()
            
            return true
        } catch (e: Exception) {
            logger.error("Vosk语音识别器初始化异常: ${e.message}")
            e.printStackTrace()
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
            // 暂时禁用关键词语法，使用完整语言模型
            logger.info("暂时禁用关键词语法，使用完整语言模型")
            // 注释掉关键词设置，让Vosk使用完整词汇表
            /*
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
            */
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

    /**
     * 测试Vosk识别器是否正常工作
     * 使用简单的测试音频数据
     */
    fun testVoskRecognizer(): Boolean {
        if (!isInitialized || voskRecognizer == null) {
            logger.error("Vosk识别器未初始化，无法测试")
            return false
        }
        
        try {
            logger.info("开始测试Vosk识别器...")
            
            // 创建一个简单的测试音频（静音）
            val testAudioSize = 3200 // 200ms @ 16kHz
            val testAudio = ByteArray(testAudioSize) { 0 }
            
            // 测试1：静音数据
            logger.info("测试1：静音数据")
            val result1 = processAudioWithVosk(testAudio, forceFinal = true)
            logger.info("静音测试结果: ${result1.text}")
            
            // 测试2：随机噪音数据
            logger.info("测试2：随机噪音数据")
            val noiseAudio = ByteArray(testAudioSize) { (kotlin.random.Random.nextInt(-1000, 1000) and 0xFF).toByte() }
            val result2 = processAudioWithVosk(noiseAudio, forceFinal = true)
            logger.info("噪音测试结果: ${result2.text}")
            
            // 测试3：检查模型是否支持中文
            logger.info("测试3：检查关键词设置")
            logger.info("当前注册的关键词: ${registeredKeywords.joinToString(", ")}")
            
            logger.info("Vosk识别器测试完成")
            return true
        } catch (e: Exception) {
            logger.error("Vosk识别器测试失败: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
}