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
    
    // 音频参数 - 每次接收到新数据时可能会被安全访问
    @Volatile
    private var sampleRate = AudioDefaults.TARGET_SAMPLE_RATE
    @Volatile
    private var channels = AudioDefaults.CHANNELS
    
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
                // 释放现有实例
                apm?.release()
                apm = null
                
                // 创建新实例
                val newApm = WebRtcApm()
                if (!newApm.initialize(sampleRate, channels)) {
                    logger.error("初始化WebRTC APM失败")
                    return false
                }
                
                // 配置APM - 重新配置VAD参数，让它更严格
                newApm.setVadThreshold(0.30f)  // 大幅提高VAD阈值，从0.05f提高到0.30f
                newApm.setVadDebounceFrames(5)  // 增加去抖动帧数，从1提高到5
                newApm.enableEchoCancellation(true)
                
                apm = newApm
                
                logger.info("音频处理器初始化成功: ${sampleRate}Hz, ${channels}ch")
                _processingState.value = ProcessingState.IDLE
                return true
            } catch (e: Exception) {
                logger.error("初始化音频处理器失败: ${e.message}")
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
            if (!audioDevice.initialize("default", sampleRate)) {
                logger.error("初始化音频设备失败")
                return false
            }
            
            // 设置回调
            audioDevice.setAudioCallback(this)
            
            // 打开输入流，使用回调模式
            val success = audioDevice.openInputStreamWithCallback(-1, sampleRate, channels, this)
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
     * 在回调模式中直接处理音频，不读取流
     */
    override fun onAudioInput(data: ShortArray, frameCount: Int) {
        // 检查原始输入数据
        if (audioReadCounter++ % 1000 == 0) { // 从100改为1000，大幅减少日志
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
        if (data.isEmpty() || frameCount <= 0) return

        try {
            // 检测音频振幅
            val maxAmplitude = calculateMaxAmplitude(data, frameCount)
            
            // 减少日志频率，避免咳嗽等声音产生大量日志
            if (maxAmplitude > 100) {
                if (audioReadCounter % 100 == 0) { // 从每20帧改为每100帧记录一次
                    logger.debug("检测到有效音频，最大振幅: $maxAmplitude")
                }
            }

            // 获取本地APM副本，减少锁定和避免并发修改问题
            val localApm = synchronized(processingLock) { apm }

            if (localApm == null) {
                // 如果APM不存在，跳过处理但继续传递原始数据
                logger.warn("APM实例为空，直接传递原始音频")
                sendProcessedAudio(data, frameCount)
                return
            }
            
            // 创建输入数据副本，以确保数据安全
            val inputCopy = data.copyOf()

            // 检测语音 - 暂时只用振幅检测，WebRTC VAD太严格
            val hasVoice = try {
                val amplitude = calculateMaxAmplitude(data, frameCount)
                amplitude > 2000  // 只用振幅检测
            } catch (e: Exception) {
                false
            }
            // 每5帧才处理一次，减少处理负载
            if (audioReadCounter % 5 != 0) {
                return  // 跳过大部分帧
            }
            if (audioReadCounter % 500 == 0) { // 从100改为500，更少的调试信息
                val apmVad = try { localApm.isVoiceDetected() } catch (e: Exception) { false }
                logger.debug("VAD调试: 振幅=$maxAmplitude, APM-VAD=$apmVad, 最终结果=$hasVoice")
            }
            // 采样抽取策略: 根据是否有语音动态调整处理频率
            // 有语音时直接处理；无语音时，完全不处理
            if (!hasVoice) {
                // 没有语音时，完全跳过处理和发送
                if (audioReadCounter % 1000 == 0) {
                    logger.debug("未检测到语音，跳过处理")
                }
                return  // 直接返回，不发送任何数据到识别器
            }

            // 使用try-catch捕获所有处理异常
            try {
                // 处理音频 - 不在锁内执行，以避免长时间阻塞
                val processedData = localApm.processFrame(inputCopy)
//                val processedData = inputCopy.copyOf()  // 直接使用原始数据
                // 只有检测到语音时才发送数据
                if (hasVoice) {
                    // 添加VAD日志
                    if (audioReadCounter % 200 == 0) {
                        logger.debug("VAD检测到语音，发送数据: hasVoice=$hasVoice")
                    }

                    // 发送处理结果
                    sendProcessedAudio(data = processedData, frameCount = processedData.size)
                    
                    // 发送VAD结果
                    sendVadResult(hasVoice)
                }
            } catch (e: Exception) {
                // 处理失败，限制过多的错误日志
                val now = Clock.System.now().toEpochMilliseconds()
                if (now - lastErrorLogTime > errorLogThrottleMs) {
                    logger.error("处理音频数据异常: ${e.message}")
                    lastErrorLogTime = now
                }
                // 处理失败时不发送数据
            }
        } catch (e: Exception) {
            // 处理失败，限制过多的错误日志
            val now = Clock.System.now().toEpochMilliseconds()
            if (now - lastErrorLogTime > errorLogThrottleMs) {
                logger.error("处理音频数据异常: ${e.message}")
                lastErrorLogTime = now
            }
            // 异常时不发送数据
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
     * 获取当前的APM实例
     * 提供外部安全访问APM的方式
     */
    fun getApm(): WebRtcApm? {
        return synchronized(processingLock) { apm?.let { it } }
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
        for (i in 0 until minOf(frameCount, data.size)) {
            val amplitude = kotlin.math.abs(data[i].toInt())
            if (amplitude > maxAmplitude) {
                maxAmplitude = amplitude
            }
        }
        return maxAmplitude
    }
    
    /**
     * 释放资源
     */
    fun release() {
        if (_processingState.value == ProcessingState.PROCESSING) {
            stopProcessing()
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
            }
        }
        
        _processingState.value = ProcessingState.IDLE
        logger.info("音频处理器资源已释放")
    }
} 