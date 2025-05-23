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
    private val audioDevice = PortAudioDevice.getInstance()
    
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
                
                // 配置APM
                newApm.setVadThreshold(0.12f)
                newApm.setVadDebounceFrames(2)
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
        // 添加调试日志
        if (audioReadCounter++ % 100 == 0) {
            logger.debug("音频回调接收: ${frameCount}帧, 第${audioReadCounter}次")
        }

        if (_processingState.value != ProcessingState.PROCESSING) {
            if (audioReadCounter % 1000 == 0) {
                logger.warn("处理器未在PROCESSING状态，当前状态: ${_processingState.value}")
            }
            return
        }
        if (data.isEmpty() || frameCount <= 0) return

        try {
            // 快速质量检查
            val maxAmplitude = data.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0

            if (maxAmplitude < 50) {
                lowQualityFrames++
                if (audioReadCounter % 200 == 0) {
                    logger.debug("检测到有效音频，最大--振幅: $maxAmplitude")
                }
                if (lowQualityFrames > maxLowQualityFrames) {
                    // 太多低质量帧，跳过处理节省CPU
                    if (audioReadCounter % 500 == 0) {
                        logger.debug("跳过低质量音频帧")
                    }
                    return
                }
            } else {
                lowQualityFrames = 0
                // 添加日志显示检测到有效音频
                if (audioReadCounter % 200 == 0) {
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

            // 使用try-catch捕获所有处理异常
            try {
                // 处理音频 - 不在锁内执行，以避免长时间阻塞
                val processedData = localApm.processFrame(inputCopy)

                // 检测语音
                val hasVoice = localApm.isVoiceDetected()

                // 添加VAD日志
                if (audioReadCounter % 200 == 0 || hasVoice) {
                    logger.debug("VAD检测: hasVoice=$hasVoice")
                }

                // 发送处理结果
                sendProcessedAudio(processedData, processedData.size)

                // 发送VAD结果
                sendVadResult(hasVoice)
            } catch (e: Exception) {
                // 处理失败，使用原始数据，限制过多的错误日志
                val now = Clock.System.now().toEpochMilliseconds()
                if (now - lastErrorLogTime > errorLogThrottleMs) {
                    logger.error("处理音频数据异常: ${e.message}")
                    lastErrorLogTime = now
                }

                // 即使处理失败，也发送原始数据，确保音频流不中断
                sendProcessedAudio(data, frameCount)
            }
        } catch (e: Exception) {
            // 处理失败，使用原始数据，限制过多的错误日志
            val now = Clock.System.now().toEpochMilliseconds()
            if (now - lastErrorLogTime > errorLogThrottleMs) {
                logger.error("处理音频数据异常: ${e.message}")
                lastErrorLogTime = now
            }

            // 即使处理失败，也发送原始数据，确保音频流不中断
            sendProcessedAudio(data, frameCount)
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