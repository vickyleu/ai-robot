package voice.audio.processing

import com.airobot.core.utils.format
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.datetime.Clock.System
import voice.api.AudioProcessingApi
import voice.api.vad.IVoiceActivityDetector
import voice.audio.AudioPipeline
import voice.acquisition.portaudio.PortAudioAcquisition
import voice.audio.recognition.VoskSpeechRecognizer
import voice.audio.vad.VoiceActivityDetector
import voice.util.AudioUtils
import voice.util.DiagnosticsCollector
import voice.util.LogManager
import kotlin.time.ExperimentalTime
import voice.acquisition.portaudio.PortAudioDevice

/**
 * 音频处理管理器
 * 负责协调音频处理流水线中的各个组件
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
class AudioProcessingManager(
    private val modelPath: String
) : AudioProcessingApi {
    private val logger = LogManager.getLogger("AudioProcessingManager")
    
    // 音频流水线组件
    private val acquisition = PortAudioAcquisition(
        AudioPipeline.Acquisition.Config(sampleRate = 16000, channels = 2)
    )
    private val preprocessor = AudioPreprocessor()
    private val vad = VoiceActivityDetector()
    private val recognizer = VoskSpeechRecognizer()
    private val diagnostics = DiagnosticsCollector()

    // 专用的播放设备用于识别回放，与录音设备分开可避免冲突
    private val playbackDevice: PortAudioDevice = PortAudioDevice.getInstance()
    private var playerReady = false
    
    // 回调处理
    private var keywordDetectedCallback: ((String) -> Unit)? = null
    
    // 状态标志
    private var isInitialized = false
    private var isRunning = false
    
    // 处理统计
    private var frameCount = 0
    private var speechFrameCount = 0
    private var lastFrameTime = 0L
    private var recognitionCallCount = 0
    
    // 记录处理开始的时间，用于计算识别延迟
    private var processingStartTime = 0L
    
    // 是否处于调试模式，只有调试模式才会输出部分日志
    private val debugMode = false

    // 用于VAD动态阈值调整的参数
    private val initialVadThreshold = 0.8f     // 初始VAD阈值
    private val energyLevels = ArrayList<Double>(30) // 保存最近的能量值
    private var adaptiveVadThreshold = initialVadThreshold // 动态调整的VAD阈值
    private var backgroundNoiseLevel = 0.0     // 背景噪声水平

    /**
     * 获取处理统计信息
     */
    override fun getStats(): AudioProcessingApi.ProcessingStats {
        return AudioProcessingApi.ProcessingStats(
            frameCount = frameCount,
            speechFrameCount = speechFrameCount,
            recognitionCallCount = recognitionCallCount,
            lastFrameTime = lastFrameTime
        )
    }
    
    /**
     * 初始化音频处理管理器
     * @return 初始化是否成功
     */
    override fun initialize(): Boolean {
        if (isInitialized) {
            logger.warn("音频处理管理器已初始化")
            return true
        }
        
        logger.info("初始化音频处理管理器")
        
        // 初始化各组件
        if (!acquisition.initialize()) {
            logger.error("音频采集初始化失败")
            return false
        }
        
        if (!recognizer.initialize(modelPath)) {
            logger.error("语音识别器初始化失败")
            acquisition.release()
            return false
        }
        
        // 配置VAD灵敏度 - 设置为中低灵敏度(0.4)以减少误触发
        vad.setSensitivity(0.4f)
        logger.info("已设置VAD灵敏度为中低灵敏度(0.4)，减少误触发")

        // 初始化专用的播放设备 (与录音设备分离)
        if (playbackDevice.initialize("回放设备", 16000)) {
            if (playbackDevice.start()) {
                playerReady = true
                logger.info("专用播放设备初始化成功")
            } else {
                logger.warn("启动专用播放设备失败，回放功能将不可用")
                playerReady = false
            }
        } else {
            logger.warn("初始化专用播放设备失败，回放功能将不可用")
            playerReady = false
        }
        
        isInitialized = true
        logger.info("音频处理管理器初始化成功")
        return true
    }
    
    /**
     * 设置关键词检测回调
     */
    override fun setKeywordDetectedCallback(callback: (String) -> Unit) {
        keywordDetectedCallback = callback
    }
    
    /**
     * 更新关键词列表
     * @param keywords 关键词列表
     */
    override fun updateKeywords(keywords: List<String>) {
        logger.info("更新关键词列表: ${keywords.joinToString(", ")}")
        
        if (!isInitialized) {
            logger.warn("音频处理管理器未初始化，无法更新关键词")
            return
        }
        
        val keywordsString = keywords.joinToString(",")
        recognizer.updateKeywords(keywordsString)
    }
    
    /**
     * 开始音频处理
     */
    override fun start() {
        if (isRunning) {
            logger.warn("音频处理流水线已经在运行中")
            return
        }
        if (!isInitialized) {
            logger.error("音频处理管理器未初始化，无法启动！")
            return
        }
        
        logger.info("启动音频处理流水线")
        processingStartTime = System.now().toEpochMilliseconds()
        
        acquisition.startCapture { audioData, length ->
            val timestamp = LogManager.getCurrentTimeMillis()
            processAudioFrame(audioData, length, timestamp)
        }
        
        isRunning = true
    }
    
    /**
     * 处理音频帧
     * @param audioData 原始音频数据
     * @param length 数据长度
     * @param timestamp 时间戳
     */
    private fun processAudioFrame(audioData: ByteArray, length: Int, timestamp: Long) {
        frameCount++
        lastFrameTime = timestamp
        
        // 计算当前音频帧的能量
        val energy = calculateRms(audioData, length)
        
        // 更新背景噪声和动态VAD阈值
        updateAudioEnvironment(energy)
        
        // 仅在调试模式下每100帧输出系统状态
        if (debugMode && frameCount % 100 == 0) {
            logger.debug("音频帧#$frameCount: 能量=$energy, 噪声=$backgroundNoiseLevel, VAD阈值=$adaptiveVadThreshold")
        }
        
        // ===== 音频处理流水线 =====
        
        // 1. 音频预处理
        val processResult = preprocessor.process(audioData, length)
        
        // 如果预处理判断为不应继续，则跳过后续处理
        if (!processResult.shouldContinue || processResult.processedLength == 0) {
            return
        }
        
        // 2. 语音活动检测
        val vadResult = vad.detect(processResult.processedAudio, processResult.processedLength)

        // 使用动态阈值进行VAD判断
        val speechDetected = vadResult.hasSpeech && vadResult.confidence > adaptiveVadThreshold
        
        // 仅当检测到语音且置信度高时输出VAD结果
        if (speechDetected) {
            logger.info("检测到语音活动: 置信度=${"%.2f".format(vadResult.confidence)}, 能量=${"%.1f".format(vadResult.metrics.energyLevel)}")
        }
        
        // 使用动态阈值进行语音识别判断
        if (speechDetected) {
            // 获取有效的处理后音频数据
            val processedAudio = if (processResult.processedLength > 0) {
                processResult.processedAudio.copyOfRange(0, processResult.processedLength)
            } else {
                return
            }
            
            // 3. 语音识别
            val recognitionResult = recognizer.recognize(processedAudio, processedAudio.size)
            recognitionCallCount++
            
            // 处理识别结果
            handleRecognitionResult(recognitionResult, processedAudio, vadResult)
        }
    }
    
    /**
     * 更新音频环境参数，动态调整VAD阈值
     */
    private fun updateAudioEnvironment(currentEnergy: Double) {
        // 保存最近的能量值
        if (energyLevels.size >= 30) {
            energyLevels.removeAt(0)
        }
        energyLevels.add(currentEnergy)
        
        // 至少需要5个样本才能计算
        if (energyLevels.size < 5) return
        
        // 计算噪声基线 - 使用低位四分位值作为背景噪声估计
        val sortedLevels = energyLevels.sorted()
        val lowQuartileIndex = (sortedLevels.size * 0.25).toInt()
        backgroundNoiseLevel = sortedLevels[lowQuartileIndex]
        
        // 动态调整VAD阈值 - 噪声越大，阈值越高
        val baseThreshold = initialVadThreshold
        val noiseAdjustment = (backgroundNoiseLevel / 1000.0).coerceIn(0.0, 0.15)
        adaptiveVadThreshold = (baseThreshold + noiseAdjustment.toFloat()).coerceIn(0.65f, 0.95f)
    }
    
    /**
     * 处理识别结果
     * @param result 识别结果
     * @param audioData 产生该结果的音频数据
     * @param vadResult VAD结果
     */
    private fun handleRecognitionResult(
        result: AudioPipeline.SpeechRecognition.RecognitionResult,
        audioData: ByteArray,
        vadResult: IVoiceActivityDetector.DetectionResult
    ) {
        // 只处理成功的结果且文本不为空
        if (!result.success || result.text.isBlank()) {
            if (!result.success) {
                logger.warn("识别失败: ${result.metrics.errorMessage}")
            }
            return
        }
        
        // 记录非部分结果的识别文本
        if (!result.isPartial) {
            speechFrameCount++
            
            logger.info("识别结果: \"${result.text}\"")
            
            // 播放用于调试目的的音频
            playRecognizedAudio(audioData)
            
            // 获取所有关键词列表
            val currentKeywords = recognizer.getCurrentKeywords()
            
            if (currentKeywords.isEmpty()) {
                logger.debug("当前没有注册关键词，跳过关键词检测")
                return
            }
            
            // 检查是否包含关键词
            val recognizedText = result.text.trim()
            val detectedKeywords = findKeywordsInText(recognizedText, currentKeywords)
            
            if (detectedKeywords.isNotEmpty()) {
                // 找到关键词，触发回调
                logger.info("💡 检测到关键词: [${detectedKeywords.joinToString(", ")}]")
                
                // 对识别到的文本进行智能处理，提取用户意图
                val processedText = processRecognizedText(recognizedText, detectedKeywords)
                
                // 回调传递处理后的文本
                keywordDetectedCallback?.invoke(processedText)
            } else {
                logger.info("识别到文本，但未包含已注册关键词: \"${result.text}\"")
            }
        }
    }
    
    /**
     * 处理识别出的文本，提取用户意图
     */
    private fun processRecognizedText(text: String, detectedKeywords: List<String>): String {
        if (detectedKeywords.isEmpty() || text.isBlank()) {
            return text
        }
        
        // 按关键词长度排序，优先处理较长的关键词（避免子串问题）
        val sortedKeywords = detectedKeywords.sortedByDescending { it.length }
        val primaryKeyword = sortedKeywords[0]
        
        // 尝试用关键词分割文本，提取更有用的命令部分
        val parts = text.split(primaryKeyword, ignoreCase = true)
        
        // 如果关键词在开头，取后面的内容作为命令参数
        if (parts.size > 1 && parts[0].trim().isEmpty()) {
            val commandParam = parts[1].trim()
            if (commandParam.isNotEmpty()) {
                return "$primaryKeyword $commandParam"
            }
        }
        
        // 如果关键词在句尾，取前面的内容作为修饰语
        if (parts.size > 1 && parts[parts.size - 1].trim().isEmpty()) {
            val modifier = parts[parts.size - 2].trim()
            if (modifier.isNotEmpty()) {
                return "$modifier $primaryKeyword"
            }
        }
        
        // 默认返回原始文本
        return text
    }
    
    /**
     * 在文本中查找关键词
     * @param text 要搜索的文本
     * @param keywords 关键词列表
     * @return 找到的关键词列表
     */
    private fun findKeywordsInText(text: String, keywords: List<String>): List<String> {
        if (text.isBlank() || keywords.isEmpty()) {
            return emptyList()
        }
        
        val found = mutableListOf<String>()
        val lowerText = text.lowercase()
        
        // 检查否定词前缀
        val negationPrefixes = listOf("不", "不要", "不需要", "别", "没有")
        val containsNegation = negationPrefixes.any { prefix -> 
            lowerText.contains(prefix)
        }
        
        // 提取文本中的所有词
        val textWords = text.lowercase().split(Regex("[\\s,.!?;，。！？；]"))
            .filter { it.isNotBlank() }
        
        for (keyword in keywords) {
            if (keyword.isBlank()) continue
            
            val lowerKeyword = keyword.lowercase()
            
            // 完整词匹配 - 作为独立词出现
            if (textWords.contains(lowerKeyword)) {
                // 检查该词前是否有否定词
                val keywordIndex = lowerText.indexOf(lowerKeyword)
                val hasNegationPrefix = negationPrefixes.any { prefix ->
                    keywordIndex >= prefix.length && 
                    lowerText.substring(keywordIndex - prefix.length, keywordIndex) == prefix
                }
                
                if (!hasNegationPrefix) {
                    found.add(keyword)
                    continue
                }
            }
            
            // 部分匹配 - 检查边界
            if (lowerText.contains(lowerKeyword)) {
                val keywordIndex = lowerText.indexOf(lowerKeyword)
                val endIndex = keywordIndex + lowerKeyword.length
                
                // 边界检查
                val validStart = keywordIndex == 0 || !lowerText[keywordIndex - 1].isLetterOrDigit()
                val validEnd = endIndex == lowerText.length || !lowerText[endIndex].isLetterOrDigit()
                
                // 检查否定前缀
                val hasNegationPrefix = negationPrefixes.any { prefix ->
                    keywordIndex >= prefix.length && 
                    lowerText.substring(keywordIndex - prefix.length, keywordIndex) == prefix
                }
                
                if (validStart && validEnd && !hasNegationPrefix && !containsNegation) {
                    found.add(keyword)
                }
            }
        }
        
        return found
    }
    
    /**
     * 播放被识别的音频（仅用于调试）
     */
    private fun playRecognizedAudio(audioData: ByteArray) {
        // 只有播放器就绪时才回放
        if (!playerReady || audioData.isEmpty()) {
            return
        }
        
        try {
            val shortArrayToPlay = AudioUtils.byteArrayToShortArray(audioData)
            if (shortArrayToPlay.isNotEmpty()) {
                playbackDevice.playAudio(shortArrayToPlay)
                logger.debug("回放识别的音频片段 (${shortArrayToPlay.size}采样点)")
            }
        } catch (e: Exception) {
            logger.warn("回放音频失败: ${e.message}")
        }
    }
    
    /**
     * 停止音频处理
     */
    override fun stop() {
        if (!isRunning) {
            logger.warn("音频处理管理器未在运行")
            return
        }
        
        logger.info("停止音频处理流水线")
        acquisition.stopCapture()
        isRunning = false
    }
    
    /**
     * 生成诊断报告
     */
    override fun generateDiagnosticReport(): String {
        val report = StringBuilder()
        report.appendLine("音频处理管理器诊断报告")
        report.appendLine("==========================")
        report.appendLine("初始化状态: ${if (isInitialized) "已初始化" else "未初始化"}")
        report.appendLine("运行状态: ${if (isRunning) "运行中" else "未运行"}")
        report.appendLine("处理总帧数: $frameCount")
        report.appendLine("识别到语音的帧数: $speechFrameCount")
        report.appendLine("语音比例: ${if (frameCount > 0 && speechFrameCount > 0) "${"%.2f".format(speechFrameCount.toDouble() * 100.0 / frameCount)}%" else "N/A"}")
        report.appendLine("最后一帧处理时间: $lastFrameTime")
        report.appendLine("识别调用次数: $recognitionCallCount")
        report.appendLine("运行时间: ${(System.now().toEpochMilliseconds() - processingStartTime) / 1000}秒")
        
        report.appendLine("音频采集器状态:")
        report.appendLine("------------------")
        report.appendLine("采样率: ${acquisition.config.sampleRate}Hz, 通道数: ${acquisition.config.channels}")
        
        report.appendLine("VAD状态:")
        report.appendLine("------------------")
        report.appendLine("VAD已配置")
        
        report.appendLine("识别器状态:")
        report.appendLine("------------------")
        report.appendLine("识别器已初始化: ${recognizer.isInitialized}")
        
        report.appendLine("播放器状态:")
        report.appendLine("------------------")
        report.appendLine("专用播放器已就绪: $playerReady")

        return report.toString()
    }
    
    /**
     * 处理来自KeywordDetector的音频数据
     * 更高效的音频处理接口，直接处理Short数组
     * @param audioData Short数组音频数据
     * @return Boolean 是否检测到关键词
     */
    fun processAudio(audioData: ShortArray): Boolean {
        if (!isInitialized || !isRunning) {
            return false
        }
        
        try {
            // 1. 转换为字节数组
            val byteData = AudioUtils.shortArrayToByteArray(audioData)
            
            // 计算音频能量并更新环境参数
            val energy = calculateRms(byteData, byteData.size)
            updateAudioEnvironment(energy)
            
            // 2. 音频预处理
            val processResult = preprocessor.process(byteData, byteData.size)
            
            // 如果预处理判断为不应继续，则返回false
            if (!processResult.shouldContinue || processResult.processedLength == 0) {
                return false
            }
            
            // 3. 语音活动检测
            val vadResult = vad.detect(processResult.processedAudio, processResult.processedLength)
            
            // 必须检测到语音且置信度足够高（使用动态阈值）
            if (!vadResult.hasSpeech || vadResult.confidence <= adaptiveVadThreshold) {
                return false
            }
            
            // 4. 语音识别
            val audioToRecognize = processResult.processedAudio.copyOfRange(0, processResult.processedLength)
            val recognitionResult = recognizer.recognize(audioToRecognize, audioToRecognize.size)
            
            // 5. 处理识别结果
            if (recognitionResult.success && !recognitionResult.isPartial && recognitionResult.text.isNotBlank()) {
                // 获取所有关键词列表
                val currentKeywords = recognizer.getCurrentKeywords()
                
                if (currentKeywords.isEmpty()) {
                    return false
                }
                
                // 检查是否包含关键词
                val detectedKeywords = findKeywordsInText(recognitionResult.text, currentKeywords)
                
                if (detectedKeywords.isNotEmpty()) {
                    // 这里检测到关键词，准备触发回调
                    
                    // 播放用于调试目的的音频
                    playRecognizedAudio(audioToRecognize)
                    
                    // 记录并更新统计
                    speechFrameCount++
                    recognitionCallCount++
                    
                    // 对识别到的文本进行智能处理
                    val processedText = processRecognizedText(recognitionResult.text, detectedKeywords)
                    
                    // 输出日志
                    logger.info("💡 检测到关键词: [${detectedKeywords.joinToString(", ")}] 文本: \"${processedText}\"")
                    
                    // 触发回调
                    keywordDetectedCallback?.invoke(processedText)
                    
                    // 成功检测到关键词，返回true
                    return true
                }
            }
            
            // 未检测到关键词，返回false
            return false
        } catch (e: Exception) {
            logger.error("处理短音频时发生异常: ${e.message}")
            return false
        }
    }
    
    /**
     * 释放资源
     */
    override fun release() {
        if (isRunning) {
            stop()
        }
        
        if (isInitialized) {
            acquisition.release()
            recognizer.release()
            
            if (playerReady) {
                logger.info("释放专用播放设备...")
                playbackDevice.stopPlayback()
                playbackDevice.release()
                playerReady = false
            }
            
            isInitialized = false
            logger.info("音频处理管理器资源已释放")
        }
    }
    
    /**
     * 计算音频数据的RMS能量
     */
    private fun calculateRms(audioData: ByteArray, length: Int): Double {
        if (length == 0 || length % 2 != 0) return 0.0
        var sumSquares = 0.0
        val sampleCount = length / 2 
        
        for (i in 0 until length step 2) {
            val byte1 = audioData[i].toInt() and 0xFF
            val byte2 = audioData[i + 1].toInt() and 0xFF
            var sample = (byte2 shl 8) or byte1
            // 将无符号16位PCM转换为有符号
            if (sample > 32767) {
                sample -= 65536
            }
            sumSquares += sample * sample.toDouble()
        }
        
        return if (sampleCount > 0) kotlin.math.sqrt(sumSquares / sampleCount) else 0.0
    }
}