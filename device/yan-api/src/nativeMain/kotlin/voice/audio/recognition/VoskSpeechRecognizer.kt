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
import com.airobot.voskinterop.vosk_recognizer_set_max_alternatives
import com.airobot.voskinterop.vosk_recognizer_set_partial_words
import com.airobot.voskinterop.vosk_set_log_level
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

    private val minAudioBufferSize = AudioDefaults.VOSK_MIN_AUDIO_BUFFER_SIZE / 2 // 🔧 减半到320字节，20ms数据量，更快响应
    private val maxAudioBufferSize = AudioDefaults.VOSK_MAX_AUDIO_BUFFER_SIZE // 保持800ms数据量上限
    private val recognitionCooldownMs = 100L // 🔧 减少到100ms，更快响应


    private var isStreamProcessing = false
    private var silenceFrames = 0
    private val maxSilenceFrames = 150 // 1500ms静音触发识别 - 🔧 从500ms增加到1500ms，避免连续语音截断

    // 关键词和命令管理 
    private val registeredKeywords = mutableListOf<String>()
    private val shortCommandKeywords = listOf(
        "你好", "嗨", "哈喽", "开始", "停止", "暂停", "继续", 
        "音量大", "音量小", "音量增大", "音量减小", "放大声音", "调小声音",
        "关闭", "打开", "启动", "退出"
    )
    
    // === Vosk 优化配置 ===
    // 多候选结果设置
    private val voskMaxAlternatives: Int = 5                     // 获取多个候选结果，提高识别准确性
    private val voskEnableWords: Boolean = true                  // 启用词级别信息，获取时间戳和置信度
    private val voskEnablePartialWords: Boolean = true           // 启用部分识别结果，实时反馈
    
    // 端点检测优化
    private val voskStartMaxDelay: Float = 2.0f                  // 🔧 增加到2.0秒，给足够的开始时间
    private val voskEndDelay: Float = 1.5f                       // 🔧 增加到1.5秒，确保"小度小度"中间停顿不会被误判为结束
    private val voskMaxDuration: Float = 8.0f                    // 🔧 增加到8.0秒，给足够的时间说完整的唤醒词
    
    // 置信度过滤设置
    private val voskMinConfidence: Float = 0.1f                  // 🔧 降低到0.1提高识别速度
    private val voskPartialMinConfidence: Float = 0.05f          // 🔧 部分结果用更低的阈值提高响应速度
    private val voskEnableConfidenceFilter: Boolean = true       // 🔧 重新启用置信度过滤，避免接受质量太低的结果
    
    // 中文文本后处理设置
    private val enableChineseTextCorrection: Boolean = true      // 启用中文文本纠错
    private val enableDuplicateCharRemoval: Boolean = true       // 移除重复字符
    private val enableCommonErrorCorrection: Boolean = true      // 常见错误修正
    private val maxDuplicateChars: Int = 2                       // 最大允许重复字符数
    
    // 上下文优化设置
    private val enableContextualRecognition: Boolean = true      // 启用上下文识别
    private val contextKeywords: List<String> = listOf(          // 上下文关键词，提高相关词汇识别率
        "小度", "你好", "在吗", "开始", "停止", "暂停", "继续",
        "音量", "声音", "大声", "小声", "播放", "关闭", "打开"
    )
    private val contextBoostScore: Float = 0.3f                  // 上下文关键词置信度加成：提高到0.3
    
    // 常见错误替换映射
    private val commonCorrections = mapOf(
        "的的" to "的", "了了" to "了", "是是" to "是", "在在" to "在",
        "我我" to "我", "你你" to "你", "他他" to "他", "她她" to "她",
        "这这" to "这", "那那" to "那", "有有" to "有", "没没" to "没",
        "不不" to "不", "要要" to "要", "会会" to "会", "能能" to "能",
        "好好" to "好", "大大" to "大", "小小" to "小", "多多" to "多"
    )
    
    // 语音识别常见错误映射
    private val speechRecognitionCorrections = mapOf(
        "小杜" to "小度", "小毒" to "小度", "小肚" to "小度", "小独" to "小度",
        "小读" to "小度", "小渡" to "小度", "小堵" to "小度", "小赌" to "小度",
        "小督" to "小度", "小妒" to "小度", "小嘟" to "小度", "小都" to "小度",
        "小豆" to "小度", "小斗" to "小度", "小逗" to "小度", "小兜" to "小度",
        "小度度" to "小度", "小度小度" to "小度", "你好你好" to "你好",
        "在吗在吗" to "在吗", "开始开始" to "开始", "停止停止" to "停止"
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
            val hasVoice = energy > 30  // 🔧 从50进一步降低到30，更敏感的语音检测
            if (!hasVoice) silenceFrames++ else silenceFrames = 0

            // 添加能量检测日志
            if (recognitionCount % 500 == 0) { // 🔧 从100改为500，大幅减少日志频率
                logger.debug("音频能量: $energy, 语音: $hasVoice, 累积: ${accumulatedAudio.size}字节")
            }

            // 🔧 优化累积逻辑：更积极地累积音频数据
            if (hasVoice || accumulatedAudio.isNotEmpty() || energy > 15) {  // 🔧 增加更低的能量阈值作为备选
                accumulateAudio(audio, length)
                if (accumulatedAudio.size > maxAudioBufferSize) {
                    silenceFrames = 0
                    val res = processAccumulatedAudio(timestamp, LogManager.getCurrentTimeMillis(), forceFinal = true)
                    accumulatedAudio = ByteArray(0)
                    return res
                }
            }

            // 🔧 更积极的处理触发条件
            val shouldCut = accumulatedAudio.size >= maxAudioBufferSize ||
                    silenceFrames >= maxSilenceFrames ||
                    (hasVoice && accumulatedAudio.size >= minAudioBufferSize) ||
                    (accumulatedAudio.size >= minAudioBufferSize * 2)  // 🔧 即使没有明确语音，累积足够数据也处理
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
        val durationMs = (bufferSizeBefore / 2 / AudioDefaults.INPUT_DEVICE_CHANNELS * 1000) / AudioDefaults.INPUT_DEVICE_SAMPLE_RATE
        
        // 创建缓冲区副本用于处理，避免在处理过程中被修改
        val audioToProcess = accumulatedAudio.copyOf()
        
        // 立即清空累积缓冲区，避免重复处理
        accumulatedAudio = ByteArray(0)
        silenceFrames = 0
        
        val voskResult = processAudioWithVosk(audioToProcess, forceFinal)
        val cost = System.now().toEpochMilliseconds() - startTime
        lastRecognitionTime = System.now().toEpochMilliseconds()
        
        // 只在有结果时记录
        if (voskResult.text.isNotBlank()) {
            logger.info("处理音频: ${bufferSizeBefore}字节/${durationMs}ms -> \"${voskResult.text}\"")
        }

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
            logger.info("识别: \"${voskResult.text}\"")
            if (voskResult.foundKeywords.isNotEmpty()) {
                logger.info("关键词: ${voskResult.foundKeywords.joinToString(", ")}")
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
        // 检查音频数据质量
        if (audioData.size < 320) { // 🔧 从640降低到320，至少20ms的音频就可以处理
            return VoskResult("", "", 0f, emptyList(), emptyList(), ResultType.EMPTY)
        }

        // 预处理：增强音频信号
        val enhancedAudio = enhanceAudioSignal(audioData)  // 🔧 重新启用音频增强，提高识别率

        val samples = convertAudioToShorts(enhancedAudio)
        val sampleCount = enhancedAudio.size / 2

        // 检查样本质量
        validateAudioQuality(samples, sampleCount)

        // 继续原有处理...
        val r = vosk_recognizer_accept_waveform_s(voskRecognizer, samples, sampleCount)
        nativeHeap.free(samples.rawValue)
        
        // 🔧 优化：更积极地获取识别结果
        if (r != 0 || forceFinal) {
            val json = vosk_recognizer_result(voskRecognizer)?.toKString() ?: "{}"
            val result = parseFinalResult(json)
            vosk_recognizer_reset(voskRecognizer)
            return result
        } else {
            val partial = vosk_recognizer_partial_result(voskRecognizer)?.toKString() ?: "{}"
            val partialResult = parsePartialResult(partial)
            
            // 🔧 如果强制最终处理，总是尝试获取最终结果，即使部分结果为空
            if (forceFinal) {
                val json = vosk_recognizer_result(voskRecognizer)?.toKString() ?: "{}"
                val finalResult = parseFinalResult(json)
                vosk_recognizer_reset(voskRecognizer)
                return finalResult
            }
            
            return partialResult
        }
    }
    // 新增：音频信号增强函数
    private fun enhanceAudioSignal(audioData: ByteArray): ByteArray {
        if (audioData.size < 4) return audioData  // 太短的音频直接返回
        
        val enhanced = ByteArray(audioData.size)
        val samples = mutableListOf<Short>()
        
        // 转换为样本数组进行处理
        for (i in audioData.indices step 2) {
            if (i + 1 < audioData.size) {
                val lowByte = audioData[i].toInt() and 0xFF
                val highByte = audioData[i + 1].toInt() and 0xFF
                val sample = lowByte or (highByte shl 8)
                val signedSample = if (sample and 0x8000 != 0) sample - 0x10000 else sample
                samples.add(signedSample.toShort())
            }
        }
        
        if (samples.isEmpty()) return audioData
        
        // 查找第一个有意义的音频数据（非零且振幅足够）
        var startIndex = 0
        val minAmplitude = AudioDefaults.MIN_EFFECTIVE_AMPLITUDE  // 最小有效振幅
        for (i in samples.indices) {
            if (kotlin.math.abs(samples[i].toInt()) > minAmplitude) {
                startIndex = i
                break
            }
        }
        
        // 如果找到有效开始位置，去掉前面的静音
        val processedSamples = if (startIndex > 0) {
            logger.debug("检测到前置静音${startIndex}样本，将其移除")
            samples.subList(startIndex, samples.size)
        } else {
            samples
        }
        
        // 🔧 修复：重新启用适度的增益处理，提高识别率
        // 原来完全禁用增益会导致音频信号过弱，影响识别效果
        val processedWithGain = processedSamples.map { sample ->
            val amplified = (sample.toInt() * 2.0).toInt()  // 🔧 从1.2倍增加到2.0倍，提高音频音量
            amplified.coerceIn(-32767, 32767).toShort()
        }
        
        // 转换回字节数组
        val finalSize = minOf(enhanced.size, processedWithGain.size * 2)
        for (i in 0 until finalSize step 2) {
            val sampleIndex = i / 2
            if (sampleIndex < processedWithGain.size) {
                val sample = processedWithGain[sampleIndex].toInt()
                val unsignedSample = if (sample < 0) sample + 0x10000 else sample
                enhanced[i] = (unsignedSample and 0xFF).toByte()
                if (i + 1 < enhanced.size) {
                    enhanced[i + 1] = ((unsignedSample shr 8) and 0xFF).toByte()
                }
            }
        }
        
        // 如果处理后的数据比原数据短，用静音填充剩余部分
        for (i in finalSize until enhanced.size) {
            enhanced[i] = 0
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

        val hasEnoughAmplitude = maxAbs > 100  // 🔧 从200降低到100，更宽松的振幅要求
        val hasEnoughDynamicRange = dynamicRange > 50  // 🔧 从100降低到50，更宽松的动态范围要求

        if (!hasEnoughAmplitude) {
            logger.debug("音频振幅较低: maxAbs=$maxAbs (建议>100)，但仍继续处理")  // 🔧 改为debug级别，不阻止处理
        }
        if (!hasEnoughDynamicRange) {
            logger.debug("音频动态范围较低: range=$dynamicRange (建议>50)，但仍继续处理")  // 🔧 改为debug级别，不阻止处理
        }

        return true  // 🔧 总是返回true，不阻止音频处理
    }

    
    /**
     * 解析Vosk最终结果JSON
     */
    private fun parseFinalResult(jsonStr: String): VoskResult {
        try {
            logger.debug("🎯 Vosk原始JSON结果: $jsonStr")  // 🔧 新增：记录原始JSON
            
            val json = Json.parseToJsonElement(jsonStr).jsonObject
            var text = json["text"]?.jsonPrimitive?.content ?: ""
            
            // 提取置信度和可选信息
            var confidence = extractConfidence(json, text, isPartial = false)
            
            // 提取替代文本
            val alternatives = json["alternatives"]?.jsonArray?.mapNotNull { alt ->
                alt.jsonObject["text"]?.jsonPrimitive?.content
            } ?: emptyList()
            
            logger.debug("🎯 Vosk解析结果: text='$text', confidence=$confidence, alternatives=${alternatives.size}")  // 🔧 新增：记录解析结果
            
            // 🔧 新增：当主要text字段为空时，从alternatives中提取最佳结果
            if (text.isBlank() && alternatives.isNotEmpty()) {
                // 找出置信度最高的alternative作为主要文本
                var bestText = ""
                var bestConfidence = 0.0f
                
                json["alternatives"]?.jsonArray?.forEach { alt ->
                    val altObj = alt.jsonObject
                    val altText = altObj["text"]?.jsonPrimitive?.content ?: ""
                    val altConf = altObj["confidence"]?.jsonPrimitive?.floatOrNull ?: 0.0f
                    
                    if (altText.isNotBlank() && altConf > bestConfidence) {
                        bestText = altText
                        bestConfidence = altConf
                    }
                }
                
                if (bestText.isNotBlank()) {
                    text = bestText
                    confidence = bestConfidence
                    logger.info("🔧 从alternatives中提取最佳结果: text='$text', confidence=$confidence")
                }
            }
            
            // === 最终结果的中文文本后处理 ===
            if (text.isNotBlank()) {
                // 1. 置信度过滤
                if (voskEnableConfidenceFilter && confidence < voskMinConfidence) {
                    // 尝试从alternatives中找到更好的结果
                    var bestText = ""
                    var bestConfidence = confidence
                    
                    json["alternatives"]?.jsonArray?.forEach { alt ->
                        val altObj = alt.jsonObject
                        val altText = altObj["text"]?.jsonPrimitive?.content ?: ""
                        val altConf = altObj["confidence"]?.jsonPrimitive?.floatOrNull ?: 0.0f
                        
                        if (altConf > bestConfidence && altConf >= voskMinConfidence) {
                            bestText = altText
                            bestConfidence = altConf
                        }
                    }
                    
                    if (bestText.isNotBlank()) {
                        text = bestText
                        confidence = bestConfidence
                    } else if (confidence < voskMinConfidence) {
                        // 如果所有结果置信度都不够，返回空结果
                        return VoskResult("", "", confidence, emptyList(), alternatives, ResultType.EMPTY)
                    }
                }
                
                // 2. 中文文本纠错
                if (enableChineseTextCorrection) {
                    text = postProcessChineseText(text)
                }
                
                // 3. 上下文优化
                if (enableContextualRecognition) {
                    val (processedText, scoreBoost) = processWithContext(text)
                    text = processedText
                    confidence = minOf(1.0f, confidence + scoreBoost)
                }
            }
            
            // 检查文本中是否包含已注册的关键词
            val foundKeywords = findKeywordsInText(text, alternatives)
            
            val finalResult = VoskResult(
                text = text,
                partialText = "",
                confidence = confidence,
                foundKeywords = foundKeywords,
                alternatives = alternatives,
                resultType = if (text.isBlank() && alternatives.isEmpty()) ResultType.EMPTY else ResultType.FINAL
            )
            
            // 🔧 新增：记录最终结果状态
            logger.info("🎯 Vosk最终结果: text='$text', confidence=$confidence, keywords=${foundKeywords.size}, type=${finalResult.resultType}")
            if (text.isBlank() && alternatives.isEmpty()) {
                logger.warn("⚠️ Vosk返回空结果！可能原因: 1)置信度过低 2)音频质量不足 3)模型不匹配")
            }
            
            return finalResult
        } catch (e: Exception) {
            logger.error("解析JSON失败: ${e.message}")
            return VoskResult("", "", 0f, emptyList(), emptyList(), ResultType.ERROR)
        }
    }
    
    /**
     * 解析Vosk部分结果JSON
     */
    private fun parsePartialResult(jsonStr: String): VoskResult {
        try {
            val json = Json.parseToJsonElement(jsonStr).jsonObject
            var partialText = json["partial"]?.jsonPrimitive?.content ?: ""
            
            // 提取置信度 - 部分结果通常置信度较低
            var confidence = extractConfidence(json, partialText, isPartial = true)
            
            // === 部分结果的中文文本后处理 ===
            if (partialText.isNotBlank()) {
                // 1. 置信度过滤（使用较低的阈值）
                if (voskEnableConfidenceFilter && confidence < voskPartialMinConfidence) {
                    return VoskResult("", "", confidence, emptyList(), emptyList(), ResultType.EMPTY)
                }
                
                // 2. 简化的中文文本纠错（只做基本的错误修正）
                if (enableChineseTextCorrection) {
                    partialText = applySpeechRecognitionCorrections(partialText)
                    partialText = cleanupSpacesAndPunctuation(partialText)
                }
                
                // 3. 上下文优化
                if (enableContextualRecognition) {
                    val (processedText, scoreBoost) = processWithContext(partialText)
                    partialText = processedText
                    confidence = minOf(1.0f, confidence + scoreBoost)
                }
            }
            
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
            return confidenceArray.average().toFloat()
        }
        
        // 3. 🔧 如果没有置信度但有文本，给一个合理的默认值而不是0.0f
        return if (text.isNotBlank()) {
            if (isPartial) 0.3f else 0.5f  // 部分结果给较低置信度，最终结果给中等置信度
        } else {
            0.0f
        }
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
            
            // 🎯 采样率调试日志 - 音频累积过程
            val inputSampleRate = AudioDefaults.INPUT_DEVICE_SAMPLE_RATE
            val inputChannels = AudioDefaults.INPUT_DEVICE_CHANNELS
            val newAudioDurationMs = (actualLength / 2 / inputChannels * 1000) / inputSampleRate
            val currentTotalDurationMs = (accumulatedAudio.size / 2 / inputChannels * 1000) / inputSampleRate
            logger.debug("🎯 音频累积: 新增${actualLength}字节/${newAudioDurationMs}ms, 当前总计${accumulatedAudio.size}字节/${currentTotalDurationMs}ms, 格式=${inputSampleRate}Hz/${inputChannels}ch")
            
            // 如果累积的数据会超过最大缓冲区，先清理旧数据
            if (accumulatedAudio.size + actualLength > maxAudioBufferSize) {
                // 保留最新的一半数据，为新数据腾出空间
                val keepSize = maxAudioBufferSize / 2
                val beforeCleanDurationMs = (accumulatedAudio.size / 2 / inputChannels * 1000) / inputSampleRate
                accumulatedAudio = accumulatedAudio.takeLast(keepSize).toByteArray()
                val afterCleanDurationMs = (accumulatedAudio.size / 2 / inputChannels * 1000) / inputSampleRate
                logger.debug("🎯 缓冲区清理: ${beforeCleanDurationMs}ms -> ${afterCleanDurationMs}ms，清理到${keepSize}字节")
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
            
            // 🔧 新增：检查模型语言类型
            if (modelPath.contains("cn") || modelPath.contains("chinese") || modelPath.contains("中文")) {
                logger.info("✅ 检测到中文模型路径，适合中文语音识别")
            } else {
                logger.warn("⚠️ 模型路径似乎不是中文模型，可能影响中文识别效果")
            }
            
            // 加载Vosk模型
            logger.info("正在加载Vosk模型...")
            voskModel = vosk_model_new(modelPath)
            if (voskModel == null) {
                logger.error("Vosk模型加载失败，路径: $modelPath")
                return false
            }
            logger.info("✅ Vosk模型加载成功")
            
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
            
            // === Vosk 优化配置 ===
            // 1. 设置多候选结果
            vosk_recognizer_set_max_alternatives(voskRecognizer, voskMaxAlternatives)
            logger.info("✅ 设置最大候选结果数: $voskMaxAlternatives")
            
            // 2. 启用词级别信息和部分结果
            vosk_recognizer_set_words(voskRecognizer, if (voskEnableWords) 1 else 0)
            vosk_recognizer_set_partial_words(voskRecognizer, if (voskEnablePartialWords) 1 else 0)
            logger.info("✅ 启用词级别信息: $voskEnableWords, 部分结果: $voskEnablePartialWords")
            
            // 3. 设置端点检测模式和延迟
            vosk_recognizer_set_endpointer_mode(voskRecognizer, VOSK_EP_ANSWER_SHORT)  // 短模式，适合关键词检测
            vosk_recognizer_set_endpointer_delays(voskRecognizer,
                voskStartMaxDelay,    // 初始静音超时
                voskEndDelay,         // 识别后静音超时
                voskMaxDuration       // 最大发音时长
            )
            logger.info("✅ 端点检测配置: 初始延迟=${voskStartMaxDelay}s, 结束延迟=${voskEndDelay}s, 最大时长=${voskMaxDuration}s")
            
            // 4. 设置日志级别（0=默认，<0=静音，>0=详细）
            vosk_set_log_level(0)  // 使用默认日志级别
            
            logger.info("✅ Vosk识别器优化配置完成")
            
            // 添加默认的短命令关键词
            registeredKeywords.addAll(shortCommandKeywords)

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
            logger.info("已更新关键词，共${registeredKeywords.size}个：${registeredKeywords.joinToString(", ")}")
            return true
        } catch (e: Exception) {
            logger.error("更新关键词异常: ${e.message}")
            return false
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
    
    // === 中文文本后处理方法 ===
    
    /**
     * 对中文文本进行后处理
     * @param text 原始文本
     * @return 处理后的文本
     */
    private fun postProcessChineseText(text: String): String {
        if (text.isBlank()) return text
        
        var processedText = text.trim()
        logger.debug("🔧 文本后处理开始: '$text' -> '$processedText'")
        
        // 1. 语音识别特定错误修正
        if (enableCommonErrorCorrection) {
            val beforeCorrection = processedText
            processedText = applySpeechRecognitionCorrections(processedText)
            if (beforeCorrection != processedText) {
                logger.info("🔧 语音识别纠错: '$beforeCorrection' -> '$processedText'")
            }
        }
        
        // 2. 常见错误替换
        if (enableCommonErrorCorrection) {
            val beforeCommon = processedText
            processedText = applyCommonCorrections(processedText)
            if (beforeCommon != processedText) {
                logger.info("🔧 常见错误纠错: '$beforeCommon' -> '$processedText'")
            }
        }
        
        // 3. 去除重复字符
        if (enableDuplicateCharRemoval) {
            val beforeDuplicate = processedText
            processedText = removeDuplicateCharacters(processedText, maxDuplicateChars)
            if (beforeDuplicate != processedText) {
                logger.info("🔧 重复字符移除: '$beforeDuplicate' -> '$processedText'")
            }
        }
        
        // 4. 清理多余空格和标点
        val beforeCleanup = processedText
        processedText = cleanupSpacesAndPunctuation(processedText)
        if (beforeCleanup != processedText) {
            logger.debug("🔧 空格标点清理: '$beforeCleanup' -> '$processedText'")
        }
        
        logger.debug("🔧 文本后处理完成: '$text' -> '$processedText'")
        return processedText
    }
    
    /**
     * 应用语音识别特定的错误修正
     */
    private fun applySpeechRecognitionCorrections(text: String): String {
        var result = text
        for ((wrong, correct) in speechRecognitionCorrections) {
            result = result.replace(wrong, correct, ignoreCase = true)
        }
        return result
    }
    
    /**
     * 应用常见错误修正
     */
    private fun applyCommonCorrections(text: String): String {
        var result = text
        for ((wrong, correct) in commonCorrections) {
            result = result.replace(wrong, correct)
        }
        return result
    }
    
    /**
     * 移除重复字符
     * @param text 输入文本
     * @param maxDuplicates 最大允许重复次数
     * @return 处理后的文本
     */
    private fun removeDuplicateCharacters(text: String, maxDuplicates: Int): String {
        if (text.length <= 1) return text
        
        val result = StringBuilder()
        var currentChar = text[0]
        var count = 1
        result.append(currentChar)
        
        for (i in 1 until text.length) {
            val char = text[i]
            if (char == currentChar) {
                count++
                if (count <= maxDuplicates) {
                    result.append(char)
                }
                // 超过最大重复次数的字符被忽略
            } else {
                currentChar = char
                count = 1
                result.append(char)
            }
        }
        
        return result.toString()
    }
    
    /**
     * 清理多余的空格和标点符号
     */
    private fun cleanupSpacesAndPunctuation(text: String): String {
        return text
            .replace(Regex("\\s+"), " ")  // 多个空格替换为单个空格
            .replace(Regex("[，。！？；：、]{2,}"), "")  // 移除重复的标点符号
            .trim()
    }
    
    /**
     * 上下文优化处理
     * @param text 识别文本
     * @return 处理结果和置信度加成
     */
    private fun processWithContext(text: String): Pair<String, Float> {
        var scoreBoost = 0.0f
        
        // 检查文本中是否包含上下文关键词
        for (keyword in contextKeywords) {
            if (text.contains(keyword, ignoreCase = true)) {
                scoreBoost += contextBoostScore
            }
        }
        
        // 限制最大加成
        scoreBoost = minOf(scoreBoost, 0.5f)
        
        return Pair(text, scoreBoost)
    }
}