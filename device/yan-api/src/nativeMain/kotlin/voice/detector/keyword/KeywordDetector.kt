@file:OptIn(ExperimentalForeignApi::class)

package voice.detector.keyword

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.posix.log
import voice.api.KeywordDetectorApi
import voice.audio.processing.WebRtcApmSingleton
import voice.util.LogManager
import voice.util.AudioDefaults
import voice.util.AudioUtils
import voice.audio.recognition.VoskSpeechRecognizer
import voice.acquisition.portaudio.PortAudioAcquisition

/**
 * 关键词检测器
 * 使用 Vosk 进行关键词检测，通过 WebRTC APM 进行前处理
 */
class KeywordDetector : KeywordDetectorApi {
    private val logger = LogManager.getLogger("KeywordDetector")
    
    // Vosk 检测器实例
    private val voskDetector = VoskKeywordDetector()
    
    // 当前状态
    private var isListening = false
    private var isInitialized = false
    private val _detectorState = MutableStateFlow(KeywordDetectorApi.DetectorState.IDLE)
    override val detectorState: StateFlow<KeywordDetectorApi.DetectorState> = _detectorState.asStateFlow()
    
    // 检测配置
    private var sensitivity: Float = 0.75f
    
    // 回调
    private var keywordCallback: ((String) -> Unit)? = null
    
    // 关键词列表
    private val keywords = mutableListOf<String>()
    
    // 音频采集器
    private val acquisition = PortAudioAcquisition()
    
    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Default)
    
    // VAD参数 - 使用WebRTC提供的VAD功能
    private val vadDebounceFrames = 5   // 从10降低到5，减少所需的连续帧数
    private var consecutiveVoiceFrames = 0
    
    // 音频质量判断参数
    // 当 calculateRmsEnergy 归一化到 0~1 区间后，正常语音 RMS ≈ 0.03~0.3。
    // 设置 0.02 作为下限，过滤极低噪声。
    private val minValidRms = 0.02
    
    // 添加计数器以限制日志
    private var audioReadCounter = 0
    
    /**
     * 初始化关键词检测器
     * @param modelPath 模型路径
     * @param sensitivity 敏感度 [0,1]
     * @return 初始化是否成功
     */
    override fun initialize(modelPath: String, sensitivity: Float): Boolean {
        logger.info("KeywordDetector.initialize() 被调用")
        
        if (isInitialized) {
            logger.warn("关键词检测器已经初始化")
            return true
        }
        
        this.sensitivity = sensitivity
        
        // 初始化 Vosk 检测器
        if (!voskDetector.initialize(modelPath, sensitivity)) {
            logger.error("Vosk 关键词检测器初始化失败")
            return false
        }
        
        // 初始化 WebRTC APM
        WebRtcApmSingleton.getInstance(
            sampleRate = AudioDefaults.TARGET_SAMPLE_RATE,
            channels = 1
        )
        
        isInitialized = true
        _detectorState.value = KeywordDetectorApi.DetectorState.IDLE
        logger.info("关键词检测器初始化成功")
        return true
    }

    /**
     * 添加关键词
     * @param keyword 关键词
     */
    override fun addKeyword(keyword: String) {
        if (!keywords.contains(keyword)) {
            keywords.add(keyword)
            voskDetector.addKeyword(keyword)
            logger.info("添加关键词: $keyword")
        }
    }
    
    /**
     * 设置检测到关键词时的回调
     * @param callback 回调函数
     */
    fun setKeywordCallback(callback: (String) -> Unit) {
        this.keywordCallback = callback
        voskDetector.setKeywordCallback(callback)
        logger.info("已设置关键词检测回调")
    }
    
    /**
     * 开始监听关键词
     * @return 是否成功启动
     */
    override suspend fun startListening(): Boolean {
        logger.info("KeywordDetector.startListening() 被调用")
        
        if (!isInitialized) {
            logger.error("关键词检测器未初始化")
            return false
        }
        
        if (isListening) {
            logger.warn("关键词检测器已经在监听中")
            return true
        }
        
        // 启动底层音频采集
        try {
            acquisition.startCapture { byteArray, length ->
                // 将原始PCM字节发送到检测逻辑
                detect(byteArray, length)
            }
        } catch (e: Exception) {
            logger.error("启动音频采集失败: ${e.message}")
            return false
        }

        isListening = true
        _detectorState.value = KeywordDetectorApi.DetectorState.LISTENING
        logger.info("startListening流程结束，状态: LISTENING")
        return true
    }
    
    /**
     * 停止监听关键词
     */
    override fun stopListening() {
        logger.info("KeywordDetector.stopListening() 被调用")
        
        if (!isListening) {
            logger.warn("关键词检测器未在监听")
            return
        }
        
        // 停止采集
        acquisition.stopCapture()

        isListening = false
        _detectorState.value = KeywordDetectorApi.DetectorState.IDLE
        logger.info("关键词检测器已停止监听")
    }
    
    /**
     * 用于API兼容性的检测方法 - 字节数组版本
     * 此方法不在接口中定义，但为了兼容性提供
     * @param audioData 音频数据(字节数组)
     * @param length 数据长度
     * @return 是否检测到关键词
     */
    fun detect(audioData: ByteArray, length: Int): Boolean {
        if (!isInitialized || !isListening) {
            return false
        }
        
        try {
            // 将ByteArray转换为ShortArray
            val shorts = AudioUtils.byteArrayToShortArray(audioData, length)
            
            // 使用ShortArray版本处理
            return processAudioFrame(shorts, shorts.size)
        } catch (e: Exception) {
            logger.error("关键词检测出错: ${e.message}")
            return false
        }
    }
    
    /**
     * 计算音频能量(RMS)
     */
    private fun calculateRmsEnergy(audio: ShortArray): Double {
        if (audio.isEmpty()) return 0.0
        
        var sum = 0.0
        for (sample in audio) {
            // 直接计算平方和，不做归一化
            sum += (sample * sample).toDouble()
        }
        
        // 计算RMS并归一化到[0,1]范围
        val rms = kotlin.math.sqrt(sum / audio.size)
        return rms / Short.MAX_VALUE
    }
    
    /**
     * 处理音频帧 - 短整型数组版本
     * @param audioFrame 音频数据(短整型数组)
     * @param frameSize 帧大小
     * @return 是否检测到关键词
     */
    override fun processAudioFrame(audioFrame: ShortArray, frameSize: Int): Boolean {
        if (!isInitialized) {
            return false
        }
        
        try {
            // 使用 WebRTC APM 进行音频预处理和VAD检测
            val processedData = WebRtcApmSingleton.processFrame(audioFrame)
            
            // 使用WebRTC内置VAD进行语音检测
            val hasVoiceActivity = WebRtcApmSingleton.isVoiceDetected()
            
            // 计算音频能量，作为额外过滤条件
            val rms = calculateRmsEnergy(processedData)
            
            // 只有VAD和能量都满足条件才认为有语音活动
            val isRealVoice = hasVoiceActivity && rms > minValidRms
            
            // 每100帧记录一次状态，帮助调试
            if (audioReadCounter++ % 100 == 0) {
                // 添加最大值信息到日志
                var maxValue = 0.0
                for (sample in processedData) {
                    val abs = kotlin.math.abs(sample.toDouble())
                    if (abs > maxValue) maxValue = abs
                }
                logger.debug("当前音频能量: $rms, 最大值: $maxValue, VAD状态: $hasVoiceActivity isRealVoice:$isRealVoice")
            }
            
            if (isRealVoice) {
                consecutiveVoiceFrames++
                
                // 稳定检测 - 至少连续N帧有语音才认为是语音段
                if (consecutiveVoiceFrames >= vadDebounceFrames) {
                    // 使用 Vosk 检测关键词
                    // 传递VAD结果为true，避免重复计算
                    val detected = voskDetector.detect(processedData, true)
                    
                    if (detected) {
                        logger.info("✓ 检测到关键词!")
                        _detectorState.value = KeywordDetectorApi.DetectorState.DETECTED
                        // 重置语音帧计数器
                        consecutiveVoiceFrames = 0
                        return true
                    }
                }
            } else {
                // 如果当前帧没有语音活动，重置连续计数
                if (consecutiveVoiceFrames > 0) {
                    consecutiveVoiceFrames = 0
                }
            }
            
            return false
        } catch (e: Exception) {
            logger.error("关键词处理出错: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
    
    /**
     * 设置敏感度
     * @param sensitivity 敏感度值 [0,1]
     */
    override fun setSensitivity(sensitivity: Float) {
        this.sensitivity = sensitivity.coerceIn(0f, 1f)
        voskDetector.setSensitivity(this.sensitivity)
        logger.info("设置敏感度: $sensitivity")
    }
    
    /**
     * 获取当前敏感度
     * @return 当前敏感度值
     */
    override fun getSensitivity(): Float {
        return sensitivity
    }
    
    /**
     * 释放资源
     */
    override fun release() {
        logger.info("KeywordDetector.release() 被调用")
        
        if (isListening) {
            stopListening()
        }
        
        if (isInitialized) {
            voskDetector.release()
            acquisition.release()
            isInitialized = false
        }
        
        _detectorState.value = KeywordDetectorApi.DetectorState.IDLE
        logger.info("关键词检测器资源已释放")
    }

    /**
     * 生成诊断报告
     */
    fun generateDiagnostics(): String {
        val sb = StringBuilder()
        sb.appendLine("===== 关键词检测器诊断 =====")
        sb.appendLine("初始化状态: $isInitialized")
        sb.appendLine("监听状态: $isListening")
        sb.appendLine("检测器状态: ${detectorState.value}")
        sb.appendLine("敏感度: $sensitivity")
        sb.appendLine("关键词列表: ${keywords.joinToString(", ")}")
        return sb.toString()
    }
} 