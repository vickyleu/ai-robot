@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package voice.audio.processing

import com.airobot.core.utils.format
import kotlinx.cinterop.ExperimentalForeignApi
import voice.acquisition.portaudio.PortAudioDevice
import voice.util.LogManager
import voice.util.AudioDefaults
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.datetime.Clock
import voice.util.AudioUtils
import kotlin.concurrent.Volatile
import kotlinx.cinterop.refTo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock.System
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.fflush
import platform.posix.fclose

/**
 * 回调式音频处理器
 * 支持WebRTC APM和第三方音频处理器（RNNoise + SpeexDSP + SoXR）
 */
class CallbackAudioProcessor : PortAudioDevice.AudioDataCallback {
    private val logger = LogManager.getLogger("CallbackAudioProcessor")
    
    // 状态
    private val _processingState = MutableStateFlow(ProcessingState.IDLE)
    val processingState: StateFlow<ProcessingState> = _processingState.asStateFlow()
    
    // 音频质量监控
    private var lowQualityFrames = 0
    private val maxLowQualityFrames = 10
    
    // 音频设备实例
    val audioDevice = PortAudioDevice.getInstance()
    private val scope = CoroutineScope(Dispatchers.IO)

    // 音频处理锁
    private val processingLock = SynchronizedObject()
    
    // 音频处理器实例 - 根据配置选择
    @Volatile
    private var webrtcApm: WebRtcApm? = null
    
    @Volatile
    private var thirdPartyProcessor: ThirdPartyAudioProcessor? = null
    
    // 处理器就绪状态检查
    @Volatile
    private var processorReady = false
    
    @Volatile
    private var processorFullyInitialized = false
    
    // 音频参数
    @Volatile
    private var inputFormat = AudioDefaults.AudioFormat(
        AudioDefaults.INPUT_DEVICE_SAMPLE_RATE,
        AudioDefaults.INPUT_DEVICE_CHANNELS
    )
    
    // 上一次处理异常的时间，用于限制日志频率
    private var lastErrorLogTime = 0L
    private val errorLogThrottleMs = 1000 // 限制错误日志为每秒最多一条
    
    // 音频读取计数器
    private var audioReadCounter = 0
    private val minValidRms = 0.003 // 🔧 从0.008进一步降低到0.003，适应实际的RMS水平
    
    // 🔧 修复：降低音频质量检测阈值，适应第三方处理器的输出水平
    private val minValidAmplitude = 1000 // 🔧 从3500大幅降低到1000，适应RNNoise/SpeexDSP处理后的音频
    private val minConsecutiveValidFrames = 2 // 🔧 从3降低到2帧，提高响应速度
    private var consecutiveValidFrameCount = 0 // 🔧 连续有效帧计数器
    
    // 🔧 智能日志打印策略
    private var lastVadResult = false  // 记录上一次的VAD结果
    private var silentFrameCount = 0   // 连续静音帧计数器
    
    // 回调函数 - 保持与现有代码兼容的命名，增加同步保护
    private val callbackLock = SynchronizedObject()
    private var processedAudioCallback: ( (ShortArray, Int) -> Unit)? = null
    private var vadCallback: ((Boolean) -> Unit)? = null
    private var rawAudioCallback: ((ShortArray, Int) -> Unit)? = null
    
    // 原始录音文件保存
    private var rawRecordingFile: kotlinx.cinterop.CPointer<platform.posix.FILE>? = null
    private var rawRecordingInitialized = false
    
    // 处理状态枚举
    enum class ProcessingState {
        IDLE, PROCESSING, ERROR
    }
    
    /**
     * 初始化音频处理器
     */
    suspend fun initialize(sampleRate: Int, channels: Int): Boolean {
        // 更新输入格式
        inputFormat = AudioDefaults.AudioFormat(sampleRate, channels)
        
        synchronized(processingLock) {
            try {
                // 严格重置处理器就绪状态
                processorReady = false
                processorFullyInitialized = false
                
                // 释放现有实例
                webrtcApm?.release()
                webrtcApm = null
                thirdPartyProcessor?.cleanup()
                thirdPartyProcessor = null
                
                // 根据配置选择处理器
                if (AudioDefaults.USE_THIRD_PARTY_PROCESSOR) {
                    logger.info("使用第三方音频处理器 (RNNoise + SpeexDSP + SoXR)")
                    return initializeThirdPartyProcessor(sampleRate, channels)
                } else {
                    logger.info("使用WebRTC APM音频处理器")
                    return initializeWebRtcApm(sampleRate, channels)
                }
            } catch (e: Exception) {
                logger.error("初始化音频处理器失败: ${e.message}")
                processorReady = false
                processorFullyInitialized = false
                _processingState.value = ProcessingState.ERROR
                return false
            }
        }
    }
    
    /**
     * 初始化第三方音频处理器
     */
    private fun initializeThirdPartyProcessor(sampleRate: Int, channels: Int): Boolean {
        return try {
            val processor = ThirdPartyAudioProcessor()
            
            // 创建处理配置
            val config = ThirdPartyAudioProcessor.ProcessingConfig(
                enableRNNoise = AudioDefaults.ENABLE_RNNOISE,
                rnnoiseVadThreshold = AudioDefaults.RNNOISE_VAD_THRESHOLD,
                rnnoiseGain = AudioDefaults.RNNOISE_GAIN,
                enableSpeexAGC = AudioDefaults.ENABLE_SPEEX_AGC,
                enableSpeexVAD = AudioDefaults.ENABLE_SPEEX_VAD,
                enableSpeexDenoise = AudioDefaults.ENABLE_SPEEX_DENOISE,
                speexAgcLevel = AudioDefaults.SPEEX_AGC_LEVEL,
                speexNoiseSuppress = AudioDefaults.SPEEX_NOISE_SUPPRESS_DB,
                enableResampling = true,
                resamplingQuality = AudioDefaults.SOXR_QUALITY,
                frameSize = AudioDefaults.AUDIO_FRAME_SIZE,
                enableQualityMonitoring = AudioDefaults.ENABLE_QUALITY_MONITORING
            )
            
            if (!processor.initialize(
                inputSampleRate = sampleRate,
                inputChannels = channels,
                outputSampleRate = AudioDefaults.WEBRTC_APM_SAMPLE_RATE,
                outputChannels = AudioDefaults.WEBRTC_APM_CHANNELS,
                processingConfig = config
            )) {
                logger.error("第三方音频处理器初始化失败")
                return false
            }
            
            thirdPartyProcessor = processor
            processorReady = true
            processorFullyInitialized = true
            
            logger.info("✅ 第三方音频处理器初始化成功: 输入=${inputFormat}, 输出=${AudioDefaults.WEBRTC_APM_SAMPLE_RATE}Hz/${AudioDefaults.WEBRTC_APM_CHANNELS}ch")
            _processingState.value = ProcessingState.IDLE
            true
        } catch (e: Exception) {
            logger.error("第三方音频处理器初始化异常: ${e.message}")
            false
        }
    }
    
    /**
     * 初始化WebRTC APM
     */
    private fun initializeWebRtcApm(sampleRate: Int, channels: Int): Boolean {
        return try {
            // 创建新实例
            val newApm = WebRtcApm()
            if (!newApm.initialize(sampleRate, channels)) {
                logger.error("初始化WebRTC APM失败")
                return false
            }
            
            // 配置APM - 使用AudioDefaults常量配置
            newApm.setVadThreshold(AudioDefaults.VAD_THRESHOLD)
            newApm.setVadDebounceFrames(AudioDefaults.VAD_DEBOUNCE_FRAMES)
            newApm.enableEchoCancellation(AudioDefaults.ENABLE_ECHO_CANCELLATION)
            
            webrtcApm = newApm
            
            // 双重验证APM完全就绪
            val apmHandle = newApm.getApmHandle()
            if (apmHandle == null) {
                logger.error("APM句柄为空，初始化失败")
                processorReady = false
                processorFullyInitialized = false
                return false
            }
            
            // 确保APM完全就绪后才允许音频处理
            processorReady = true
            processorFullyInitialized = true
            
            logger.info("✅ WebRTC APM初始化成功: 输入=${inputFormat}, APM=${AudioDefaults.WEBRTC_APM_SAMPLE_RATE}Hz/${AudioDefaults.WEBRTC_APM_CHANNELS}ch")
            _processingState.value = ProcessingState.IDLE
            true
        } catch (e: Exception) {
            logger.error("WebRTC APM初始化异常: ${e.message}")
            false
        }
    }
    
    /**
     * 开始处理
     */
    suspend fun startProcessing(): Boolean {
        if (_processingState.value == ProcessingState.PROCESSING) {
            logger.warn("音频处理器已经在运行")
            return true
        }
        
        try {
            // 确保音频设备初始化
            if (!audioDevice.initialize(inputFormat.sampleRate)) {
                logger.error("初始化音频设备失败")
                return false
            }
            
            // 设置回调
            audioDevice.setAudioCallback(this)
            
            // 打开输入流，使用回调模式
            val success = audioDevice.openInputStreamWithCallback(inputFormat.sampleRate, inputFormat.channels, this)
            if (!success) {
                logger.error("打开音频输入流失败")
                return false
            }
            
            // 启动设备
            audioDevice.start()
            
            _processingState.value = ProcessingState.PROCESSING
            logger.info("音频处理器开始工作: ${inputFormat}")
            return true
        } catch (e: Exception) {
            logger.error("启动音频处理器失败: ${e.message}")
            _processingState.value = ProcessingState.ERROR
            return false
        }
    }
    
    /**
     * 处理PortAudio回调的音频数据
     */
    override  fun onAudioInput(audioData: ShortArray, frameCount: Int) {
        // 检查处理器是否完全初始化
        if (!processorFullyInitialized) {
            // 处理器未完全初始化，但仍然写入原始录音用于调试
            writeRawRecording(audioData)
            return
        }
        
        synchronized(processingLock) {
            try {
                _processingState.value = ProcessingState.PROCESSING
                
                // 基本参数验证
                if (audioData.isEmpty() || frameCount <= 0) {
                    return
                }
                
                // 记录原始音频数据（用于调试）
                val firstTenSamples = audioData.take(10).joinToString(",")
                val maxAmplitude = audioData.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
                
                // 只在振幅较大时记录，避免频繁日志
                if (AudioDefaults.ENABLE_DEBUG_LOGS && maxAmplitude > 5000 && audioReadCounter % 200 == 0) {  // 减少频率
                    logger.debug("原始音频: 前10个样本=$firstTenSamples, 最大振幅=$maxAmplitude")
                }
                
                writeRawRecording(audioData)
                
                // 调用原始音频回调 - 在处理之前提供原始数据
                rawAudioCallback?.invoke(audioData.copyOf(), frameCount)
                
                // 音频质量检查
                if (maxAmplitude < 100) {
                    return
                }
                
                // 根据配置选择处理器
                val processedAudio: ShortArray
                val vadResult: Boolean
                
                if (AudioDefaults.USE_THIRD_PARTY_PROCESSOR) {
                    // 使用第三方音频处理器
                    val processor = thirdPartyProcessor
                    if (processor == null) {
                        logger.debug("第三方处理器未就绪，跳过音频处理")
                        return
                    }
                    
                    processedAudio = try {
                        processor.processFrame(audioData)
                    } catch (e: Exception) {
                        logger.error("第三方音频处理异常: ${e.message}")
                        return
                    }
                    
                    // 获取VAD结果
                    vadResult = try {
                        processor.isVoiceDetected()
                    } catch (e: Exception) {
                        logger.warn("第三方VAD检测异常: ${e.message}")
                        false
                    }
                } else {
                    // 使用WebRTC APM处理器
                    val currentApm = webrtcApm
                    if (currentApm == null) {
                        logger.debug("WebRTC APM未就绪，跳过音频处理")
                        return
                    }
                    
                    processedAudio = try {
                        // 使用支持诊断模式的processFrame方法，而不是 processAndResample
                        runBlocking {
                            currentApm.processAndResample(audioData,
                                outputSampleRate = AudioDefaults.WEBRTC_APM_SAMPLE_RATE,
                                outputChannels = AudioDefaults.WEBRTC_APM_CHANNELS
                            )
                        }
                    } catch (e: Exception) {
                        logger.error("WebRTC APM音频处理异常: ${e.message}")
                        return
                    }
                    
                    // VAD检测
                    vadResult = try {
                        currentApm.isVoiceDetected()
                    } catch (e: Exception) {
                        logger.warn("WebRTC APM VAD检测异常: ${e.message}")
                        false
                    }
                }
                
                // 检查处理结果
                val processedMaxAmp = processedAudio.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
                
                if (processedMaxAmp == 0) {
                    logger.debug("处理后音频为空，跳过")
                    return
                }
                
                // 🔧 修复：更严格的音频质量验证
                val rms = calculateRms(processedAudio)
                val normalizedRms = rms // calculateRms已经返回归一化值(0-1范围)
                val isValidAudio = processedMaxAmp >= minValidAmplitude && normalizedRms >= minValidRms
                
                // 🔧 修复：简化连续帧检测逻辑
                if (isValidAudio) {
                    consecutiveValidFrameCount++
                } else {
                    // 🔧 简化：直接重置连续帧计数，避免复杂的逐渐减少逻辑
                    consecutiveValidFrameCount = 0
                }
                
                // 🔧 修复：只有连续多帧都满足条件才认为是真正的语音
                val hasConsecutiveValidFrames = consecutiveValidFrameCount >= minConsecutiveValidFrames
                
                // 🔧 修复：改为AND逻辑，SpeexDSP VAD和能量检测都必须通过才认为是人声
                // 这样可以有效过滤掉非人声的大音量噪音（如音乐、机械声等）
                // 只有当SpeexDSP检测到语音特征且能量也符合要求时才判定为人声
                val finalVadResult = vadResult && isValidAudio && hasConsecutiveValidFrames
                
                // 🔧 VAD调试：显示各组件的检测结果
                if (audioReadCounter % 10 == 0) {  // 每10帧显示一次调试信息
                    logger.debug("VAD调试: SpeexDSP=$vadResult, 能量检测=$isValidAudio(振幅≥$minValidAmplitude=${processedMaxAmp >= minValidAmplitude}, RMS≥$minValidRms=${normalizedRms >= minValidRms}), 连续帧=$hasConsecutiveValidFrames($consecutiveValidFrameCount>=$minConsecutiveValidFrames), 最终=$finalVadResult")
                }
                
                // 🔧 智能日志打印策略：只在检测到语音或状态变化时打印，静音状态不打印
                val vadStateChanged = finalVadResult != lastVadResult
                
                // 更新静音帧计数器
                if (!finalVadResult) {
                    silentFrameCount++
                } else {
                    silentFrameCount = 0  // 检测到语音时重置
                }
                
                // 智能打印频率控制：只在有语音或状态变化时打印
                val shouldPrint = when {
                    vadStateChanged -> true  // VAD状态变化时立即打印
                    finalVadResult -> audioReadCounter % 5 == 0  // 有语音时每5次打印一次
                    else -> false  // 静音状态不打印日志
                }
                
                if (shouldPrint) {
                    val currentTime = System.now().toEpochMilliseconds()
                    val timeStr = "${currentTime % 100000}"  // 显示最后5位数字作为时间戳
                    val stateInfo = if (vadStateChanged) "[状态变化]" else ""
                    logger.info("$stateInfo[${timeStr}] 电平: 振幅=$processedMaxAmp, RMS=${"%.3f".format(normalizedRms)}, VAD=${if(finalVadResult) "语音" else "静音"}")
                }
                
                // 更新上一次VAD结果
                lastVadResult = finalVadResult
                
                // 发送处理后的音频数据
                synchronized(callbackLock) {
                    processedAudioCallback?.invoke(processedAudio, frameCount)
                    // 🔧 关键修复：只在VAD状态变化时才调用VAD回调
                    if (vadStateChanged) {
                        vadCallback?.invoke(finalVadResult)
                    }
                }
                
            } catch (e: Exception) {
                val currentTime = Clock.System.now().toEpochMilliseconds()
                if (currentTime - lastErrorLogTime > errorLogThrottleMs) {
                    logger.error("音频处理回调异常: ${e.message}")
                    lastErrorLogTime = currentTime
                }
                _processingState.value = ProcessingState.ERROR
            } finally {
                _processingState.value = ProcessingState.IDLE
            }
        }
    }
    
    /**
     * 安全发送处理后的音频数据
     */
    private suspend fun sendProcessedAudio(data: ShortArray, frameCount: Int) {
        val callback = synchronized(callbackLock) { processedAudioCallback }
        if (callback != null) {
            try {
                // 添加日志确认回调被触发
                if (audioReadCounter % 500 == 0) {
                    logger.debug("发送处理后的音频数据: frameCount=$frameCount")
                }
                callback.invoke(data, frameCount)
            } catch (e: Exception) {
                val now = Clock.System.now().toEpochMilliseconds()
                if (now - lastErrorLogTime > errorLogThrottleMs) {
                    logger.error("处理后音频回调执行异常: ${e.message}")
                    lastErrorLogTime = now
                }
            }
        } else {
            // 没有设置回调
            if (audioReadCounter % 1000 == 0) {
                logger.warn("processedAudioCallback 未设置")
            }
        }
    }
    
    /**
     * 安全发送VAD结果
     */
    private fun sendVadResult(hasVoice: Boolean) {
        val callback = synchronized(callbackLock) { vadCallback }
        try {
            callback?.invoke(hasVoice)
        } catch (e: Exception) {
            val now = Clock.System.now().toEpochMilliseconds()
            if (now - lastErrorLogTime > errorLogThrottleMs) {
                logger.error("VAD回调执行异常: ${e.message}")
                lastErrorLogTime = now
            }
        }
    }
    
    /**
     * 设置处理后音频的回调
     * 保持与现有代码的兼容性
     */
    fun setProcessedAudioCallback(callback:  (ShortArray, Int) -> Unit) {
        synchronized(callbackLock) {
            this.processedAudioCallback = callback
        }
    }
    
    /**
     * 设置VAD状态变化回调
     * 保持与现有代码的兼容性
     */
    fun setVadCallback(callback: (Boolean) -> Unit) {
        synchronized(callbackLock) {
            this.vadCallback = callback
        }
    }
    
    /**
     * 设置原始音频回调
     */
    fun setRawAudioCallback(callback: (ShortArray, Int) -> Unit) {
        synchronized(callbackLock) {
            rawAudioCallback = callback
        }
    }
    
    /**
     * 检查当前是否检测到语音
     * 根据配置查询对应处理器的VAD状态
     */
    fun isVoiceDetected(): Boolean {
        return synchronized(processingLock) {
            if (AudioDefaults.USE_THIRD_PARTY_PROCESSOR) {
                thirdPartyProcessor?.isVoiceDetected() ?: false
            } else {
                webrtcApm?.isVoiceDetected() ?: false
            }
        }
    }
    
    /**
     * 获取WebRTC APM实例
     * @return WebRtcApm实例，可能为null
     */
    fun getApm(): WebRtcApm? {
        return synchronized(processingLock) { webrtcApm }
    }
    
    /**
     * 获取第三方音频处理器实例
     * @return ThirdPartyAudioProcessor实例，可能为null
     */
    fun getThirdPartyProcessor(): ThirdPartyAudioProcessor? {
        return synchronized(processingLock) { thirdPartyProcessor }
    }
    
    /**
     * 设置回声消除状态
     * 注意：仅对WebRTC APM有效，第三方处理器使用SpeexDSP的回声消除
     */
    fun setEchoCancellationEnabled(enabled: Boolean) {
        synchronized(processingLock) {
            if (AudioDefaults.USE_THIRD_PARTY_PROCESSOR) {
                logger.warn("第三方处理器模式下，回声消除由SpeexDSP管理，此设置无效")
            } else {
                webrtcApm?.enableEchoCancellation(enabled)
            }
        }
    }
    
    /**
     * 获取处理器统计信息
     */
    fun getProcessorStats(): String {
        return synchronized(processingLock) {
            if (AudioDefaults.USE_THIRD_PARTY_PROCESSOR) {
                val stats = thirdPartyProcessor?.getStats()
                if (stats != null) {
                    "第三方处理器统计: 处理帧数=${stats.framesProcessed}, 语音帧数=${stats.voiceFramesDetected}, VAD概率=${stats.lastVadProbability}, RMS=${stats.lastRmsLevel}, 最大振幅=${stats.lastMaxAmplitude}, 零值比例=${stats.lastZeroRatio}"
                } else {
                    "第三方处理器未初始化"
                }
            } else {
                "WebRTC APM模式，无详细统计信息"
            }
        }
    }
    
    /**
     * 停止处理
     */
     fun stopProcessing() {
        try {
            audioDevice.stop()
            audioDevice.release()
            _processingState.value = ProcessingState.IDLE
            logger.info("音频处理器已停止")
        } catch (e: Exception) {
            logger.error("停止音频处理器失败: ${e.message}")
            _processingState.value = ProcessingState.ERROR
        }
    }
    
    /**
     * 计算音频数据的最大振幅
     */
    private fun calculateMaxAmplitude(data: ShortArray, frameCount: Int): Int {
        var maxAmplitude = 0
        val samples = minOf(data.size, frameCount)
        for (i in 0 until samples) {
            val amplitude = kotlin.math.abs(data[i].toInt())
            if (amplitude > maxAmplitude) {
                maxAmplitude = amplitude
            }
        }
        return maxAmplitude
    }
    
    /**
     * 检测尖锐瞬变 - 键盘声的特征
     * 键盘声通常有尖锐的振幅变化
     * 注意：此方法现在主要用于调试，实际键盘检测由WebRTC APM内部处理
     */
    private fun detectSharpTransients(data: ShortArray): Boolean {
        if (data.size < 10) return false
        
        var maxChange = 0
        var highChangeCount = 0
        
        for (i in 1 until minOf(data.size, 50)) {
            val change = kotlin.math.abs(data[i].toInt() - data[i-1].toInt())
            if (change > maxChange) maxChange = change
            if (change > 1000) highChangeCount++
        }
        
        // 键盘声特征：大幅振幅变化且变化频繁
        return maxChange > 5000 && highChangeCount > 3
    }
    
    /**
     * 释放资源
     */
    suspend fun release() {
        if (_processingState.value == ProcessingState.PROCESSING) {
            stopProcessing()
        }
        
        // 关闭原始录音文件
        rawRecordingFile?.let {
            try {
                fclose(it)
                logger.info("原始录音文件已关闭")
            } catch (e: Exception) {
                logger.error("关闭原始录音文件失败: ${e.message}")
            }
            rawRecordingFile = null
            rawRecordingInitialized = false
        }
        
        // 清除回调
        synchronized(callbackLock) {
            processedAudioCallback = null
            vadCallback = null
            rawAudioCallback = null
        }
        
        // 释放音频处理器
        synchronized(processingLock) {
            try {
                if (AudioDefaults.USE_THIRD_PARTY_PROCESSOR) {
                    thirdPartyProcessor?.cleanup()
                    thirdPartyProcessor = null
                    logger.info("第三方音频处理器资源已释放")
                } else {
                    webrtcApm?.release()
                    webrtcApm = null
                    logger.info("WebRTC APM资源已释放")
                }
            } catch (e: Exception) {
                logger.error("释放音频处理器资源失败: ${e.message}")
            } finally {
                processorReady = false
                processorFullyInitialized = false
            }
        }
        
        _processingState.value = ProcessingState.IDLE
        logger.info("音频处理器资源已完全释放")
    }
    
    /**
     * 写入原始录音文件
     */
    private fun writeRawRecording(audioData: ShortArray) {
        try {
            if (!rawRecordingInitialized) {
                val filename = "/tmp/raw_recording.raw"
                rawRecordingFile = fopen(filename, "wb")
                if (rawRecordingFile != null) {
                    rawRecordingInitialized = true
                    logger.info("原始录音文件已创建: $filename")
                    logger.info("播放命令: aplay -f S16_LE -r ${inputFormat.sampleRate} -c ${inputFormat.channels} $filename")
                } else {
                    logger.error("无法创建原始录音文件")
                }
            }
            
            rawRecordingFile?.let { file ->
                val audioBytes = AudioUtils.shortArrayToByteArray(audioData)
                val bytesWritten = fwrite(audioBytes.refTo(0), 1u, audioBytes.size.toUInt(), file)
                fflush(file)
                
                // 🔧 完全禁用调试日志，减少日志刷屏
                // if (audioReadCounter % 5000 == 0) {
                //     logger.debug("写入原始录音: ${bytesWritten}字节, 帧数=${audioData.size}")
                // }
            }
        } catch (e: Exception) {
            logger.error("保存原始录音失败: ${e.message}")
        }
    }
    
    /**
     * 计算RMS能量
     */
    private fun calculateRms(audioData: ShortArray): Double {
        if (audioData.isEmpty()) return 0.0
        
        var sum = 0.0
        for (sample in audioData) {
            sum += (sample * sample).toDouble()
        }
        
        return kotlin.math.sqrt(sum / audioData.size) / Short.MAX_VALUE
    }
}