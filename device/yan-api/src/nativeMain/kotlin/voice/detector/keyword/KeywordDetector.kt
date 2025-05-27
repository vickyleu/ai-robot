@file:OptIn(ExperimentalForeignApi::class)

package voice.detector.keyword

import com.airobot.core.utils.format
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo
import kotlinx.cinterop.CPointer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.fflush
import voice.api.KeywordDetectorApi
import voice.audio.processing.CallbackAudioProcessor
import voice.util.AudioDefaults
import voice.util.AudioUtils
import voice.util.LogManager

/**
 * 关键词检测器
 * 使用 Vosk 进行关键词检测，通过 WebRTC APM 进行前处理
 */
class KeywordDetector(
    // 使用全局共享的音频处理器
    private val audioProcessor: CallbackAudioProcessor = CallbackAudioProcessor()
) : KeywordDetectorApi {
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
    
    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Default)
    
    // VAD参数 - 使用WebRTC提供的VAD功能
    private val vadDebounceFrames = 5   // 从10降低到5，减少所需的连续帧数
    
    // 音频质量判断参数
    // 当 calculateRmsEnergy 归一化到 0~1 区间后，正常语音 RMS ≈ 0.03~0.3。
    // 设置 0.02 作为下限，过滤极低噪声。
    private val minValidRms = 0.02
    
    // 添加计数器以限制日志
    private var audioReadCounter = 0
    
    // 播放前音频文件写入 - 单个文件
    private var playbackFile: CPointer<platform.posix.FILE>? = null
    private var playbackFileInitialized = false
    
    // 音频累积机制 - 确保有足够长的音频用于识别
    private val audioBuffer = mutableListOf<ShortArray>()
    private var totalAudioSamples = 0
    private val minAudioSamplesFor800ms = (AudioDefaults.Formats.WEBRTC_APM.sampleRate * 2.0).toInt() // 从1.5秒增加到2.0秒，确保足够长的音频
    
    // 连续性检测 - 避免把间隔很久的音频当成一句话
    private var lastAudioTime = 0L
    private val maxSilenceGapMs = 1500L // 从1000ms增加到1500ms，减少因短暂静音导致的重置
    private var consecutiveAudioFrames = 0
    private val minConsecutiveFrames = 3 // 从2增加到3，确保音频连续性
    
    // Vosk处理保护 - 避免频繁调用导致内存崩溃
    private var lastVoskProcessTime = 0L
    private val minVoskProcessIntervalMs = 3000L // 从2秒增加到3秒，进一步减少处理频率
    
    /**
     * 获取全局使用的音频处理器实例
     */
    fun getAudioProcessor(): CallbackAudioProcessor = audioProcessor
    
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
        
        // 确保音频处理器已初始化 - 但不自行管理，依赖外部传入或默认构造
        // 只配置回调
        
        // 配置音频处理器回调
        audioProcessor.setProcessedAudioCallback { processedData, size ->
            // 使用Vosk检测处理后的音频
            if (isListening && size > 0) {
                val currentTime = Clock.System.now().toEpochMilliseconds()
                
                // 减少日志频率，避免过多输出
                if (audioReadCounter++ % 500 == 0) {
                    logger.debug("处理音频回调: 数据大小=$size, 前5个样本=${processedData.take(5).joinToString(",")}")
                }
                
                // 检查连续性：如果距离上次音频超过最大静音间隔，则重置累积
                if (lastAudioTime > 0 && (currentTime - lastAudioTime) > maxSilenceGapMs) {
                    if (audioBuffer.isNotEmpty()) {
                        logger.debug("检测到静音间隔${currentTime - lastAudioTime}ms > ${maxSilenceGapMs}ms，重置音频累积")
                        audioBuffer.clear()
                        totalAudioSamples = 0
                        consecutiveAudioFrames = 0
                    }
                }
                
                lastAudioTime = currentTime
                consecutiveAudioFrames++
                
                // 只有连续帧数足够时才开始累积
                if (consecutiveAudioFrames >= minConsecutiveFrames) {
                    // 累积音频数据
                    audioBuffer.add(processedData.copyOf())
                    totalAudioSamples += processedData.size
                    
                    // 检查是否累积了足够的音频（至少800ms）
                    if (totalAudioSamples >= minAudioSamplesFor800ms) {
                        // 合并所有累积的音频数据
                        val combinedAudio = ShortArray(totalAudioSamples)
                        var offset = 0
                        for (chunk in audioBuffer) {
                            chunk.copyInto(combinedAudio, offset)
                            offset += chunk.size
                        }
                        
                        val combinedDurationMs = totalAudioSamples * 1000 / AudioDefaults.Formats.WEBRTC_APM.sampleRate
                        logger.info("累积完成，开始处理: ${totalAudioSamples}样本, 时长${combinedDurationMs}ms, 连续帧数${consecutiveAudioFrames}")
                        
                        // 检查Vosk处理间隔，避免频繁调用导致内存崩溃
                        if (currentTime - lastVoskProcessTime < minVoskProcessIntervalMs) {
                            logger.debug("Vosk处理间隔太短，跳过本次处理: ${currentTime - lastVoskProcessTime}ms < ${minVoskProcessIntervalMs}ms")
                            // 清空缓冲区，准备下一轮累积
                            audioBuffer.clear()
                            totalAudioSamples = 0
                            consecutiveAudioFrames = 0
                            return@setProcessedAudioCallback
                        }
                        lastVoskProcessTime = currentTime
                        
                        // 检测关键词 - 使用累积的音频数据
                        voskDetector.detect(combinedAudio)
                        
                        // 播放确认：将累积的音频正确重采样到播放设备格式
                        // 根据配置决定是否启用播放确认功能
                        if (AudioDefaults.ENABLE_PLAYBACK_CONFIRMATION) {
                            val apm = audioProcessor.getApm()
                            if (apm != null) {
                                try {
                                    // 修复参数传递问题：APM处理后应该输出到播放设备格式
                                    // 但要注意APM内部是单声道处理，需要在APM内部进行格式转换
                                    val processedData = apm.processAndResample(
                                        audioData = combinedAudio,
                                        outputSampleRate = AudioDefaults.Formats.OUTPUT_DEVICE.sampleRate,  // 48000Hz
                                        outputChannels = AudioDefaults.Formats.OUTPUT_DEVICE.channels       // 2ch (APM内部会处理1ch->2ch转换)
                                    )
                                    
                                    if (processedData.isNotEmpty()) {
                                        // 检查重采样结果质量
                                        val resampledMaxAmp = processedData.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
                                        val nonZeroCount = processedData.count { it != 0.toShort() }
                                        val zeroRatio = (processedData.size - nonZeroCount).toFloat() / processedData.size
                                        
                                        logger.debug("播放确认音频处理完成: 重采样后最大振幅=$resampledMaxAmp, 非零样本=${nonZeroCount}/${processedData.size}, 零值比例=${"%.4f".format( zeroRatio)}")
                                        
                                        // 验证音频质量 - 改进检测逻辑
                                        if (resampledMaxAmp == 0) {
                                            logger.error("重采样后音频全为0，跳过播放")
                                        } else if (zeroRatio > 0.95f) {
                                            logger.error("重采样后零值过多(${"%.4f".format(zeroRatio)})，可能存在严重问题")
                                            
                                            // 添加详细的输入数据分析
                                            val inputMaxAmp = combinedAudio.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
                                            val inputNonZeroCount = combinedAudio.count { it != 0.toShort() }
                                            val inputZeroRatio = (combinedAudio.size - inputNonZeroCount).toFloat() / combinedAudio.size
                                            logger.error("输入数据分析: 最大振幅=$inputMaxAmp, 非零样本=${inputNonZeroCount}/${combinedAudio.size}, 零值比例=${"%.4f".format( inputZeroRatio)}")
                                            
                                            if (inputZeroRatio < 0.5f && zeroRatio > 0.95f) {
                                                logger.error("检测到数据处理错误：输入有效数据但输出几乎全为零！")
                                            }
                                        } else {
                                            logger.debug("重采样质量检查通过，准备播放")
                                        }
                                        
                                        // 验证音频质量
                                        if (resampledMaxAmp == 0) {
                                            logger.warn("重采样后音频全为0，跳过播放")
                                        } else if (zeroRatio > 0.9f) {
                                            logger.warn("重采样后零值过多(${zeroRatio})，可能存在问题")
                                        } else {
                                            // 音频质量正常，进行播放
                                            val audioBytes = AudioUtils.shortArrayToByteArray(processedData)
                                            
                                            // 写入播放前的音频文件用于调试
                                            try {
                                                if (!playbackFileInitialized) {
                                                    val filename = "/tmp/playback_audio.raw"
                                                    playbackFile = fopen(filename, "ab")
                                                    if (playbackFile != null) {
                                                        playbackFileInitialized = true
                                                        logger.info("播放前音频文件已创建(追加模式): $filename")
                                                        logger.info("播放命令: aplay -f S16_LE -r ${AudioDefaults.Formats.OUTPUT_DEVICE.sampleRate} -c ${AudioDefaults.Formats.OUTPUT_DEVICE.channels} $filename")
                                                    } else {
                                                        logger.error("无法创建播放前音频文件")
                                                    }
                                                }
                                                
                                                playbackFile?.let { file ->
                                                    val bytesWritten = fwrite(audioBytes.refTo(0), 1u, audioBytes.size.toUInt(), file)
                                                    fflush(file)
                                                    
                                                    val durationMs = (audioBytes.size / 2 / AudioDefaults.Formats.OUTPUT_DEVICE.channels * 1000) / AudioDefaults.Formats.OUTPUT_DEVICE.sampleRate
                                                    logger.info("写入播放前音频: ${bytesWritten}字节, 播放时长约${durationMs}ms, 原始累积时长${combinedDurationMs}ms")
                                                }
                                            } catch (e: Exception) {
                                                logger.error("写入播放前音频文件失败: ${e.message}")
                                            }
                                            
                                            // 播放音频
                                            val success = audioProcessor.audioDevice.play(audioBytes, audioBytes.size)
                                            if (!success) {
                                                logger.warn("音频播放失败")
                                            } else {
                                                logger.debug("播放确认音频成功: ${audioBytes.size}字节")
                                            }
                                        }
                                    } else {
                                        logger.warn("重采样后音频数据为空")
                                    }
                                } catch (e: Exception) {
                                    logger.error("音频重采样播放失败: ${e.message}")
                                    // 发生异常时，尝试简单的播放方式
                                    try {
                                        // 正确的简单格式转换：16kHz/1ch -> 48kHz/2ch
                                        // 步骤1: 采样率从16kHz提升到48kHz (3倍)
                                        val upsampled = ShortArray(combinedAudio.size * 3) { i ->
                                            combinedAudio[i / 3]  // 每个样本重复3次实现3倍上采样
                                        }
                                        
                                        // 步骤2: 单声道转双声道 (2倍)
                                        val stereoConverted = ShortArray(upsampled.size * 2) { i ->
                                            upsampled[i / 2]  // 左右声道相同
                                        }
                                        
                                        val audioBytes = AudioUtils.shortArrayToByteArray(stereoConverted)
                                        audioProcessor.audioDevice.play(audioBytes, audioBytes.size)
                                        logger.info("使用简单重采样播放音频: ${combinedAudio.size} -> ${stereoConverted.size}样本 (16kHz/1ch -> 48kHz/2ch)")
                                    } catch (fallbackE: Exception) {
                                        logger.error("简单重采样播放也失败: ${fallbackE.message}")
                                    }
                                }
                            }
                        } else {
                            logger.debug("播放确认功能已禁用，跳过播放")
                        }
                        
                        // 清空缓冲区，准备下一轮累积
                        audioBuffer.clear()
                        totalAudioSamples = 0
                        consecutiveAudioFrames = 0
                        logger.debug("音频缓冲区已清空，开始新一轮累积")
                    } else {
                        // 还没有足够的音频，继续累积
                        val currentDurationMs = totalAudioSamples * 1000 / AudioDefaults.Formats.WEBRTC_APM.sampleRate
                        if (audioReadCounter % 100 == 0) {
                            logger.debug("累积连续音频中: ${totalAudioSamples}样本, 时长${currentDurationMs}ms / 800ms, 连续帧${consecutiveAudioFrames}")
                        }
                    }
                } else {
                    // 连续帧数不够，继续等待
                    if (audioReadCounter % 200 == 0) {
                        logger.debug("等待连续音频: 当前连续帧${consecutiveAudioFrames} / ${minConsecutiveFrames}")
                    }
                }
            }
        }
        
        // 配置VAD回调
        audioProcessor.setVadCallback { hasVoice ->
            // 可选：处理VAD状态变化
        }
        
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
        
        // 使用回调式处理器启动音频处理
        val success = audioProcessor.startProcessing()
        if (!success) {
            logger.error("启动音频处理器失败")
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
        
        // 停止音频处理器
        audioProcessor.stopProcessing()

        isListening = false
        _detectorState.value = KeywordDetectorApi.DetectorState.IDLE
        logger.info("关键词检测器已停止监听")
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
        
        // 关闭播放前音频文件
        playbackFile?.let {
            try {
                fclose(it)
                logger.info("播放前音频文件已关闭")
            } catch (e: Exception) {
                logger.error("关闭播放前音频文件失败: ${e.message}")
            }
            playbackFile = null
            playbackFileInitialized = false
        }
        
        // 清理音频缓冲区
        audioBuffer.clear()
        totalAudioSamples = 0
        
        // 重置连续性检测
        lastAudioTime = 0L
        consecutiveAudioFrames = 0
        
        // 重置Vosk处理保护
        lastVoskProcessTime = 0L
        
        if (isInitialized) {
            voskDetector.release()
            // 不再释放audioProcessor，因为它可能被其他组件共享使用
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
        
        // 添加APM诊断信息
        sb.appendLine()
        sb.appendLine("===== APM处理器诊断 =====")
        val apm = audioProcessor.getApm()
        if (apm != null) {
            try {
                sb.appendLine(apm.generateDiagnosticReport())
            } catch (e: Exception) {
                sb.appendLine("获取APM诊断报告失败: ${e.message}")
            }
        } else {
            sb.appendLine("APM实例不可用")
        }
        
        return sb.toString()
    }

    /**
     * 获取APM详细诊断报告
     */
    fun getApmDiagnosticReport(): String {
        val apm = audioProcessor.getApm()
        return apm?.generateDiagnosticReport() ?: "APM实例不可用"
    }

    /**
     * 动态调节APM参数的便捷方法（更新版本）
     */
    
    // 调节噪声抑制级别
    fun adjustNoiseSuppressionLevel(level: com.airobot.webrtcapminterop.APMNsLevel) {
        val apm = audioProcessor.getApm()
        apm?.setNoiseSuppressionLevel(level)
        logger.info("调节噪声抑制级别: $level")
    }
    
    // 调节前置放大器增益
    fun adjustPreAmplifierGain(gainFactor: Float) {
        val apm = audioProcessor.getApm()
        apm?.setPreAmplifierGain(gainFactor)
        logger.info("调节前置放大器增益: $gainFactor")
    }
    
    // 调节模拟电平
    fun adjustAnalogLevel(level: Int) {
        val apm = audioProcessor.getApm()
        apm?.setStreamAnalogLevel(level)
        logger.info("调节模拟电平: $level")
    }
    
    // 获取当前模拟电平
    fun getCurrentAnalogLevel(): Int {
        val apm = audioProcessor.getApm()
        return apm?.getStreamAnalogLevel() ?: 0
    }
    
    // 启用APM调试录制
    fun enableApmDebugRecording(filePath: String = "/tmp/apm_debug_${Clock.System.now().toEpochMilliseconds()}.wav"): Boolean {
        val apm = audioProcessor.getApm()
        return apm?.enableDebugRecording(filePath) ?: false
    }
    
    // 禁用APM调试录制
    fun disableApmDebugRecording() {
        val apm = audioProcessor.getApm()
        apm?.disableDebugRecording()
    }
    
    // 应用APM预设模式
    fun applyApmPreset(mode: com.airobot.webrtcapminterop.APMPresetMode): Boolean {
        val apm = audioProcessor.getApm()
        return apm?.applyPresetMode(mode) ?: false
    }
    
    // 重置APM统计信息
    fun resetApmStatistics() {
        val apm = audioProcessor.getApm()
        apm?.resetStatistics()
        logger.info("APM统计信息已重置")
    }

    // === 高级功能接口（修复版本） ===

    /**
     * 获取扩展APM统计信息
     */
    fun getExtendedApmStatistics(): String {
        val apm = audioProcessor.getApm()
        return apm?.getExtendedStatistics() ?: "APM实例不可用"
    }

    /**
     * 评估音频质量
     */
    fun assessAudioQuality(): String? {
        val apm = audioProcessor.getApm()
        return apm?.assessAudioQuality()
    }

    /**
     * 分析当前音频流
     */
    fun analyzeCurrentAudioStream(): String? {
        val apm = audioProcessor.getApm()
        // 需要具体的音频数据实现 - 暂时返回null
        return null
    }

    /**
     * 检测当前环境是否适合唤醒词检测
     */
    fun isWakeWordEnvironmentGood(): Boolean {
        val apm = audioProcessor.getApm()
        return apm?.detectWakeWordEnvironment() ?: false
    }

    /**
     * 获取语音清晰度评分
     */
    fun getCurrentSpeechClarityScore(): Float {
        val apm = audioProcessor.getApm()
        return apm?.getSpeechClarityScore() ?: 0.0f
    }

    /**
     * 检测是否有双讲情况
     */
    fun detectDoubleTalk(): Boolean {
        val apm = audioProcessor.getApm()
        return apm?.detectDoubleTalk() ?: false
    }

    /**
     * 估计当前环境的混响时间
     */
    fun estimateReverberationTime(): Float {
        val apm = audioProcessor.getApm()
        return apm?.estimateReverberationTime() ?: 0.0f
    }

    /**
     * 获取频率响应数据
     */
    fun getFrequencyResponse(numBins: Int = 256): Pair<FloatArray, FloatArray>? {
        val apm = audioProcessor.getApm()
        return apm?.getFrequencyResponse(numBins)
    }

    /**
     * 设置APM运行时参数
     */
    fun setApmRuntimeSetting(type: com.airobot.webrtcapminterop.APMRuntimeSettingType, value: Float) {
        val apm = audioProcessor.getApm()
        apm?.setRuntimeSetting(type, value)
        logger.info("设置APM运行时参数: type=$type, value=$value")
    }

    /**
     * 获取线性AEC输出
     */
    fun getLinearAecOutput(): FloatArray? {
        val apm = audioProcessor.getApm()
        return apm?.getLinearAecOutput()
    }

    /**
     * 动态更新APM配置
     */
    fun updateApmConfigurationRuntime(configJson: String): Boolean {
        val apm = audioProcessor.getApm()
        return apm?.updateConfigurationRuntime(configJson) ?: false
    }

    /**
     * 导出当前APM配置
     */
    fun exportApmConfiguration(): String? {
        val apm = audioProcessor.getApm()
        return apm?.exportConfigurationJson()
    }

    /**
     * 获取APM错误状态
     */
    fun getApmErrorStatus(): String {
        val apm = audioProcessor.getApm()
        return if (apm != null) {
            try {
                val errorCode = apm.getLastErrorCode()
                if (errorCode != null) {
                    val errorString = apm.getErrorString(errorCode)
                    "错误码: $errorCode, 描述: ${errorString ?: "未知错误"}"
                } else {
                    "无错误"
                }
            } catch (e: Exception) {
                "错误状态检查失败: ${e.message}"
            }
        } else {
            "APM实例不可用"
        }
    }
    
    // 获取详细的APM统计信息（简化版）
    fun getApmStatistics(): String {
        val apm = audioProcessor.getApm()
        return if (apm != null) {
            try {
                buildString {
                    appendLine("=== APM基本统计 ===")
                    val analogLevel = apm.getStreamAnalogLevel()
                    appendLine("模拟电平: $analogLevel")
                    
                    // 语音助手专用指标（如果可用）
                    try {
                        val wakeWordEnv = apm.detectWakeWordEnvironment()
                        val clarityScore = apm.getSpeechClarityScore()
                        appendLine("唤醒词环境: ${if (wakeWordEnv) "适合" else "不适合"}")
                        appendLine("语音清晰度: ${"%.3f".format(clarityScore)}")
                    } catch (e: Exception) {
                        appendLine("高级指标: 不可用")
                    }
                    
                    appendLine("错误状态: 正常")
                }
            } catch (e: Exception) {
                "获取APM统计信息失败: ${e.message}"
            }
        } else {
            "APM实例不可用"
        }
    }
    
    /**
     * 智能APM参数自动调节（简化版）
     */
    fun autoOptimizeApmParameters(): String {
        val apm = audioProcessor.getApm()
        if (apm == null) {
            return "APM实例不可用"
        }
        
        return try {
            val adjustments = mutableListOf<String>()
            
            // 基础参数调节
            val analogLevel = apm.getStreamAnalogLevel()
            when {
                analogLevel > 200 -> {
                    val newLevel = (analogLevel * 0.8).toInt().coerceAtLeast(50)
                    apm.setStreamAnalogLevel(newLevel)
                    adjustments.add("模拟电平过高，降低: $analogLevel -> $newLevel")
                }
                analogLevel < 50 -> {
                    val newLevel = (analogLevel * 1.2).toInt().coerceAtMost(200)
                    apm.setStreamAnalogLevel(newLevel)
                    adjustments.add("模拟电平过低，提高: $analogLevel -> $newLevel")
                }
                else -> {
                    adjustments.add("模拟电平正常: $analogLevel")
                }
            }
            
            // 语音助手环境优化（如果可用）
            try {
                val wakeWordEnv = apm.detectWakeWordEnvironment()
                if (!wakeWordEnv) {
                    // 优化噪声抑制
                    apm.setNoiseSuppressionLevel(com.airobot.webrtcapminterop.kNsVeryHigh)
                    adjustments.add("环境不适合唤醒词，增强噪声抑制")
                    
                    // 增加前置放大
                    apm.setPreAmplifierGain(1.5f)
                    adjustments.add("提高前置放大器增益到1.5x")
                }
            } catch (e: Exception) {
                adjustments.add("语音助手环境检测不可用")
            }
            
            // 语音清晰度优化（如果可用）
            try {
                val clarityScore = apm.getSpeechClarityScore()
                if (clarityScore < 0.5f) {
                    // 应用语音助手预设
                    if (apm.applyPresetMode(com.airobot.webrtcapminterop.APM_PRESET_VOICE_ASSISTANT)) {
                        adjustments.add("语音清晰度低，应用语音助手预设模式")
                    }
                }
            } catch (e: Exception) {
                adjustments.add("语音清晰度检测不可用")
            }
            
            if (adjustments.isEmpty()) {
                "APM参数已是最优，无需调节"
            } else {
                "智能优化完成:\n${adjustments.joinToString("\n")}"
            }
            
        } catch (e: Exception) {
            "智能优化失败: ${e.message}"
        }
    }

    /**
     * 生成完整的音频处理链诊断报告（简化版）
     */
    fun generateCompleteAudioDiagnostics(): String {
        return buildString {
            appendLine("===== 完整音频处理链诊断报告 =====")
            appendLine("时间: ${Clock.System.now()}")
            appendLine()
            
            // 关键词检测器状态
            appendLine("=== 关键词检测器状态 ===")
            appendLine("初始化状态: $isInitialized")
            appendLine("监听状态: $isListening")
            appendLine("检测器状态: ${detectorState.value}")
            appendLine("敏感度: $sensitivity")
            appendLine("关键词列表: ${keywords.joinToString(", ")}")
            appendLine()
            
            // 音频累积状态
            appendLine("=== 音频累积状态 ===")
            appendLine("当前累积样本数: $totalAudioSamples")
            appendLine("目标样本数: $minAudioSamplesFor800ms")
            appendLine("缓冲区块数: ${audioBuffer.size}")
            appendLine("连续音频帧数: $consecutiveAudioFrames")
            appendLine("最小连续帧数: $minConsecutiveFrames")
            appendLine("上次音频时间: $lastAudioTime")
            appendLine("上次Vosk处理时间: $lastVoskProcessTime")
            appendLine()
            
            // APM诊断信息（使用简化版本）
            val apm = audioProcessor.getApm()
            if (apm != null) {
                try {
                    appendLine(apm.generateDiagnosticReport())
                } catch (e: Exception) {
                    appendLine("=== APM诊断失败 ===")
                    appendLine("错误: ${e.message}")
                }
            } else {
                appendLine("=== APM不可用 ===")
                appendLine("音频处理器未提供APM实例")
            }
            
            appendLine()
            appendLine("=== 智能建议 ===")
            
            // 环境适应性建议（简化版）
            if (apm != null) {
                try {
                    val wakeWordEnv = apm.detectWakeWordEnvironment()
                    val clarityScore = apm.getSpeechClarityScore()
                    
                    if (!wakeWordEnv) {
                        appendLine("⚠️ 当前环境不太适合关键词检测")
                        appendLine("   建议: 1) 检查背景噪声; 2) 调整麦克风位置; 3) 考虑使用外接麦克风")
                    }
                    
                    if (clarityScore < 0.5f) {
                        appendLine("⚠️ 语音清晰度较低")
                        appendLine("   建议: 1) 增强噪声抑制; 2) 调整前置放大器; 3) 检查麦克风质量")
                    }
                } catch (e: Exception) {
                    appendLine("智能建议生成失败: ${e.message}")
                }
            }
            
            // 累积参数建议
            if (totalAudioSamples < minAudioSamplesFor800ms / 2) {
                appendLine("ℹ️ 音频累积不足，可能影响识别准确率")
                appendLine("   建议: 1) 延长说话时间; 2) 确保连续发音; 3) 避免长时间停顿")
            }
            
            if (consecutiveAudioFrames < minConsecutiveFrames) {
                appendLine("ℹ️ 音频连续性不佳")
                appendLine("   建议: 1) 保持连续发音; 2) 检查麦克风连接; 3) 避免间断性输入")
            }
            
            appendLine()
            appendLine("=== 性能指标 ===")
            appendLine("音频累积效率: ${if (totalAudioSamples > 0) (totalAudioSamples.toFloat() / minAudioSamplesFor800ms * 100).toInt() else 0}%")
            appendLine("帧连续性: ${if (consecutiveAudioFrames >= minConsecutiveFrames) "良好" else "需改善"}")
            
            if (apm != null) {
                try {
                    val clarityScore = apm.getSpeechClarityScore()
                    val wakeWordEnv = apm.detectWakeWordEnvironment()
                    
                    appendLine("语音清晰度: ${(clarityScore * 100).toInt()}%")
                    appendLine("环境适应性: ${if (wakeWordEnv) "良好" else "需优化"}")
                } catch (e: Exception) {
                    appendLine("性能指标计算失败: ${e.message}")
                }
            }
        }
    }
} 