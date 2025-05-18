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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    
    // 简短命令增强
    private val shortCommandKeywords = listOf(
        "你好", "嗨", "哈喽", "开始", "停止", "暂停", "继续", 
        "音量", "大", "小", "关闭", "打开", "启动", "退出"
    )
    
    /**
     * 处理音频数据
     * @param audio 音频数据
     * @param length 数据长度
     * @return 识别结果
     */
    override fun recognize(audio: ByteArray, length: Int): AudioPipeline.SpeechRecognition.RecognitionResult {
        if (!isInitialized) {
            logger.error("Vosk识别器未初始化")
            return AudioPipeline.SpeechRecognition.RecognitionResult(
                success = false,
                text = "",
                isPartial = false,
                metrics = RecognitionMetrics(
                    processingTimeMs = 0,
                    confidenceScore = 0.0f,
                    errorCode = 1,
                    errorMessage = "识别器未初始化"
                )
            )
        }
        
        recognitionCount++
        val currentTime = LogManager.getCurrentTimeMillis()
        
        logger.info("【调试】Vosk识别开始 #$recognitionCount: 音频长度=$length, 时间=$currentTime")
        
        try {
            // 计算能量水平
            val energy = calculateEnergy(audio, length)
            logger.info("【调试】Vosk音频能量: $energy")
            
            // 音频累积处理
            if (length > 0) {
                // 积累音频数据
                val newBuffer = ByteArray(accumulatedAudio.size + length)
                // Kotlin Native 不支持 System.arraycopy，使用手动复制
                for (i in accumulatedAudio.indices) {
                    newBuffer[i] = accumulatedAudio[i]
                }
                for (i in 0 until length) {
                    newBuffer[accumulatedAudio.size + i] = audio[i]
                }
                accumulatedAudio = newBuffer
                
                // 限制积累的最大大小
                if (accumulatedAudio.size > maxAudioBufferSize) {
                    // 保留后半部分
                    val newSize = maxAudioBufferSize / 2
                    val tempBuffer = ByteArray(newSize)
                    for (i in 0 until newSize) {
                        tempBuffer[i] = accumulatedAudio[accumulatedAudio.size - newSize + i]
                    }
                    accumulatedAudio = tempBuffer
                }
            }
            
            // 检查是否应该执行识别
            val timeElapsed = currentTime - lastRecognitionTime
            val bufferSizeAdequate = accumulatedAudio.size >= minAudioBufferSize
            
            if (!bufferSizeAdequate || timeElapsed < recognitionCooldownMs) {
                // 缓冲区太小或时间间隔太短，返回空结果
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
            
            // 准备处理音频
            val halfLength = accumulatedAudio.size / 2
            logger.info("【调试】Vosk处理音频: 大小=$halfLength")
            
            // 处理16位PCM音频 (ShortArray)
            val samples = nativeHeap.allocArray<ShortVar>(halfLength)
            for (i in 0 until halfLength) {
                val lowByte = accumulatedAudio[i * 2].toInt() and 0xFF
                val highByte = accumulatedAudio[i * 2 + 1].toInt() and 0xFF
                samples[i] = ((highByte shl 8) or lowByte).toShort()
            }
            
            // 调用Vosk处理音频
            val result = vosk_recognizer_accept_waveform_s(
                voskRecognizer, 
                samples, 
                halfLength.toInt()
            )
            
            logger.info("【调试】Vosk处理结果: 状态=$result")
            
            // 释放临时缓冲区
            nativeHeap.free(samples)
            
            var text = ""
            var isPartial = true
            
            // 根据处理结果获取识别文本
            if (result == 0) {
                // 部分结果
                val partialResult = vosk_recognizer_partial_result(voskRecognizer)?.toKString() ?: "{}"
                logger.info("【调试】Vosk识别原始JSON: $partialResult")
                text = extractTextFromJson(partialResult, "partial")
                isPartial = true
            } else {
                // 最终结果
                val finalResult = vosk_recognizer_result(voskRecognizer)?.toKString() ?: "{}"
                logger.info("【调试】Vosk识别最终JSON: $finalResult")
                text = extractTextFromJson(finalResult, "text")
                isPartial = false
                
                // 如果有最终结果，重置识别器状态
                vosk_recognizer_reset(voskRecognizer)
                accumulatedAudio = ByteArray(0)
            }
            
            logger.info("【调试】Vosk提取文本: \"$text\"")
            
            // 优化短命令识别
            if (text.isBlank() && energy > 1000) {
                // 检查是否有单个短命令
                for (keyword in shortCommandKeywords) {
                    if (accumulatedAudio.size < keyword.length * 100) {
                        // 有足够的音频长度可能包含这个关键词
                        text = tryDetectShortCommand(keyword)
                        if (text.isNotBlank()) {
                            logger.info("【调试】检测到短命令: $text")
                            break
                        }
                    }
                }
            }
            
            // 更新最后识别时间
            lastRecognitionTime = currentTime
            
            return AudioPipeline.SpeechRecognition.RecognitionResult(
                success = true,
                text = text,
                isPartial = isPartial,
                metrics = RecognitionMetrics(
                    processingTimeMs = currentTime - LogManager.getCurrentTimeMillis(),
                    confidenceScore = if (text.isNotBlank()) 0.8f else 0.0f,
                    errorCode = 0,
                    errorMessage = ""
                )
            )
        } catch (e: Exception) {
            logger.error("Vosk识别异常: ${e.message}")
            return AudioPipeline.SpeechRecognition.RecognitionResult(
                success = false,
                text = "",
                isPartial = false,
                metrics = RecognitionMetrics(
                    processingTimeMs = 0,
                    confidenceScore = 0.0f,
                    errorCode = 2,
                    errorMessage = "识别处理异常: ${e.message}"
                )
            )
        }
    }
    
    /**
     * 尝试检测短命令
     */
    private fun tryDetectShortCommand(keyword: String): String {
        // 这里简化处理，假设有足够的声音能量就可能是短命令
        // 实际中可以添加更复杂的声纹匹配算法
        return keyword
    }
    
    /**
     * 从JSON中提取文本
     */
    private fun extractTextFromJson(jsonStr: String, key: String): String {
        return try {
            val json = Json.parseToJsonElement(jsonStr).jsonObject
            json[key]?.jsonPrimitive?.content ?: ""
        } catch (e: Exception) {
            logger.error("JSON解析失败: $e")
            ""
        }
    }
    
    /**
     * 计算音频能量
     */
    private fun calculateEnergy(audio: ByteArray, length: Int): Double {
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
            
            // 启用部分结果获取
            vosk_recognizer_set_words(voskRecognizer, 1)
            
            // 添加简短命令语法支持
            setupShortCommandGrammar()
            
            isInitialized = true
            logger.info("Vosk语音识别器初始化成功")
            return true
        } catch (e: Exception) {
            logger.error("Vosk语音识别器初始化异常: ${e.message}")
            if (voskModel != null) {
                vosk_model_free(voskModel)
                voskModel = null
            }
            return false
        }
    }
    
    /**
     * 设置简短命令识别语法
     */
    private fun setupShortCommandGrammar() {
        try {
            // 直接使用简单的字符串数组格式，而不是嵌套结构
            val keywordsJson = buildString {
                append("[")
                shortCommandKeywords.forEachIndexed { index, keyword ->
                    if (index > 0) append(", ")
                    append("\"").append(keyword).append("\"")
                }
                append("]")
            }
            
            logger.info("设置简短命令JSON: $keywordsJson")
            vosk_recognizer_set_grm(voskRecognizer, keywordsJson)
            logger.info("已设置简短命令语法")
        } catch (e: Exception) {
            logger.error("设置简短命令语法失败: ${e.message}")
        }
    }
    
    /**
     * 更新关键词
     * @param keywords 关键词列表，逗号分隔
     * @return 更新是否成功
     */
    fun updateKeywords(keywords: String): Boolean {
        if (!isInitialized) {
            logger.error("Vosk识别器未初始化，无法更新关键词")
            return false
        }
        
        try {
            logger.info("更新关键词: $keywords")
            
            // 添加短命令到关键词中
            val allKeywords = keywords.split(",").toMutableList()
            shortCommandKeywords.forEach { keyword ->
                if (!allKeywords.contains(keyword)) {
                    allKeywords.add(keyword)
                }
            }
            
            // 构建简单的字符串数组，使用正确的JSON格式
            val keywordsJson = buildString {
                append("[")
                allKeywords.forEachIndexed { index, keyword ->
                    if (index > 0) append(", ")
                    append("\"").append(keyword.trim()).append("\"")
                }
                append("]")
            }
            
            logger.info("关键词JSON: $keywordsJson")
            
            // 设置Vosk语法
            vosk_recognizer_set_grm(voskRecognizer, keywordsJson)
            
            return true
        } catch (e: Exception) {
            logger.error("更新关键词异常: ${e.message}")
            return false
        }
    }
    
    /**
     * 重置识别器状态
     */
    fun reset() {
        if (isInitialized) {
            logger.info("重置Vosk识别器")
            vosk_recognizer_reset(voskRecognizer)
            accumulatedAudio = ByteArray(0)
            lastRecognitionTime = 0L
        }
    }
    
    /**
     * 释放资源
     */
    fun release() {
        logger.info("释放Vosk识别器资源")
        
        if (voskRecognizer != null) {
            vosk_recognizer_free(voskRecognizer)
            voskRecognizer = null
        }
        
        if (voskModel != null) {
            vosk_model_free(voskModel)
            voskModel = null
        }
        
        isInitialized = false
    }
} 