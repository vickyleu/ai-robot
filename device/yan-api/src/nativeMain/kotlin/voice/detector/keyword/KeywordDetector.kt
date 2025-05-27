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
        return sb.toString()
    }
} 