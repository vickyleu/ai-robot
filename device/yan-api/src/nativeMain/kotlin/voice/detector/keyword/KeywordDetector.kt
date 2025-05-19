@file:OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)

package voice.detector.keyword

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import voice.api.KeywordDetectorApi
import voice.audio.processing.AudioProcessingManager
import voice.util.LogManager
import kotlin.time.ExperimentalTime

/**
 * Vosk关键词检测器
 * 负责监听音频中的关键词
 */
class KeywordDetector : KeywordDetectorApi {
    private val logger = LogManager.getLogger("KeywordDetector")
    
    // 音频处理管理器
    private var audioManager: AudioProcessingManager? = null
    
    // 回调处理
    private val callbacks = mutableListOf<(String) -> Unit>()
    
    // 检测状态
    private val _detectorState = MutableStateFlow(KeywordDetectorApi.DetectorState.IDLE)
    override val detectorState: StateFlow<KeywordDetectorApi.DetectorState> = _detectorState.asStateFlow()
    
    // 当前灵敏度
    private var sensitivity = 0.5f
    
    // 关键词列表
    private val keywords = mutableListOf<String>()
    
    // 用于计算音频能量的计数器
    private var detectCallCount = 0
    
    // 用于计算检测到的关键词数量
    private var keywordDetectedCount = 0
    
    /**
     * 初始化检测器
     * @param modelPath 模型文件路径
     * @param sensitivity 灵敏度，0.0-1.0
     * @return 初始化是否成功
     */
    override fun initialize(modelPath: String, sensitivity: Float): Boolean {
        logger.info("初始化KeywordDetector，模型路径: $modelPath")
        _detectorState.value = KeywordDetectorApi.DetectorState.IDLE
        
        this.sensitivity = sensitivity
        
        try {
            // 创建并初始化音频处理管理器
            audioManager = AudioProcessingManager(modelPath)
            
            // 设置关键词检测回调
            audioManager?.setKeywordDetectedCallback { text ->
                handleKeywordDetected(text)
            }
            
            // 初始化音频处理流水线
            val result = audioManager?.initialize() ?: false
            
            if (!result) {
                logger.error("初始化音频处理管理器失败")
                _detectorState.value = KeywordDetectorApi.DetectorState.ERROR
                return false
            }
            
            // 设置默认关键词
            addKeyword("小样")
            addKeyword("嘿小样")
            addKeyword("你好小样")
            
            logger.info("KeywordDetector初始化成功")
            _detectorState.value = KeywordDetectorApi.DetectorState.IDLE
            return true
        } catch (e: Exception) {
            logger.error("初始化KeywordDetector失败: ${e.message}")
            _detectorState.value = KeywordDetectorApi.DetectorState.ERROR
            return false
        }
    }
    
    /**
     * 开始监听
     * @return 是否成功开始监听
     */
    override fun startListening(): Boolean {
        logger.info("KeywordDetector.startListening() 被调用")
        try {
            if (audioManager == null) {
                logger.error("音频处理管理器未初始化")
                return false
            }
            _detectorState.value = KeywordDetectorApi.DetectorState.LISTENING
            logger.info("⭐⭐⭐ 调用 audioManager.start() 启动音频处理流水线 - 开始")
            try {
                logger.info("准备调用audioManager.start()，当前线程: ${platform.posix.getpid()}")
                audioManager?.start()
                logger.info("⭐⭐⭐ audioManager.start() 已调用成功，下一步操作")
            } catch (e: Exception) {
                logger.error("调用audioManager.start()时发生异常: ${e.message}")
                e.printStackTrace()
                _detectorState.value = KeywordDetectorApi.DetectorState.ERROR
                return false
            }
            logger.info("⭐⭐⭐ startListening流程结束，状态: ${_detectorState.value}")
            return true
        } catch (e: Exception) {
            logger.error("KeywordDetector.startListening() 发生异常: ${e.message}")
            e.printStackTrace()
            _detectorState.value = KeywordDetectorApi.DetectorState.ERROR
            return false
        }
    }
    
    /**
     * 停止监听
     */
    override fun stopListening() {
        logger.info("停止关键词监听")
        audioManager?.stop()
        _detectorState.value = KeywordDetectorApi.DetectorState.IDLE
    }
    
    /**
     * 处理音频帧
     * @param audioFrame 音频数据
     * @param frameSize 帧大小
     * @return 是否检测到关键词
     */
    override fun processAudioFrame(audioFrame: ShortArray, frameSize: Int): Boolean {
        // 由于AudioProcessingManager已经在自己的回调中处理音频数据，
        // 这里只需检查当前状态是否为已检测到关键词
        
        // 检查是否处于已检测状态
        val isDetected = _detectorState.value == KeywordDetectorApi.DetectorState.DETECTED
        
        // 如果检测到关键词，重置状态为监听中
        if (isDetected) {
            _detectorState.value = KeywordDetectorApi.DetectorState.LISTENING
        }
        
        return isDetected
    }
    
    /**
     * 设置灵敏度
     * @param sensitivity 灵敏度，0.0-1.0
     */
    override fun setSensitivity(sensitivity: Float) {
        logger.info("设置关键词检测灵敏度: $sensitivity")
        this.sensitivity = sensitivity
        // 实际上，我们可能需要将这个灵敏度传递给底层的AudioProcessingManager
        // 但目前的AudioProcessingManager没有这个方法
    }
    
    /**
     * 获取灵敏度
     * @return 当前灵敏度
     */
    override fun getSensitivity(): Float {
        return sensitivity
    }
    
    /**
     * 添加关键词
     */
    override fun addKeyword(keyword: String) {
        keywords.add(keyword)
        logger.info("添加关键词: $keyword")
        
        // 更新Vosk识别器的关键词列表
        audioManager?.updateKeywords(keywords)
    }
    
    /**
     * 处理检测到的关键词
     */
    private fun handleKeywordDetected(text: String) {
        logger.info("检测到关键词: \"$text\"")
        _detectorState.value = KeywordDetectorApi.DetectorState.DETECTED
        // 触发所有回调
        callbacks.forEach { callback ->
            try {
                logger.info("执行关键词检测回调: $text")
                callback(text)
            } catch (e: Exception) {
                logger.error("执行关键词回调时发生异常: ${e.message}")
            }
        }
    }
    
    /**
     * 添加关键词检测回调
     * @param callback 要添加的回调函数
     */
    fun addCallback(callback: (String) -> Unit) {
        callbacks.add(callback)
        logger.info("添加关键词检测回调，当前回调数: ${callbacks.size}")
    }
    
    /**
     * 移除关键词检测回调
     * @param callback 要移除的回调函数
     */
    fun removeCallback(callback: (String) -> Unit) {
        // removeIf在Kotlin/Native中不可用，手动实现
        val iterator = callbacks.iterator()
        var removed = false
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item == callback) {
                iterator.remove()
                removed = true
            }
        }
        
        if (removed) {
            logger.info("移除关键词检测回调，当前回调数: ${callbacks.size}")
        }
    }
    
    /**
     * 生成诊断报告
     * @return 诊断信息字符串
     */
    fun generateDiagnostics(): String {
        val sb = StringBuilder()
        sb.appendLine("=== 关键词检测器诊断报告 ===")
        sb.appendLine("检测器状态: ${_detectorState.value}")
        
        // 添加音频处理管理器状态
        if (audioManager != null) {
            sb.appendLine("音频处理管理器: 已初始化")
            sb.appendLine("处理管理器诊断:")
            sb.appendLine(audioManager?.generateDiagnosticReport() ?: "未能获取诊断信息")
        } else {
            sb.appendLine("音频处理管理器: 未初始化")
        }
        
        return sb.toString()
    }
    
    /**
     * 释放资源
     */
    override fun release() {
        logger.info("释放KeywordDetector资源")
        audioManager?.release()
        audioManager = null
        callbacks.clear()
        _detectorState.value = KeywordDetectorApi.DetectorState.IDLE
    }
    
    /**
     * 直接检测给定的音频数据是否包含关键词
     * @param audioData 音频数据
     * @return 是否检测到关键词
     */
    fun detect(audioData: ShortArray): Boolean {
        try {
            // 如果不在监听状态，直接返回false
            if (_detectorState.value != KeywordDetectorApi.DetectorState.LISTENING) {
                return false
            }
            
            // 计算音频能量，用于诊断
            val energy = calculateEnergy(audioData)
            
            // 每50帧记录一次音频能量信息
            if (detectCallCount++ % 50 == 0) {
                logger.info("KeywordDetector: 帧#$detectCallCount, 能量=$energy, 状态=${_detectorState.value}")
            }
            
            // 只有在音频能量超过阈值时才进行进一步处理
            if (energy < 500.0) {
                return false
            }
            
            // 记录具有足够能量的音频
            logger.info("KeywordDetector: 检测到高能量音频! 能量=$energy, 长度=${audioData.size}")
            
            // 检查当前状态，确认是否已经检测到关键词
            try {
                if (audioManager == null) {
                    logger.warn("音频处理管理器未初始化")
                    return false
                }
                
                logger.info("KeywordDetector: 将音频发送给AudioProcessingManager进行关键词检测...")
                
                // 直接传递ShortArray到audioManager的processAudio方法
                val result = audioManager?.processAudio(audioData) ?: false
                
                if (result) {
                    // 检测到关键词
                    logger.info("KeywordDetector: 成功检测到关键词!")
                    keywordDetectedCount++
                    
                    // 更新检测状态
                    _detectorState.value = KeywordDetectorApi.DetectorState.DETECTED
                    
                    // 调用回调
                    callbacks.forEach { callback ->
                        try {
                            callback("关键词")
                        } catch (e: Exception) {
                            logger.error("执行关键词回调时发生异常: ${e.message}")
                        }
                    }
                }
                return result
            } catch (e: Exception) {
                logger.error("audioManager.processAudio() 发生异常: ${e.message}")
                return false
            }
        } catch (e: Exception) {
            logger.error("KeywordDetector.detect() 发生异常: ${e.message}")
            return false
        }
    }
    
    /**
     * 计算音频能量值
     */
    private fun calculateEnergy(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        
        var sum = 0.0
        for (sample in samples) {
            sum += (sample * sample).toDouble()
        }
        
        val rms = kotlin.math.sqrt(sum / samples.size)
        return (rms / Short.MAX_VALUE) * 1000.0
    }
} 