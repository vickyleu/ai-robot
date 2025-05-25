@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package voice.audio.processing

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
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.fflush
import platform.posix.fclose

/**
 * 回调式音频处理器
 * 直接在PortAudio回调中使用WebRTC APM处理音频，支持VAD检测
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
    /*private*/ val audioDevice = PortAudioDevice.getInstance()
    
    // 音频处理锁
    private val processingLock = SynchronizedObject()
    
    // APM实例 - 私有管理，避免外部直接访问
    @Volatile
    private var apm: WebRtcApm? = null
    
    // APM就绪状态检查 - 双重验证
    @Volatile
    private var apmReady = false
    
    @Volatile
    private var apmFullyInitialized = false
    
    // 音频参数 - 每次接收到新数据时可能会被安全访问
    @Volatile
    private var sampleRate = AudioDefaults.INPUT_DEVICE_SAMPLE_RATE
    @Volatile
    private var channels = AudioDefaults.INPUT_DEVICE_CHANNELS
    
    // 上一次处理异常的时间，用于限制日志频率
    private var lastErrorLogTime = 0L
    private val errorLogThrottleMs = 1000 // 限制错误日志为每秒最多一条
    // 音频读取计数器
    private var audioReadCounter = 0
    private val minValidRms = 0.01 // 从0.02降低到0.01，适应低振幅音频设备
    
    // 回调函数 - 保持与现有代码兼容的命名，增加同步保护
    private val callbackLock = SynchronizedObject()
    private var processedAudioCallback: ((ShortArray, Int) -> Unit)? = null
    private var vadCallback: ((Boolean) -> Unit)? = null
    
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
    fun initialize(sampleRate: Int, channels: Int): Boolean {
        this.sampleRate = sampleRate
        this.channels = channels
        
        synchronized(processingLock) {
            try {
                // 严格重置APM就绪状态
                apmReady = false
                apmFullyInitialized = false
                
                // 释放现有实例
                apm?.release()
                apm = null
                
                // 创建新实例
                val newApm = WebRtcApm()
                if (!newApm.initialize(sampleRate, channels)) {
                    logger.error("初始化WebRTC APM失败")
                    return false
                }
                
                // 配置APM - 调整VAD参数
                newApm.setVadThreshold(0.01f)
                newApm.setVadDebounceFrames(1)
                newApm.enableEchoCancellation(false)
                
                apm = newApm
                
                // 双重验证APM完全就绪
                val apmHandle = newApm.getApmHandle()
                if (apmHandle == null) {
                    logger.error("APM句柄为空，初始化失败")
                    apmReady = false
                    apmFullyInitialized = false
                    return false
                }
                
                // 确保APM完全就绪后才允许音频处理
                apmReady = true
                apmFullyInitialized = true
                
                logger.info("音频处理器初始化成功: 输入=${sampleRate}Hz/${channels}ch, APM=${AudioDefaults.WEBRTC_APM_SAMPLE_RATE}Hz/${AudioDefaults.WEBRTC_APM_CHANNELS}ch")
                _processingState.value = ProcessingState.IDLE
                return true
            } catch (e: Exception) {
                logger.error("初始化音频处理器失败: ${e.message}")
                apmReady = false
                apmFullyInitialized = false
                _processingState.value = ProcessingState.ERROR
                return false
            }
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
            if (!audioDevice.initialize(sampleRate)) {
                logger.error("初始化音频设备失败")
                return false
            }
            
            // 设置回调
            audioDevice.setAudioCallback(this)
            
            // 打开输入流，使用回调模式
            val success = audioDevice.openInputStreamWithCallback(sampleRate, channels, this)
            if (!success) {
                logger.error("打开音频输入流失败")
                return false
            }
            
            // 启动设备
            audioDevice.start()
            
            _processingState.value = ProcessingState.PROCESSING
            logger.info("音频处理器开始工作: ${sampleRate}Hz, ${channels}ch")
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
    override fun onAudioInput(data: ShortArray, frameCount: Int) {
        // 保存原始录音数据到文件
        try {
            if (!rawRecordingInitialized) {
                val filename = "/tmp/raw_recording.raw"
                rawRecordingFile = fopen(filename, "wb")
                if (rawRecordingFile != null) {
                    rawRecordingInitialized = true
                    logger.info("原始录音文件已创建: $filename")
                    logger.info("播放命令: aplay -f S16_LE -r ${sampleRate} -c ${channels} $filename")
                } else {
                    logger.error("无法创建原始录音文件")
                }
            }
            
            rawRecordingFile?.let { file ->
                val audioBytes = AudioUtils.shortArrayToByteArray(data)
                val bytesWritten = fwrite(audioBytes.refTo(0), 1u, audioBytes.size.toUInt(), file)
                fflush(file)
                
                if (audioReadCounter % 1000 == 0) {
                    logger.debug("写入原始录音: ${bytesWritten}字节, 帧数=$frameCount")
                }
            }
        } catch (e: Exception) {
            logger.error("保存原始录音失败: ${e.message}")
        }
        
        // 检查原始输入数据
        if (audioReadCounter++ % 1000 == 0) {
            val rawSamples = data.take(10).joinToString(", ")
            val rawMax = data.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
            logger.debug("原始音频: 前10个样本=$rawSamples, 最大振幅=$rawMax")
        }

        if (_processingState.value != ProcessingState.PROCESSING) {
            if (audioReadCounter % 1000 == 0) {
                logger.warn("处理器未在PROCESSING状态，当前状态: ${_processingState.value}")
            }
            return
        }
        
        // 严格检查APM是否完全就绪 - 双重验证，防止背景噪音进入识别
        if (!apmReady || !apmFullyInitialized) {
            if (audioReadCounter % 1000 == 0) {
                logger.debug("APM未完全就绪，跳过音频处理 (apmReady=$apmReady, apmFullyInitialized=$apmFullyInitialized)")
            }
            return
        }
        
        // 获取本地APM副本
        val localApm = synchronized(processingLock) { apm }

        if (localApm == null) {
            logger.warn("APM实例为空，直接传递原始音频")
            sendProcessedAudio(data, frameCount)
            return
        }
        
        // 双重验证APM实例的有效性
        val apmHandle = localApm.getApmHandle()
        if (apmHandle == null) {
            if (audioReadCounter % 1000 == 0) {
                logger.warn("APM句柄为空，跳过处理")
            }
            return
        }
        
        if (data.isEmpty() || frameCount <= 0) return

        try {
            // 检测音频振幅
            val maxAmplitude = calculateMaxAmplitude(data, frameCount)
            
            if (maxAmplitude > 100) {
                if (audioReadCounter % 100 == 0) {
                    logger.debug("检测到有效音频，最大振幅: $maxAmplitude")
                }
            }

            // 创建输入数据副本
            val inputCopy = data.copyOf()

            // 检测语音 - 使用APM VAD和振幅阈值
            val amplitude = calculateMaxAmplitude(data, frameCount)
            val apmVad = try { localApm.isVoiceDetected() } catch (e: Exception) { 
                logger.error("VAD检测异常: ${e.message}")
                false 
            }
            
            // 注意：键盘检测现在完全由WebRTC APM内部处理，我们不需要手动检测
            // APM的isVoiceDetected()方法内部会自动处理键盘声抑制
            
            // 简化的VAD逻辑：主要依赖APM的判断
            val hasVoice = if (apmVad) {
                amplitude > 100  // 降低阈值，因为APM已经过滤了键盘声
            } else {
                amplitude > 800  // APM认为不是语音，需要更高阈值才能覆盖
            }
            
            if (audioReadCounter % 200 == 0) {
                logger.debug("VAD状态: 振幅=$amplitude, APM-VAD=$apmVad, 最终结果=$hasVoice")
            }
            
            // 处理检测到的语音
            if (hasVoice) {
                try {
                    val processedData = localApm.processFrame(inputCopy)
                    
                    sendProcessedAudio(data = processedData, frameCount = processedData.size)
                    sendVadResult(hasVoice)
                    
                    if (audioReadCounter % 100 == 0) {
                        logger.debug("成功处理语音: 振幅=$amplitude, 处理后数据大小=${processedData.size}")
                    }
                } catch (e: Exception) {
                    val now = Clock.System.now().toEpochMilliseconds()
                    if (now - lastErrorLogTime > errorLogThrottleMs) {
                        logger.error("处理音频数据异常: ${e.message}")
                        lastErrorLogTime = now
                    }
                }
            } else {
                if (audioReadCounter % 1000 == 0) {
                    logger.debug("未检测到语音，跳过处理: 振幅=$amplitude")
                }
            }
        } catch (e: Exception) {
            val now = Clock.System.now().toEpochMilliseconds()
            if (now - lastErrorLogTime > errorLogThrottleMs) {
                logger.error("处理音频数据异常: ${e.message}")
                lastErrorLogTime = now
            }
        }
    }
    
    /**
     * 安全发送处理后的音频数据
     */
    private fun sendProcessedAudio(data: ShortArray, frameCount: Int) {
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
    fun setProcessedAudioCallback(callback: (ShortArray, Int) -> Unit) {
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
     * 检查当前是否检测到语音
     * 直接查询当前APM实例的VAD状态
     */
    fun isVoiceDetected(): Boolean {
        // 使用安全的线程安全访问
        return synchronized(processingLock) {
            apm?.isVoiceDetected() ?: false
        }
    }
    
    /**
     * 获取APM实例 - 新增
     * @return WebRtcApm实例，可能为null
     */
    fun getApm(): WebRtcApm? {
        return synchronized(processingLock) { apm }
    }
    
    /**
     * 设置回声消除状态
     * 提供外部控制回声消除的方式
     */
    fun setEchoCancellationEnabled(enabled: Boolean) {
        synchronized(processingLock) {
            apm?.enableEchoCancellation(enabled)
        }
    }
    
    /**
     * 停止处理
     */
    fun stopProcessing() {
        if (_processingState.value != ProcessingState.PROCESSING) {
            return
        }
        
        try {
            // 移除回调
            audioDevice.setAudioCallback(null)
            
            // 停止设备
            audioDevice.stop()
            
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
    fun release() {
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
        }
        
        // 释放APM
        synchronized(processingLock) {
            try {
                apm?.release()
            } finally {
                apm = null
                apmReady = false
                apmFullyInitialized = false
            }
        }
        
        _processingState.value = ProcessingState.IDLE
        logger.info("音频处理器资源已释放")
    }
} 