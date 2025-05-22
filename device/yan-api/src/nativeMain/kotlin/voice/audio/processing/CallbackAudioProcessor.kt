@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package voice.audio.processing

import kotlinx.cinterop.ExperimentalForeignApi
import voice.acquisition.portaudio.PortAudioDevice
import voice.util.LogManager
import voice.util.AudioDefaults
import voice.util.AudioUtils
import voice.detector.keyword.KeywordDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * 回调式音频处理器
 * 通过PortAudio回调直接处理音频，统一进行降噪、回声消除等处理
 */
class CallbackAudioProcessor : PortAudioDevice.AudioDataCallback {
    private val logger = LogManager.getLogger("CallbackAudioProcessor")
    
    // 状态
    private val _processingState = MutableStateFlow(ProcessingState.IDLE)
    val processingState: StateFlow<ProcessingState> = _processingState.asStateFlow()
    
    // 音频设备实例
    private val audioDevice = PortAudioDevice.getInstance()
    
    // APM实例
    var apm: WebRtcApm? = null
    
    // 音频缓冲区
    private var inputBuffer = ShortArray(0)
    private var outputBuffer = ShortArray(0)
    
    // 音频参数
    private var sampleRate = AudioDefaults.TARGET_SAMPLE_RATE
    private var channels = AudioDefaults.CHANNELS
    
    // VAD状态
    private var isVoiceDetected = false
    private var vadDebounceCounter = 0
    private val vadDebounceFrames = 3
    
    // 回调接口
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
        
        // 创建并初始化APM实例
        try {
            apm = WebRtcApm().apply {
                if (!initialize(sampleRate, channels)) {
                    logger.error("初始化WebRTC APM失败")
                    return false
                }
                
                // 配置VAD参数
                setVadThreshold(0.12f)
                setVadDebounceFrames(2)
                
                // 启用回声消除
                enableEchoCancellation(true)
            }
            
            // 更新缓冲区大小
            val frameSize = apm?.getApmFrameSize() ?: 160
            inputBuffer = ShortArray(frameSize * channels)
            outputBuffer = ShortArray(frameSize)
            
            logger.info("CallbackAudioProcessor初始化成功: ${sampleRate}Hz, ${channels}ch")
            _processingState.value = ProcessingState.IDLE
            return true
        } catch (e: Exception) {
            logger.error("初始化CallbackAudioProcessor失败: ${e.message}")
            return false
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
            
            // 设置音频回调
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
            logger.info("CallbackAudioProcessor开始处理: ${sampleRate}Hz, ${channels}ch")
            return true
        } catch (e: Exception) {
            logger.error("启动音频处理失败: ${e.message}")
            _processingState.value = ProcessingState.ERROR
            return false
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
            
            // 关闭流
            audioDevice.stop()
            
            _processingState.value = ProcessingState.IDLE
            logger.info("CallbackAudioProcessor已停止处理")
        } catch (e: Exception) {
            logger.error("停止音频处理失败: ${e.message}")
            _processingState.value = ProcessingState.ERROR
        }
    }
    
    /**
     * 设置处理后音频的回调
     */
    fun setProcessedAudioCallback(callback: (ShortArray, Int) -> Unit) {
        this.processedAudioCallback = callback
    }
    
    /**
     * 设置VAD状态变化回调
     */
    fun setVadCallback(callback: (Boolean) -> Unit) {
        this.vadCallback = callback
    }
    
    /**
     * 处理来自PortAudio的音频数据
     */
    override fun onAudioInput(data: ShortArray, frameCount: Int) {
        if (_processingState.value != ProcessingState.PROCESSING || apm == null) {
            return
        }
        
        try {
            // 检查数据帧大小
            if (frameCount <= 0 || data.isEmpty()) {
                return
            }
            
            // 使用APM处理音频
            val processedData = apm!!.processFrame(data)
            
            // 检查VAD状态
            val vadResult = apm!!.isVoiceDetected()
            
            // 音频能量
            val energy = apm!!.calculateEnergy(processedData)
            val hasSignificantEnergy = energy > 0.02 && energy < 0.9 // 避免饱和
            
            // VAD去抖动
            val oldVadState = isVoiceDetected
            if (vadResult && hasSignificantEnergy) {
                vadDebounceCounter++
                if (vadDebounceCounter >= vadDebounceFrames && !isVoiceDetected) {
                    isVoiceDetected = true
                    vadCallback?.invoke(true)
                    logger.debug("VAD状态: 有语音")
                }
            } else {
                vadDebounceCounter = 0
                if (isVoiceDetected) {
                    isVoiceDetected = false
                    vadCallback?.invoke(false)
                    logger.debug("VAD状态: 无语音")
                }
            }
            
            // 调用回调传递处理后的音频
            processedAudioCallback?.invoke(processedData, processedData.size)
            
        } catch (e: Exception) {
            logger.error("处理音频数据失败: ${e.message}")
        }
    }
    
    /**
     * 获取当前VAD状态
     */
    fun isVoiceDetected(): Boolean {
        return isVoiceDetected
    }
    
    /**
     * 释放资源
     */
    fun release() {
        if (_processingState.value == ProcessingState.PROCESSING) {
            stopProcessing()
        }
        
        apm?.release()
        apm = null
        
        _processingState.value = ProcessingState.IDLE
        logger.info("CallbackAudioProcessor资源已释放")
    }
} 