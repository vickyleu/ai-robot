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
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    
    // 🔧 新增：智能静音检测机制
    private var silenceDetectionJob: kotlinx.coroutines.Job? = null
    private val silenceThresholdMs = 800L  // 静音阈值：800ms
    private var lastValidVoiceTime = 0L    // 最后一次检测到有效语音的时间
    
    // VAD参数 - 使用WebRTC提供的VAD功能
    private val vadDebounceFrames = 5   // 从10降低到5，减少所需的连续帧数

    // 🔧 根据专业指南更新：环境噪音基线自适应检测
    private var environmentNoiseBaseline = 0.0f
    private var noiseCalibrationFrames = 0
    private val maxCalibrationFrames = 50  // 前50帧用于噪音基线校准

    // 🔧 根据专业指南新增：双时间常数积分器
    private var fastNoiseEstimate = 0.0f   // 快速噪声估计
    private var slowNoiseEstimate = 0.0f   // 慢速噪声估计
    
    // 音频质量判断参数 - 🔧 根据专业指南更新
    private val minValidRms = AudioDefaults.minValidRms  // 🔧 使用配置的专业阈值
    
    // 添加计数器以限制日志
    private var audioReadCounter = 0
    
    // 播放前音频文件写入 - 单个文件
    private var playbackFile: CPointer<platform.posix.FILE>? = null
    private var playbackFileInitialized = false
    
    // 音频累积机制 - 确保有足够长的音频用于识别
    // 添加同步保护
    private val audioBufferMutex = Mutex()
    private val audioBuffer = mutableListOf<ShortArray>()
    private var totalAudioSamples = 0
    private val minAudioSamplesFor400ms = (AudioDefaults.Formats.WEBRTC_APM.sampleRate * 0.2).toInt() // 🔧 从0.1秒增加到0.2秒，要求更长的音频才触发识别
    
    // 原始音频数据存储用于播放确认
    // 添加同步保护
    private val rawAudioBufferMutex = Mutex()
    private val rawAudioBuffer = mutableListOf<ShortArray>()
    private var totalRawAudioSamples = 0
    
    // 连续性检测 - 避免把间隔很久的音频当成一句话
    private var lastAudioTime = 0L
    private val maxSilenceGapMs = 3000L                  // 🔧 从5秒减少到3秒，更快重置累积状态
    private var consecutiveAudioFrames = 0
    private val minConsecutiveFrames = 3  // 🔧 从1增加到3帧，要求更稳定的连续语音才开始累积
    
    // 语音活动状态检测 - 基于连续说话来判断语音周期
    private var firstAudioTime = 0L // 记录第一个音频帧的时间
    private var lastVoiceActivityTime = 0L // 记录最后一次检测到语音活动的时间
    private var isInVoiceActivity = false // 当前是否处于语音活动状态
    private val voiceActivityEndThresholdMs = 1000L // 🔧 从800ms增加到1000ms，减少过早结束语音活动
    
    // Vosk处理保护 - 避免频繁调用导致内存崩溃
    private var lastVoskProcessTime = 0L
    private val minVoskProcessIntervalMs = 500L // 🔧 从200ms增加到500ms，减少频繁处理
    
    // === 第三方处理器专用参数 ===
    private val thirdPartyMaxAccumulationMs = 10000L      // 第三方处理器最大累积时长：10秒，防止内存溢出
    
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
        
        // 设置原始音频回调 - 只在有语音活动时收集原始音频数据
        audioProcessor.setRawAudioCallback { rawData, frameCount ->
            scope.launch {
                // 🔧 修复：只在有语音活动时才累积原始音频
                if (isInVoiceActivity) {
                    rawAudioBufferMutex.withLock {
                        rawAudioBuffer.add(rawData.copyOf())
                        totalRawAudioSamples += rawData.size
                        
                        // 调试：保存原始音频到WAV（只保存前3秒，避免刷盘过多）
                        // 🔧 暂时禁用调试功能，减少日志输出
                        /*
                        val maxRawSamples = AudioDefaults.Formats.WEBRTC_APM.sampleRate * 3
                        if (totalRawAudioSamples <= maxRawSamples) {
                            val filePath = "/tmp/raw_audio_debug.wav"
                            val allRaw = rawAudioBuffer.flatMap { it.asIterable() }.toShortArray()
                            voice.util.AudioUtils.saveShortArrayAsWav(allRaw, AudioDefaults.Formats.WEBRTC_APM.sampleRate, AudioDefaults.Formats.WEBRTC_APM.channels, filePath)
                            logger.info("[调试] 已保存原始音频到: $filePath, 样本数=${allRaw.size}")
                        }
                        */
                        
                        // 内存保护逻辑
                        val maxRawSamples2 = AudioDefaults.Formats.WEBRTC_APM.sampleRate * 2
                        while (totalRawAudioSamples > maxRawSamples2 && rawAudioBuffer.isNotEmpty()) {
                            val removedChunk = rawAudioBuffer.removeFirst()
                            totalRawAudioSamples -= removedChunk.size
                        }
                    }
                }
            }
        }
        
        // 配置音频处理器回调
        audioProcessor.setProcessedAudioCallback { processedData, size ->
            // 🔧 调试：确认KeywordDetector收到回调
            if (audioReadCounter % 10 == 0) {
                logger.debug("🎯 KeywordDetector收到音频回调: size=$size, isListening=$isListening")
            }
            
            // 使用协程处理音频数据，避免阻塞音频线程
            scope.launch {
                // 使用Vosk检测处理后的音频
                if (isListening && size > 0) {
                    val currentTime = Clock.System.now().toEpochMilliseconds()
                    
                    // 处理音频数据
                    val processedAudioData = processedData.copyOf()
                    
                    // 减少日志频率，避免过多输出
                    if (AudioDefaults.ENABLE_DEBUG_LOGS && audioReadCounter++ % 100 == 0) {
                        // 记录所有音频数据，不限制振幅
                        val maxAmp = processedAudioData.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
                        logger.debug("KeywordDetector处理音频回调: 数据大小=$size, 最大振幅=$maxAmp")
                    }
                    
                    // 检查连续性：如果距离上次音频超过最大静音间隔，则重置累积
                    if (lastAudioTime > 0 && (currentTime - lastAudioTime) > maxSilenceGapMs) {
                        audioBufferMutex.withLock {
                            if (audioBuffer.isNotEmpty()) {
                                logger.debug("检测到静音间隔${currentTime - lastAudioTime}ms > ${maxSilenceGapMs}ms，重置语音活动")
                                audioBuffer.clear()
                                totalAudioSamples = 0
                                consecutiveAudioFrames = 0
                                firstAudioTime = 0L
                                lastVoiceActivityTime = 0L
                                isInVoiceActivity = false
                            }
                        }
                        rawAudioBufferMutex.withLock {
                            rawAudioBuffer.clear()
                            totalRawAudioSamples = 0
                        }
                    }
                    
                    lastAudioTime = currentTime
                    
                    // 🔧 关键修复：完全信任CallbackAudioProcessor的VAD结果
                    // CallbackAudioProcessor已经做了完整的检测（SpeexDSP VAD + 能量检测 + 连续帧检测）
                    // KeywordDetector只需要根据VAD回调来累积音频，不需要重复检测
                    val vadDetected = audioProcessor.isVoiceDetected()
                    
                    // 🔧 简化：只计算基本信息用于调试，不用于判断
                    val rmsEnergy = sqrt(processedAudioData.map { it.toFloat() * it.toFloat() }.average()).toFloat()
                    val normalizedRms = rmsEnergy / 32768.0f
                    val maxAmp = processedAudioData.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
                    
                    // 🔧 新增：使用动态噪音基线检测
                    val isDynamicSilent = isDynamicSilence(normalizedRms)
                    
                    // 🔧 关键修复：不要让动态静音检测干扰VAD结果
                    // 如果CallbackAudioProcessor的VAD说是语音，就直接信任，不要被动态静音覆盖
                    val hasValidVoice = vadDetected  // 🔧 简化：完全信任VAD结果
                    
                    val currentTime2 = Clock.System.now().toEpochMilliseconds()
                    
                    // 🔧 调试：显示简化的检测信息
                    if (audioReadCounter % 10 == 0) {  // 每10帧显示一次关键信息
                        logger.debug("🎯 KeywordDetector: VAD=$vadDetected, RMS=${"%.4f".format(normalizedRms)}, 振幅=$maxAmp, 最终=$hasValidVoice")
                    }
                    
                    if (hasValidVoice) {
                        // 检测到有效语音
                        lastValidVoiceTime = currentTime2
                        
                        // 取消之前的静音检测Job
                        silenceDetectionJob?.cancel()
                        silenceDetectionJob = null
                        
                        logger.debug("🎯 检测到语音，开始累积音频")
                    } else {
                        // 检测到静音或能量不足
                        if (isInVoiceActivity) {
                            // 🔧 不再使用激进的动态静音重置，只依赖VAD回调的静音检测
                            if (silenceDetectionJob == null) {
                                logger.debug("启动静音检测Job: VAD=$vadDetected, RMS=${"%.4f".format(normalizedRms)}")
                                
                                silenceDetectionJob = scope.launch {
                                    try {
                                        kotlinx.coroutines.delay(silenceThresholdMs)
                                        
                                        // 延迟后再次检查是否仍然静音且没有语音活动
                                        if (!isInVoiceActivity) {
                                            // 如果语音活动已经结束（可能被VAD回调处理了），不需要重置
                                            logger.debug("静音检测Job完成: 语音活动已结束，无需重置")
                                        } else {
                                            // 检查最近是否有语音活动
                                            val silenceDuration = Clock.System.now().toEpochMilliseconds() - lastValidVoiceTime
                                            if (silenceDuration >= silenceThresholdMs) {
                                                logger.info("静音检测Job触发: 静音时长=${silenceDuration}ms, 重置语音活动状态")
                                                
                                                // 重置语音活动状态
                                                isInVoiceActivity = false
                                                audioBufferMutex.withLock {
                                                    audioBuffer.clear()
                                                    totalAudioSamples = 0
                                                }
                                                rawAudioBufferMutex.withLock {
                                                    rawAudioBuffer.clear()
                                                    totalRawAudioSamples = 0
                                                }
                                            } else {
                                                logger.debug("静音检测Job取消: 检测到最近有语音活动，静音时长=${silenceDuration}ms < ${silenceThresholdMs}ms")
                                            }
                                        }
                                        
                                        silenceDetectionJob = null
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        logger.debug("静音检测Job被取消: 检测到新的语音活动")
                                        silenceDetectionJob = null
                                    }
                                }
                            }
                        }
                    }
                    
                    // 调试日志：显示检测结果
                    if (AudioDefaults.ENABLE_DEBUG_LOGS && audioReadCounter % AudioDefaults.LOG_INTERVAL_FRAMES == 0) {
                        val silenceJobStatus = if (silenceDetectionJob != null) "运行中" else "无"
                        val strategy = if (hasValidVoice) "VAD检测通过" else "VAD静音"
                        logger.debug("KeywordDetector语音检测: 策略=[$strategy], VAD=$vadDetected, RMS=${"%.4f".format(normalizedRms)}, 静音Job=$silenceJobStatus")
                    }
                    
                    // 🔧 修复：直接根据VAD结果累积音频，不需要连续帧检测
                    if (hasValidVoice) {
                        audioBufferMutex.withLock {
                            // 记录第一个音频帧的时间和语音活动状态
                            if (audioBuffer.isEmpty()) {
                                firstAudioTime = currentTime2
                                isInVoiceActivity = true
                                logger.info("🎯 开始新的语音活动周期，起始时间: $firstAudioTime")
                            }
                            
                            // 更新最后一次语音活动时间
                            lastVoiceActivityTime = currentTime2
                            isInVoiceActivity = true
                            
                            // 🎯 采样率调试日志 - KeywordDetector音频累积
                            val webrtcSampleRate = AudioDefaults.Formats.WEBRTC_APM.sampleRate
                            val webrtcChannels = AudioDefaults.Formats.WEBRTC_APM.channels
                            val frameDurationMs = (processedAudioData.size * 1000) / (webrtcSampleRate * webrtcChannels)
                            val currentTotalDurationMs = (totalAudioSamples * 1000) / (webrtcSampleRate * webrtcChannels)
                            
                            // 累积音频数据（用于关键词检测）
                            audioBuffer.add(processedAudioData.copyOf())
                            totalAudioSamples += processedAudioData.size
                            
                            val newTotalDurationMs = (totalAudioSamples * 1000) / (webrtcSampleRate * webrtcChannels)
                            
                            // 只在关键时刻记录累积信息
                            if (AudioDefaults.ENABLE_DEBUG_LOGS && audioReadCounter % AudioDefaults.LOG_INTERVAL_FRAMES == 0) {
                                logger.debug("🎯 KeywordDetector累积: 新增${processedAudioData.size}样本/${frameDurationMs}ms, 总计${totalAudioSamples}样本/${newTotalDurationMs}ms, 格式=${webrtcSampleRate}Hz/${webrtcChannels}ch")
                            }
                            
                            // === 第三方处理器专用逻辑 ===
                            if (AudioDefaults.USE_THIRD_PARTY_PROCESSOR) {
                                // 🔧 修复：10秒强制识别，防止语音状态持续过久占用内存
                                if (newTotalDurationMs >= 10000) { // 10秒强制识别
                                    logger.info("🚨 语音状态持续10秒，强制触发识别防止内存溢出")
                                    
                                    val audioBufferCopy = audioBuffer.toList()
                                    val totalSamplesCopy = totalAudioSamples
                                    
                                    val combinedAudio = ShortArray(totalSamplesCopy)
                                    var offset = 0
                                    for (chunk in audioBufferCopy) {
                                        chunk.copyInto(combinedAudio, offset)
                                        offset += chunk.size
                                    }
                                    
                                    logger.info("🚨 10秒强制识别开始: ${totalSamplesCopy}样本, 时长${newTotalDurationMs}ms")
                                    lastVoskProcessTime = currentTime2
                                    
                                    // 🔧 异步执行识别，避免阻塞
                                    scope.launch {
                                        try {
                                            voskDetector.detect(combinedAudio)
                                            logger.info("🚨 10秒强制识别完成")
                                        } catch (e: Exception) {
                                            logger.error("🚨 10秒强制识别异常: ${e.message}")
                                        }
                                    }
                                    
                                    // 🔧 强制识别后清理状态，重新开始累积
                                    audioBuffer.clear()
                                    totalAudioSamples = 0
                                    rawAudioBufferMutex.withLock {
                                        rawAudioBuffer.clear()
                                        totalRawAudioSamples = 0
                                    }
                                    // 🔧 注意：不重置isInVoiceActivity，继续保持语音状态
                                    logger.debug("🚨 10秒强制识别后状态已清理，继续累积语音")
                                }
                            }
                        }
                        
                        // 继续累积音频，等待语音活动结束时统一处理（仅WebRTC APM模式）
                        if (!AudioDefaults.USE_THIRD_PARTY_PROCESSOR) {
                            val currentDurationMs = totalAudioSamples * 1000 / AudioDefaults.Formats.WEBRTC_APM.sampleRate
                            if (AudioDefaults.ENABLE_DEBUG_LOGS && audioReadCounter % 100 == 0) {
                                logger.debug("累积语音活动中: ${totalAudioSamples}样本, 时长${currentDurationMs}ms")
                            }
                        }
                    }
                }
            }
        }
        
        // 配置VAD回调 - 🎯 这是语音识别的主要触发方式
        audioProcessor.setVadCallback { hasVoice ->
            scope.launch {
                val currentTime = Clock.System.now().toEpochMilliseconds()

                if (hasVoice) {
                    // 检测到语音：记录时间，取消任何静音计时
                    lastValidVoiceTime = currentTime
                    silenceDetectionJob?.cancel()
                    silenceDetectionJob = null
                    logger.debug("🎯 检测到语音，更新时间: $currentTime")
                } else {
                    // 检测到静音：添加额外验证，确保真的是静音
                    if (isInVoiceActivity && audioBuffer.isNotEmpty() && silenceDetectionJob == null) {
                        // 🔧 新增：二次验证静音状态
                        val currentVadState = audioProcessor.isVoiceDetected()
                        if (!currentVadState) {  // 再次确认确实是静音状态
                            logger.debug("🎯 检测到静音，启动800ms计时...")

                            silenceDetectionJob = scope.launch {
                                try {
                                    var silenceStartTime = Clock.System.now().toEpochMilliseconds()

                                    while (true) {
                                        kotlinx.coroutines.delay(100L) // 每100ms检查一次

                                        // 🔧 关键修复：在等待期间持续检查VAD状态
                                        val stillSilent = !audioProcessor.isVoiceDetected()
                                        if (!stillSilent) {
                                            logger.debug("🎯 静音计时被取消: 检测到新语音")
                                            break
                                        }

                                        val silenceDuration = Clock.System.now().toEpochMilliseconds() - silenceStartTime

                                        if (silenceDuration >= 800L) {
                                            // 真正超过800ms没声音，触发识别
                                            logger.info("🎯 超过800ms没声音，触发识别")

                                            // 🔧 修复：在协程中调用suspend函数
                                            triggerRecognition()
                                            break
                                        }
                                    }

                                    silenceDetectionJob = null
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    logger.debug("🎯 静音计时被取消")
                                    silenceDetectionJob = null
                                }
                            }
                        } else {
                            logger.debug("🎯 二次验证发现仍有语音，跳过静音检测")
                        }
                    }
                }
            }
        }
        
        isInitialized = true
        _detectorState.value = KeywordDetectorApi.DetectorState.IDLE
        logger.info("关键词检测器初始化成功")
        return true
    }
    /**
     * 🔧 根据专业指南更新：环境噪音校准（双时间常数积分器）
     */
    private fun calibrateEnvironmentNoise(rms: Float) {
        if (noiseCalibrationFrames < maxCalibrationFrames) {
            // 初始校准阶段：建立基线
            environmentNoiseBaseline = (environmentNoiseBaseline * noiseCalibrationFrames + rms) / (noiseCalibrationFrames + 1)
            fastNoiseEstimate = environmentNoiseBaseline
            slowNoiseEstimate = environmentNoiseBaseline
            noiseCalibrationFrames++

            if (noiseCalibrationFrames == maxCalibrationFrames) {
                val safetyMarginLinear = 10.0f.pow(AudioDefaults.NOISE_SAFETY_MARGIN_DB / 20.0f)
                val adjustedThreshold = environmentNoiseBaseline * safetyMarginLinear
                logger.info("🎯 环境噪音校准完成: 基线=${environmentNoiseBaseline}, 安全裕度=${AudioDefaults.NOISE_SAFETY_MARGIN_DB}dB, 调整后阈值=${adjustedThreshold}")
            }
        } else {
            // 🔧 专业指南：双时间常数积分器自适应更新
            if (rms < slowNoiseEstimate) {
                // 当前能量低于噪声估计，快速跟踪下降
                fastNoiseEstimate = AudioDefaults.NOISE_ADAPTATION_FAST_ALPHA * rms + 
                                  (1 - AudioDefaults.NOISE_ADAPTATION_FAST_ALPHA) * fastNoiseEstimate
                slowNoiseEstimate = AudioDefaults.NOISE_ADAPTATION_FAST_ALPHA * rms + 
                                  (1 - AudioDefaults.NOISE_ADAPTATION_FAST_ALPHA) * slowNoiseEstimate
            } else {
                // 当前能量高于噪声估计，慢速跟踪上升（避免语音污染噪声估计）
                fastNoiseEstimate = AudioDefaults.NOISE_ADAPTATION_SLOW_ALPHA * rms + 
                                  (1 - AudioDefaults.NOISE_ADAPTATION_SLOW_ALPHA) * fastNoiseEstimate
                slowNoiseEstimate = AudioDefaults.NOISE_ADAPTATION_SLOW_ALPHA * rms + 
                                  (1 - AudioDefaults.NOISE_ADAPTATION_SLOW_ALPHA) * slowNoiseEstimate
            }
            
            // 更新噪声基线为慢速估计
            environmentNoiseBaseline = slowNoiseEstimate
        }
    }

    /**
     * 🔧 根据专业指南更新：动态静音检测（自适应阈值）
     */
    private fun isDynamicSilence(rms: Float): Boolean {
        if (noiseCalibrationFrames < maxCalibrationFrames) {
            calibrateEnvironmentNoise(rms)
            return true  // 校准期间认为是静音
        }

        // 更新噪声估计
        calibrateEnvironmentNoise(rms)
        
        // 🔧 专业指南：噪声底板 + 安全裕度
        val safetyMarginLinear = 10.0f.pow(AudioDefaults.NOISE_SAFETY_MARGIN_DB / 20.0f)
        val dynamicThreshold = environmentNoiseBaseline * safetyMarginLinear
        
        val isSilent = rms < dynamicThreshold
        
        // 调试日志（降低频率）
        if (audioReadCounter % 200 == 0) {
            logger.debug("🎯 自适应静音检测: RMS=${rms}, 基线=${environmentNoiseBaseline}, 阈值=${dynamicThreshold}, 结果=${if(isSilent) "静音" else "有声"}")
        }
        
        return isSilent
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
        
        // 使用协程同步清理音频缓冲区
        scope.launch {
            audioBufferMutex.withLock {
                audioBuffer.clear()
                totalAudioSamples = 0
            }
            
            rawAudioBufferMutex.withLock {
                rawAudioBuffer.clear()
                totalRawAudioSamples = 0
            }
        }
        
        // 重置连续性检测
        lastAudioTime = 0L
        consecutiveAudioFrames = 0
        
        // 重置Vosk处理保护
        lastVoskProcessTime = 0L
        
        // 🔧 清理静音检测Job
        silenceDetectionJob?.cancel()
        silenceDetectionJob = null
        lastValidVoiceTime = 0L
        
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
                    apm.setNoiseSuppressionLevel(AudioDefaults.NOISE_SUPPRESSION_LEVEL_VERY_HIGH)
                    adjustments.add("环境不适合唤醒词，增强噪声抑制")
                    
                    // 增加前置放大
                    apm.setPreAmplifierGain(AudioDefaults.PRE_AMPLIFIER_GAIN)
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
            
            // 音频累积状态 - 使用安全的方式获取缓冲区大小
            appendLine("=== 音频累积状态 ===")
            appendLine("当前累积样本数: $totalAudioSamples")
            appendLine("目标样本数: $minAudioSamplesFor400ms")
            
            // 安全获取缓冲区大小，避免并发修改异常
            val bufferSize = try {
                // 使用 runBlocking 来同步获取缓冲区大小
                kotlinx.coroutines.runBlocking {
                    audioBufferMutex.withLock {
                        audioBuffer.size
                    }
                }
            } catch (e: Exception) {
                logger.warn("获取缓冲区大小失败: ${e.message}")
                -1
            }
            
            appendLine("缓冲区块数: ${if (bufferSize >= 0) bufferSize else "不可用"}")
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
            if (totalAudioSamples < minAudioSamplesFor400ms / 2) {
                appendLine("ℹ️ 音频累积不足，可能影响识别准确率")
                appendLine("   建议: 1) 延长说话时间; 2) 确保连续发音; 3) 避免长时间停顿")
            }
            
            if (consecutiveAudioFrames < minConsecutiveFrames) {
                appendLine("ℹ️ 音频连续性不佳")
                appendLine("   建议: 1) 保持连续发音; 2) 检查麦克风连接; 3) 避免间断性输入")
            }
            
            appendLine()
            appendLine("=== 性能指标 ===")
            appendLine("音频累积效率: ${if (totalAudioSamples > 0) (totalAudioSamples.toFloat() / minAudioSamplesFor400ms * 100).toInt() else 0}%")
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

    /**
     * 异步执行播放确认，避免阻塞音频流
     */
    private suspend fun performPlaybackConfirmation(apmAudio: ShortArray, rawAudio: ShortArray?) {
        try {
            // 🔧 新增：音频质量检查，只播放高质量音频
            if (!isAudioQualityGoodForPlayback(apmAudio)) {
                logger.info("[播放确认] 音频质量不足，跳过播放确认")
                return
            }
            
            // 调试：统计零值比例和最大振幅
            val apmNonZero = apmAudio.count { it != 0.toShort() }
            val apmZeroRatio = (apmAudio.size - apmNonZero).toFloat() / apmAudio.size
            val apmMaxAmp = apmAudio.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
            val rawStats = if (rawAudio != null) {
                val rawNonZero = rawAudio.count { it != 0.toShort() }
                val rawZeroRatio = (rawAudio.size - rawNonZero).toFloat() / rawAudio.size
                val rawMaxAmp = rawAudio.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
                "原始音频: 零值比例=${"%.4f".format(rawZeroRatio)}, 最大振幅=$rawMaxAmp"
            } else "原始音频: 无"
            logger.info("[播放确认] APM音频: 零值比例=${"%.4f".format(apmZeroRatio)}, 最大振幅=$apmMaxAmp | $rawStats")
            
            // 🎯 修复采样率不匹配问题：第三方处理器输出16kHz/1ch，需要重采样到48kHz/2ch
            val sourceFormat = if (AudioDefaults.USE_THIRD_PARTY_PROCESSOR) {
                // 第三方处理器输出格式：16kHz/1ch
                AudioDefaults.Formats.WEBRTC_APM
            } else {
                // WebRTC APM输出格式：16kHz/1ch
                AudioDefaults.Formats.WEBRTC_APM
            }
            val targetFormat = AudioDefaults.Formats.OUTPUT_DEVICE
            
            logger.info("[播放确认] 音频格式转换: ${sourceFormat} -> ${targetFormat}")
            
            // 先播放APM音频（需要重采样）
            logger.info("[播放确认] 🔊 播放APM处理后音频（重采样后）")
            val apmPlayResult = tryPlayAudioWithResampling(
                audioData = apmAudio,
                sourceFormat = sourceFormat,
                targetFormat = targetFormat
            )
            logger.info("[播放确认] 🔊 APM音频播放${if (apmPlayResult) "成功" else "失败"}")
            
            // 再播放原始音频（如果有的话）
            if (rawAudio != null) {
                logger.info("[播放确认] 🔊 播放原始音频（重采样后）")
                val rawPlayResult = tryPlayAudioWithResampling(
                    audioData = rawAudio,
                    sourceFormat = sourceFormat,
                    targetFormat = targetFormat
                )
                logger.info("[播放确认] 🔊 原始音频播放${if (rawPlayResult) "成功" else "失败"}")
            }
        } catch (e: Exception) {
            logger.error("[播放确认] ❌ 播放确认处理失败: ${e.message}")
        }
    }
    
    /**
     * 🎯 新增：带重采样的音频播放方法
     */
    private suspend fun tryPlayAudioWithResampling(
        audioData: ShortArray,
        sourceFormat: AudioDefaults.AudioFormat,
        targetFormat: AudioDefaults.AudioFormat
    ): Boolean {
        try {
            val sourceDurationMs = (audioData.size * 1000) / (sourceFormat.sampleRate * sourceFormat.channels)
            logger.info("🎯 重采样播放: 源格式=${sourceFormat}, 目标格式=${targetFormat}, 源时长=${sourceDurationMs}ms")
            
            // 如果格式相同，直接播放
            if (sourceFormat.isSameAs(targetFormat)) {
                val audioBytes = voice.util.AudioUtils.shortArrayToByteArray(audioData)
                return audioProcessor.audioDevice.play(audioBytes, audioBytes.size)
            }
            
            // 🔧 修复控制流：避免在finally块中的return语句导致的内存问题
            var resampler: voice.audio.processing.SafeSoxrResampler? = null
            var playResult = false
            
            try {
                // 创建重采样器实例
                resampler = voice.audio.processing.SafeSoxrResampler(
                    inputSampleRate = sourceFormat.sampleRate,
                    outputSampleRate = targetFormat.sampleRate,
                    inputChannels = sourceFormat.channels,
                    outputChannels = targetFormat.channels,
                    quality = AudioDefaults.SOXR_QUALITY
                )
                
                val initSuccess = resampler.initialize()
                if (!initSuccess) {
                    logger.error("🎯 播放重采样器初始化失败")
                    return false
                }
                
                logger.info("🎯 播放重采样器初始化成功: ${sourceFormat} -> ${targetFormat}")
                
                // 执行重采样
                val resampledData = resampler.process(audioData)
                if (resampledData.isEmpty()) {
                    logger.error("🎯 重采样结果为空")
                    return false
                }
                
                val targetDurationMs = (resampledData.size * 1000) / (targetFormat.sampleRate * targetFormat.channels)
                logger.info("🎯 重采样完成: ${audioData.size}样本 -> ${resampledData.size}样本, 时长: ${sourceDurationMs}ms -> ${targetDurationMs}ms")
                
                // 播放重采样后的音频
                val audioBytes = voice.util.AudioUtils.shortArrayToByteArray(resampledData)
                playResult = audioProcessor.audioDevice.play(audioBytes, audioBytes.size)
                
            } catch (processingException: Exception) {
                logger.error("🎯 重采样处理异常: ${processingException.message}")
                playResult = false
            } finally {
                // 🔧 关键修复：安全释放重采样器资源，不在finally中使用return
                resampler?.let { resamplerInstance ->
                    try {
                        resamplerInstance.release()
                        logger.debug("🎯 重采样器资源已安全释放")
                    } catch (releaseException: Exception) {
                        logger.warn("🎯 释放重采样器资源时出错: ${releaseException.message}")
                        // 不重新抛出异常，避免掩盖原始错误
                    }
                }
            }
            
            return playResult
            
        } catch (e: Exception) {
            logger.error("🎯 重采样播放失败: ${e.message}")
            return false
        }
    }
    
    /**
     * 🎯 新增：使用正确格式信息的播放方法
     * @deprecated 使用 tryPlayAudioWithResampling 替代
     */
    private suspend fun tryPlayAudioWithCorrectFormat(
        audioBytes: ByteArray, 
        sampleRate: Int,
        channels: Int,
        durationMs: Int
    ): Boolean {
        try {
            logger.info("🎯 播放音频格式: ${sampleRate}Hz/${channels}ch, ${audioBytes.size}字节, ${durationMs}ms")
            
            // 🎯 策略1：尝试完整播放
            val fullPlaySuccess = audioProcessor.audioDevice.play(audioBytes, audioBytes.size)
            if (fullPlaySuccess) {
                logger.info("🎯 完整播放成功: ${audioBytes.size}字节")
                return true
            }
            
            logger.warn("🎯 音频播放失败，音频大小: ${audioBytes.size}字节")
            return false
            
        } catch (e: Exception) {
            logger.error("🎯 格式化播放失败: ${e.message}")
            return false
        }
    }
    
    /**
     * 智能播放策略：先尝试完整播放，失败则分段播放
     * @deprecated 使用 tryPlayAudioWithResampling 替代
     */
    private suspend fun tryPlayAudioWithFallback(audioBytes: ByteArray, durationMs: Int): Boolean {
        // 将ByteArray转换为ShortArray，然后使用新的重采样方法
        val audioData = voice.util.AudioUtils.byteArrayToShortArray(audioBytes)
        return tryPlayAudioWithResampling(
            audioData = audioData,
            sourceFormat = AudioDefaults.Formats.WEBRTC_APM,
            targetFormat = AudioDefaults.Formats.OUTPUT_DEVICE
        )
    }

    /**
     * 🔧 音频质量检查函数 - 确保只播放高质量音频
     */
    private fun isAudioQualityGoodForPlayback(audio: ShortArray): Boolean {
        if (audio.isEmpty()) {
            logger.debug("[质量检查] 音频为空")
            return false
        }
        
        // 1. 检查最大振幅 - 🔧 大幅降低阈值以便调试
        val maxAmp = audio.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
        if (maxAmp < 100) { // 🔧 从300降低到100
            logger.debug("[质量检查] 最大振幅过低: $maxAmp < 100")
            return false
        }
        
        // 2. 检查RMS能量 - 🔧 大幅降低阈值以便调试
        val rmsEnergy = kotlin.math.sqrt(audio.map { it.toFloat() * it.toFloat() }.average())
        val normalizedRms = rmsEnergy / 32768.0f
        if (normalizedRms < 0.002f) { // 🔧 从0.008f降低到0.002f
            logger.debug("[质量检查] RMS能量过低: ${"%.4f".format(normalizedRms)} < 0.002")
            return false
        }
        
        // 3. 检查零值比例 - 🔧 放宽限制
        val zeroCount = audio.count { it == 0.toShort() }
        val zeroRatio = zeroCount.toFloat() / audio.size
        if (zeroRatio > 0.5f) { // 🔧 从0.3提高到0.5，更宽松
            logger.debug("[质量检查] 零值比例过高: ${"%.4f".format(zeroRatio)} > 0.5")
            return false
        }
        
        // 4. 检查音频长度（避免播放过长的音频）
        val durationMs = (audio.size * 1000) / (AudioDefaults.Formats.WEBRTC_APM.sampleRate * AudioDefaults.Formats.WEBRTC_APM.channels)
        if (durationMs > 5000) { // 🔧 从3秒提高到5秒
            logger.debug("[质量检查] 音频过长: ${durationMs}ms > 5000ms")
            return false
        }
        
        // 5. 🔧 降低动态范围要求
        val minAmp = audio.minOfOrNull { it.toInt() } ?: 0
        val maxAmpSigned = audio.maxOfOrNull { it.toInt() } ?: 0
        val dynamicRange = maxAmpSigned - minAmp
        if (dynamicRange < 50) { // 🔧 从200降低到50
            logger.debug("[质量检查] 动态范围过小: $dynamicRange < 50")
            return false
        }
        
        logger.info("[质量检查] ✅ 音频质量通过: 振幅=$maxAmp, RMS=${"%.4f".format(normalizedRms)}, 零值比例=${"%.4f".format(zeroRatio)}, 时长=${durationMs}ms, 动态范围=$dynamicRange")
        return true
    }

    /**
     * 触发语音识别
     */
    private suspend fun triggerRecognition() {
        audioBufferMutex.withLock {
            if (audioBuffer.isNotEmpty() && isInVoiceActivity) {
                val accumulationDurationMs = (totalAudioSamples * 1000) / (AudioDefaults.Formats.WEBRTC_APM.sampleRate * AudioDefaults.Formats.WEBRTC_APM.channels)
                
                logger.info("🚀 开始识别: 累积时长=${accumulationDurationMs}ms")
                
                val audioBufferCopy = audioBuffer.toList()
                val totalSamplesCopy = totalAudioSamples
                
                val combinedAudio = ShortArray(totalSamplesCopy)
                var offset = 0
                for (chunk in audioBufferCopy) {
                    chunk.copyInto(combinedAudio, offset)
                    offset += chunk.size
                }
                
                // 🔧 添加播放确认功能
                if (AudioDefaults.ENABLE_PLAYBACK_CONFIRMATION) {
                    logger.info("🔊 开始播放确认...")
                    
                    // 获取原始音频数据
                    val rawAudioData = rawAudioBufferMutex.withLock {
                        if (rawAudioBuffer.isNotEmpty()) {
                            val totalRawSamples = rawAudioBuffer.sumOf { it.size }
                            val combinedRawAudio = ShortArray(totalRawSamples)
                            var rawOffset = 0
                            for (chunk in rawAudioBuffer) {
                                chunk.copyInto(combinedRawAudio, rawOffset)
                                rawOffset += chunk.size
                            }
                            combinedRawAudio
                        } else {
                            null
                        }
                    }
                    
                    // 执行播放确认
                    try {
                        performPlaybackConfirmation(combinedAudio, rawAudioData)
                        logger.info("🔊 播放确认完成")
                    } catch (e: Exception) {
                        logger.error("🔊 播放确认失败: ${e.message}")
                    }
                }
                
                lastVoskProcessTime = Clock.System.now().toEpochMilliseconds()
                
                // 异步执行识别
                scope.launch {
                    try {
                        voskDetector.detect(combinedAudio)
                        logger.info("🎯 识别完成")
                    } catch (e: Exception) {
                        logger.error("🚨 识别异常: ${e.message}")
                    }
                }
                
                // 清理状态
                audioBuffer.clear()
                totalAudioSamples = 0
                rawAudioBufferMutex.withLock {
                    rawAudioBuffer.clear()
                    totalRawAudioSamples = 0
                }
                isInVoiceActivity = false
                logger.debug("🎯 状态已清理")
            }
        }
    }
}