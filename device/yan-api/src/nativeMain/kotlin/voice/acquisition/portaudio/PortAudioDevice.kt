@file:OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)

package voice.hal

import com.airobot.portaudiointerop.PaStreamParameters
import com.airobot.portaudiointerop.Pa_CloseStream
import com.airobot.portaudiointerop.Pa_GetDeviceCount
import com.airobot.portaudiointerop.Pa_GetDeviceInfo
import com.airobot.portaudiointerop.Pa_GetErrorText
import com.airobot.portaudiointerop.Pa_GetHostApiInfo
import com.airobot.portaudiointerop.Pa_Initialize
import com.airobot.portaudiointerop.Pa_OpenDefaultStream
import com.airobot.portaudiointerop.Pa_OpenStream
import com.airobot.portaudiointerop.Pa_ReadStream
import com.airobot.portaudiointerop.Pa_StartStream
import com.airobot.portaudiointerop.Pa_StopStream
import com.airobot.portaudiointerop.Pa_Terminate
import com.airobot.portaudiointerop.Pa_WriteStream
import com.airobot.portaudiointerop.paFramesPerBufferUnspecified
import com.airobot.portaudiointerop.paInputOverflowed
import com.airobot.portaudiointerop.paInt16
import com.airobot.portaudiointerop.paNoError
import com.airobot.portaudiointerop.paNoFlag
import com.airobot.portaudiointerop.paOutputUnderflowed
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.free
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock.System
import voice.interf.audio.AudioPlayer
import voice.util.LogManager
import kotlin.time.ExperimentalTime
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.size_t
import kotlinx.coroutines.launch
import platform.posix.rewind

/**
 * PortAudio音频设备实现类
 * 提供基于PortAudio的音频设备功能
 */
class PortAudioDevice : AudioDevice, AudioPlayer {
    private val logger = LogManager.getLogger("PortAudioDevice")
    
    // 设备状态
    private val _deviceState = MutableStateFlow(AudioDevice.AudioDeviceState.IDLE)
    override val deviceState: StateFlow<AudioDevice.AudioDeviceState> = _deviceState.asStateFlow()
    
    // 播放状态
    private val _playbackState = MutableStateFlow(AudioPlayer.PlaybackState.IDLE)
    override val playbackState: StateFlow<AudioPlayer.PlaybackState> = _playbackState.asStateFlow()
    
    // 存储设备信息
    private var selectedInputDeviceIndex = -1
    private var selectedOutputDeviceIndex = -1
    
    // PortAudio初始化状态
    private var portAudioInitialized = false
    
    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Default)
    
    // 音频流管理
    private val audioMutex = Mutex()
    private var inputStreamPtr = nativeHeap.alloc<COpaquePointerVar>()
    private var outputStreamPtr = nativeHeap.alloc<COpaquePointerVar>()
    
    // 当前采样率
    private var currentSampleRate = 16000
    
    // Linux设备选择器
    private val deviceSelector = LinuxAudioDeviceSelector()
    
    // 音频播放缓冲
    private val audioPlayBuffer = ShortArray(8192)
    private var audioPlayBufferPos = 0
    private val minPlayFrames = 512  // 较小的最小播放帧数，减少延迟
    
    // 语音活动检测器
    private val voiceDetector = VoiceActivityDetector()
    
    // 错误恢复计数器
    private var consecutiveErrors = 0
    private var lastErrorTimestamp = 0L
    private val maxConsecutiveErrors = 5
    private val errorResetIntervalMs = 5000L  // 5秒内无错误则重置计数器
    
    // 用于计数音频读取次数，避免过度日志记录
    private var audioReadCounter = 0
    
    /**
     * 初始化音频设备
     * @param deviceName 设备名称（暂未使用）
     * @param sampleRate 采样率
     * @return 初始化是否成功
     */
    override fun initialize(deviceName: String, sampleRate: Int): Boolean {
        if (_deviceState.value == AudioDevice.AudioDeviceState.INITIALIZING || 
            _deviceState.value == AudioDevice.AudioDeviceState.ACTIVE) {
            logger.warn("音频设备已经初始化或正在初始化中")
            return true
        }
        
        _deviceState.value = AudioDevice.AudioDeviceState.INITIALIZING
        currentSampleRate = sampleRate
        
        try {
            // 对于Linux/ALSA系统，尝试优化配置
            if (deviceSelector.isRaspberryPi()) {
                logger.info("检测到Linux/树莓派系统，尝试优化音频配置...")
                deviceSelector.fixAlsaConfig()
            }
            
            // 初始化PortAudio
            val result = Pa_Initialize()
            if (result != paNoError) {
                logger.error("无法初始化PortAudio: ${Pa_GetErrorText(result)?.toKString()}")
                _deviceState.value = AudioDevice.AudioDeviceState.ERROR
                return false
            }
            
            logger.info("PortAudio初始化成功")
            
            // 列举设备
            listAudioDevices()
            
            portAudioInitialized = true
            _deviceState.value = AudioDevice.AudioDeviceState.READY
            return true
        } catch (e: Exception) {
            logger.error("初始化音频设备失败: ${e.message}")
            e.printStackTrace()
            _deviceState.value = AudioDevice.AudioDeviceState.ERROR
            return false
        }
    }
    
    /**
     * 开始音频流
     * @return 是否成功启动
     */
    override fun start(): Boolean {
        if (_deviceState.value != AudioDevice.AudioDeviceState.READY) {
            logger.warn("音频设备未就绪，无法启动。当前状态: ${_deviceState.value}")
            return false
        }
        
        // 只是将状态设为活动，实际的流操作由openInputStream和openOutputStream方法进行
        _deviceState.value = AudioDevice.AudioDeviceState.ACTIVE
        return true
    }
    
    /**
     * 停止音频流
     */
    override fun stop() {
        if (_deviceState.value != AudioDevice.AudioDeviceState.ACTIVE) {
            logger.warn("音频设备未处于活动状态，无需停止。当前状态: ${_deviceState.value}")
            return
        }
        
        // 将状态设为就绪，但不关闭流
        _deviceState.value = AudioDevice.AudioDeviceState.READY
    }
    
    /**
     * 设置采样率
     * @param sampleRate 新的采样率
     * @return 是否成功设置
     */
    override fun setSampleRate(sampleRate: Int): Boolean {
        if (sampleRate <= 0) {
            logger.error("无效的采样率: $sampleRate")
            return false
        }
        
        // 只有在设备闲置或就绪状态才能更改采样率
        if (_deviceState.value != AudioDevice.AudioDeviceState.IDLE && 
            _deviceState.value != AudioDevice.AudioDeviceState.READY) {
            logger.warn("无法在当前状态下更改采样率: ${_deviceState.value}")
            return false
        }
        
        currentSampleRate = sampleRate
        logger.info("采样率已设置为: $sampleRate")
        return true
    }
    
    /**
     * 获取采样率
     * @return 当前采样率
     */
    override fun getSampleRate(): Int {
        return currentSampleRate
    }
    
    /**
     * 列举可用的音频设备
     * @return 输入设备索引和输出设备索引的对
     */
    override fun listAudioDevices(): Pair<Int, Int> {
        if (!portAudioInitialized) {
            logger.warn("PortAudio未初始化，无法列举设备")
            return Pair(-1, -1)
        }
        
        try {
            val deviceCount = Pa_GetDeviceCount()
            logger.info("发现 $deviceCount 个音频设备")
            
            if (deviceCount <= 0) {
                logger.warn("未找到音频设备")
                return Pair(-1, -1)
            }
            
            // 打印所有设备信息，便于调试
            for (i in 0 until deviceCount) {
                val info = Pa_GetDeviceInfo(i)?.pointed ?: continue
                val apiInfo = Pa_GetHostApiInfo(info.hostApi)?.pointed
                logger.info("设备 $i: ${info.name?.toKString() ?: "未知"}")
                logger.info("  API: ${apiInfo?.name?.toKString() ?: "未知"}")
                logger.info("  输入通道: ${info.maxInputChannels}, 输出通道: ${info.maxOutputChannels}")
                logger.info("  默认采样率: ${info.defaultSampleRate}")
            }
            
            // 首先尝试使用Linux设备选择器（适用于树莓派等设备）
            if (deviceSelector.isRaspberryPi()) {
                val inputDeviceId = deviceSelector.getRecommendedRecordingDevice()
                val outputDeviceId = deviceSelector.getRecommendedPlaybackDevice()
                
                if (inputDeviceId >= 0 || outputDeviceId >= 0) {
                    logger.info("使用Linux设备选择器推荐的设备: 输入=$inputDeviceId, 输出=$outputDeviceId")
                    selectedInputDeviceIndex = inputDeviceId
                    selectedOutputDeviceIndex = outputDeviceId
                    return Pair(inputDeviceId, outputDeviceId)
                }
            }
            
            // 如果Linux选择器未找到合适设备，查找默认设备
            var bestInputDeviceIndex = -1
            var bestOutputDeviceIndex = -1
            
            for (i in 0 until deviceCount) {
                val info = Pa_GetDeviceInfo(i)?.pointed ?: continue
                val hostInfo = Pa_GetHostApiInfo(info.hostApi)?.pointed
                
                // 检查是否为默认输入设备
                if (hostInfo != null && i == hostInfo.defaultInputDevice) {
                    bestInputDeviceIndex = i
                    logger.info("找到默认输入设备: ${info.name?.toKString()}")
                }
                
                // 检查是否为默认输出设备
                if (hostInfo != null && i == hostInfo.defaultOutputDevice) {
                    bestOutputDeviceIndex = i
                    logger.info("找到默认输出设备: ${info.name?.toKString()}")
                }
            }
            
            // 如果找不到默认设备，查找任何可用设备
            if (bestInputDeviceIndex == -1 || bestOutputDeviceIndex == -1) {
                for (i in 0 until deviceCount) {
                    val info = Pa_GetDeviceInfo(i)?.pointed ?: continue
                    
                    // 检查输入通道
                    if (bestInputDeviceIndex == -1 && info.maxInputChannels > 0) {
                        bestInputDeviceIndex = i
                        logger.info("找到输入设备: ${info.name?.toKString()}")
                    }
                    
                    // 检查输出通道
                    if (bestOutputDeviceIndex == -1 && info.maxOutputChannels > 0) {
                        bestOutputDeviceIndex = i
                        logger.info("找到输出设备: ${info.name?.toKString()}")
                    }
                    
                    // 如果同时找到了输入和输出设备，可以提前结束搜索
                    if (bestInputDeviceIndex != -1 && bestOutputDeviceIndex != -1) {
                        break
                    }
                }
            }
            
            // 保存选择的设备
            selectedInputDeviceIndex = bestInputDeviceIndex
            selectedOutputDeviceIndex = bestOutputDeviceIndex
            
            return Pair(bestInputDeviceIndex, bestOutputDeviceIndex)
        } catch (e: Exception) {
            logger.error("列举设备时出错: ${e.message}")
            e.printStackTrace()
            return Pair(-1, -1)
        }
    }
    
    /**
     * 打开音频输入流
     * @param deviceIndex 设备索引，-1表示默认设备
     * @param sampleRate 采样率
     * @param channels 通道数
     * @return 是否成功打开
     */
    override suspend fun openInputStream(deviceIndex: Int, sampleRate: Int, channels: Int): Boolean {
        if (!portAudioInitialized) {
            logger.error("PortAudio未初始化")
            return false
        }
        
        return try {
            audioMutex.withLock {
                logger.info("尝试打开音频输入流...")
                
                // 关闭已存在的流
                if (inputStreamPtr.value != null) {
                    Pa_StopStream(inputStreamPtr.value)
                    Pa_CloseStream(inputStreamPtr.value)
                    inputStreamPtr.value = null
                }
                
                // 获取实际设备索引
                val actualDeviceIndex = if (deviceIndex >= 0) deviceIndex else selectedInputDeviceIndex
                
                // 检查是否需要使用特定ALSA设备（在Linux上）
                var result = paNoError
                var alsaStreamInfo: AlsaStreamInfo? = null
                
                if (deviceSelector.isRaspberryPi()) {
                    // 使用ALSA特定设置
                    val alsaDeviceString = deviceSelector.getALSADeviceString(actualDeviceIndex, true)
                    logger.info("使用ALSA输入设备: $alsaDeviceString")
                    
                    // 创建ALSA流信息
                    alsaStreamInfo = AlsaStreamInfo(alsaDeviceString)
                    
                    // 设置输入参数
                    memScoped {
                        val inputParams = nativeHeap.alloc<PaStreamParameters>()
                        inputParams.device = actualDeviceIndex
                        inputParams.channelCount = channels
                        inputParams.sampleFormat = paInt16
                        inputParams.suggestedLatency = 0.05  // 50ms延迟
                        inputParams.hostApiSpecificStreamInfo = alsaStreamInfo.createStreamInfo()
                        
                        // 使用Pa_OpenStream
                        result = Pa_OpenStream(
                            inputStreamPtr.ptr,
                            inputParams.ptr,  // 输入参数
                            null,             // 无输出参数
                            sampleRate.toDouble(),
                            paFramesPerBufferUnspecified.toUInt(),
                            paNoFlag.toUInt(),
                            null,             // 无回调
                            null              // 无用户数据
                        )
                        
                        // 释放ALSA流信息
                        if (inputParams.hostApiSpecificStreamInfo != null) {
                            alsaStreamInfo.releaseStreamInfo(inputParams.hostApiSpecificStreamInfo!!.reinterpret())
                            inputParams.hostApiSpecificStreamInfo = null
                        }
                    }
                } else {
                    // 使用标准方式
                    logger.info("使用输入设备索引: $actualDeviceIndex")
                    
                    // 根据是否指定了设备索引，选择不同的打开方式
                    result = if (actualDeviceIndex >= 0) {
                        // 使用指定设备
                        memScoped {
                            val inputParams = nativeHeap.alloc<PaStreamParameters>()
                            inputParams.device = actualDeviceIndex
                            inputParams.channelCount = channels
                            inputParams.sampleFormat = paInt16
                            inputParams.suggestedLatency = 0.05  // 50ms延迟
                            inputParams.hostApiSpecificStreamInfo = null
                            
                            Pa_OpenStream(
                                inputStreamPtr.ptr,
                                inputParams.ptr,  // 输入参数
                                null,             // 无输出参数
                                sampleRate.toDouble(),
                                paFramesPerBufferUnspecified.toUInt(),
                                paNoFlag.toUInt(),
                                null,             // 无回调
                                null              // 无用户数据
                            )
                        }
                    } else {
                        // 使用默认设备
                        Pa_OpenDefaultStream(
                            inputStreamPtr.ptr,
                            channels,           // 输入通道数
                            0,                  // 无输出通道
                            paInt16,            // 采样格式
                            sampleRate.toDouble(),
                            paFramesPerBufferUnspecified.toUInt(),
                            null,               // 无回调函数
                            null                // 无用户数据
                        )
                    }
                }
                
                // 处理结果
                if (result == paNoError) {
                    // 启动流
                    val startResult = Pa_StartStream(inputStreamPtr.value)
                    if (startResult == paNoError) {
                        logger.info("音频输入流打开并启动成功")
                        
                        // 重置语音检测器
                        voiceDetector.reset()
                        
                        return@withLock true
                    } else {
                        logger.error("无法启动音频输入流: ${Pa_GetErrorText(startResult)?.toKString()}")
                        Pa_CloseStream(inputStreamPtr.value)
                        inputStreamPtr.value = null
                        return@withLock false
                    }
                } else {
                    logger.error("无法打开音频输入流: ${Pa_GetErrorText(result)?.toKString()}")
                    return@withLock false
                }
            }
        } catch (e: Exception) {
            logger.error("打开音频输入流时出错: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
    
    /**
     * 打开音频输出流
     * @param deviceIndex 设备索引，-1表示默认设备
     * @param sampleRate 采样率
     * @param channels 通道数
     * @return 是否成功打开
     */
    override suspend fun openOutputStream(deviceIndex: Int, sampleRate: Int, channels: Int): Boolean {
        if (!portAudioInitialized) {
            logger.error("PortAudio未初始化")
            return false
        }
        
        return try {
            audioMutex.withLock {
                logger.info("尝试打开音频输出流...")
                
                // 关闭已存在的流
                if (outputStreamPtr.value != null) {
                    Pa_StopStream(outputStreamPtr.value)
                    Pa_CloseStream(outputStreamPtr.value)
                    outputStreamPtr.value = null
                }
                
                // 获取实际设备索引
                val actualDeviceIndex = if (deviceIndex >= 0) deviceIndex else selectedOutputDeviceIndex
                
                // 检查是否需要使用特定ALSA设备（在Linux上）
                var result = paNoError
                var alsaStreamInfo: AlsaStreamInfo? = null
                
                if (deviceSelector.isRaspberryPi()) {
                    // 使用ALSA特定设置
                    val alsaDeviceString = deviceSelector.getALSADeviceString(actualDeviceIndex, false)
                    logger.info("使用ALSA输出设备: $alsaDeviceString")
                    
                    // 创建ALSA流信息
                    alsaStreamInfo = AlsaStreamInfo(alsaDeviceString)
                    
                    // 设置输出参数
                    memScoped {
                        val outputParams = nativeHeap.alloc<PaStreamParameters>()
                        outputParams.device = actualDeviceIndex
                        outputParams.channelCount = channels
                        outputParams.sampleFormat = paInt16
                        outputParams.suggestedLatency = 0.1  // 100ms延迟，输出可以稍微高一些
                        outputParams.hostApiSpecificStreamInfo = alsaStreamInfo.createStreamInfo()
                        
                        // 使用Pa_OpenStream
                        result = Pa_OpenStream(
                            outputStreamPtr.ptr,
                            null,               // 无输入参数
                            outputParams.ptr,   // 输出参数
                            sampleRate.toDouble(),
                            1024u,              // 使用较小的缓冲区以减少延迟
                            paNoFlag.toUInt(),
                            null,               // 无回调
                            null                // 无用户数据
                        )
                        
                        // 释放ALSA流信息
                        if (outputParams.hostApiSpecificStreamInfo != null) {
                            alsaStreamInfo.releaseStreamInfo(outputParams.hostApiSpecificStreamInfo!!.reinterpret())
                            outputParams.hostApiSpecificStreamInfo = null
                        }
                    }
                } else {
                    // 使用标准方式
                    logger.info("使用输出设备索引: $actualDeviceIndex")
                    
                    // 根据是否指定了设备索引，选择不同的打开方式
                    result = if (actualDeviceIndex >= 0) {
                        // 使用指定设备
                        memScoped {
                            val outputParams = nativeHeap.alloc<PaStreamParameters>()
                            outputParams.device = actualDeviceIndex
                            outputParams.channelCount = channels
                            outputParams.sampleFormat = paInt16
                            outputParams.suggestedLatency = 0.1  // 100ms延迟，输出可以稍微高一些
                            outputParams.hostApiSpecificStreamInfo = null
                            
                            Pa_OpenStream(
                                outputStreamPtr.ptr,
                                null,               // 无输入参数
                                outputParams.ptr,   // 输出参数
                                sampleRate.toDouble(),
                                1024u,              // 使用较小的缓冲区以减少延迟
                                paNoFlag.toUInt(),
                                null,               // 无回调
                                null                // 无用户数据
                            )
                        }
                    } else {
                        // 使用默认设备
                        Pa_OpenDefaultStream(
                            outputStreamPtr.ptr,
                            0,                  // 无输入通道
                            channels,           // 输出通道数
                            paInt16,            // 采样格式
                            sampleRate.toDouble(),
                            1024u,              // 使用较小的缓冲区以减少延迟
                            null,               // 无回调函数
                            null                // 无用户数据
                        )
                    }
                }
                
                // 处理结果
                if (result == paNoError) {
                    // 启动流
                    val startResult = Pa_StartStream(outputStreamPtr.value)
                    if (startResult == paNoError) {
                        logger.info("音频输出流打开并启动成功")
                        
                        // 清空播放缓冲区
                        audioPlayBufferPos = 0
                        
                        return@withLock true
                    } else {
                        logger.error("无法启动音频输出流: ${Pa_GetErrorText(startResult)?.toKString()}")
                        Pa_CloseStream(outputStreamPtr.value)
                        outputStreamPtr.value = null
                        return@withLock false
                    }
                } else {
                    logger.error("无法打开音频输出流: ${Pa_GetErrorText(result)?.toKString()}")
                    return@withLock false
                }
            }
        } catch (e: Exception) {
            logger.error("打开音频输出流时出错: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
    
    /**
     * 读取音频数据，加入人声过滤和静音检测
     * @param buffer 数据缓冲区
     * @param frameCount 帧数
     * @return 读取的帧数，负值表示错误
     */
    override suspend fun readAudio(buffer: CPointer<ShortVar>, frameCount: Int): Int {
        if (inputStreamPtr.value == null) {
            logger.error("音频输入流未打开")
            return -1
        }
        
        try {
            // 读取原始音频数据
            val result = Pa_ReadStream(inputStreamPtr.value, buffer, frameCount.toUInt())
            
            // 记录当前时间戳，用于错误处理
            val currentTimeMs = System.now().toEpochMilliseconds()
            
            // 处理错误
            if (result != paNoError && result != paInputOverflowed) {
                // 检查是否需要重置错误计数器
                if (currentTimeMs - lastErrorTimestamp > errorResetIntervalMs) {
                    consecutiveErrors = 0
                }
                
                lastErrorTimestamp = currentTimeMs
                consecutiveErrors++
                
                val errorMsg = Pa_GetErrorText(result)?.toKString() ?: "未知错误"
                logger.error("读取音频数据失败 ($consecutiveErrors): $errorMsg")
                
                // 如果连续错误太多，尝试恢复
                if (consecutiveErrors >= maxConsecutiveErrors) {
                    logger.warn("检测到连续错误，尝试恢复音频流...")
                    consecutiveErrors = 0
                    
                    // 尝试重置流
                    audioMutex.withLock {
                        if (inputStreamPtr.value != null) {
                            // 停止并关闭流，然后重新打开
                            Pa_StopStream(inputStreamPtr.value)
                            Pa_CloseStream(inputStreamPtr.value)
                            inputStreamPtr.value = null
                            
                            // 短暂延迟，给设备一个恢复的机会
                            kotlinx.coroutines.delay(500)
                            
                            // 重新打开输入流
                            openInputStream(selectedInputDeviceIndex, currentSampleRate, 1)
                        }
                    }
                }
                
                return -1
            }
            
            // 重置错误计数
            if (consecutiveErrors > 0) {
                consecutiveErrors = 0
            }
            
            // 将数据拷贝到一个ShortArray以便进行声音分析
            val audioData = ShortArray(frameCount)
            for (i in 0 until frameCount) {
                audioData[i] = buffer[i]
            }
            
            // 人声检测：如果不是人声，将音量降低而不是完全过滤
            val isVoice = voiceDetector.detectVoice(audioData)
            if (!isVoice) {
                // 音频能量检查：如果有声音但不是人声，降低音量而不是完全过滤
                val isSilence = voiceDetector.detectSilence(audioData)
                
                if (!isSilence) {
                    // 将音量降低到25%，但不完全静音，这样可以保留一些环境声音
                    for (i in 0 until frameCount) {
                        buffer[i] = (buffer[i].toInt() * 0.25).toInt().toShort()
                    }
                    
                    if (audioReadCounter++ % 100 == 0) {
                        logger.debug("检测到非人声音频，已降低音量")
                    }
                } else {
                    // 是静音，清零
                    for (i in 0 until frameCount) {
                        buffer[i] = 0
                    }
                    
                    if (audioReadCounter++ % 100 == 0) {
                        logger.debug("检测到静音")
                    }
                }
            }
            
            return frameCount
        } catch (e: Exception) {
            logger.error("读取音频数据异常: ${e.message}")
            return -1
        }
    }
    
    /**
     * 写入音频数据
     * @param buffer 数据缓冲区
     * @param frameCount 帧数
     * @return 写入的帧数，负值表示错误
     */
    suspend fun writeAudio(buffer: CPointer<ShortVar>, frameCount: Int): Int {
        if (outputStreamPtr.value == null) {
            logger.error("音频输出流未打开")
            return -1
        }
        
        return try {
            audioMutex.withLock {
                // 如果帧数太小，积累到缓冲区
                if (frameCount < minPlayFrames && audioPlayBufferPos + frameCount < audioPlayBuffer.size) {
                    for (i in 0 until frameCount) {
                        audioPlayBuffer[audioPlayBufferPos + i] = buffer[i]
                    }
                    audioPlayBufferPos += frameCount
                    return@withLock frameCount
                }
                
                // 如果有缓冲数据，先播放缓冲数据
                if (audioPlayBufferPos > 0) {
                    val totalFrames = audioPlayBufferPos + frameCount
                    val tempBuffer = nativeHeap.allocArray<ShortVar>(totalFrames)
                    
                    // 复制缓冲区数据
                    for (i in 0 until audioPlayBufferPos) {
                        tempBuffer[i] = audioPlayBuffer[i]
                    }
                    
                    // 复制当前数据
                    for (i in 0 until frameCount) {
                        tempBuffer[audioPlayBufferPos + i] = buffer[i]
                    }
                    
                    // 播放合并后的数据
                    val result = Pa_WriteStream(outputStreamPtr.value, tempBuffer, totalFrames.toUInt())
                    
                    // 释放临时缓冲区
                    nativeHeap.free(tempBuffer)
                    
                    // 清空播放缓冲区
                    audioPlayBufferPos = 0
                    
                    if (result == paNoError || result == paOutputUnderflowed) {
                        return@withLock totalFrames
                    } else {
                        logger.error("播放音频失败: ${Pa_GetErrorText(result)?.toKString()}")
                        return@withLock -1
                    }
                }
                
                // 直接播放
                val result = Pa_WriteStream(outputStreamPtr.value, buffer, frameCount.toUInt())
                
                if (result == paNoError || result == paOutputUnderflowed) {
                    return@withLock frameCount
                } else {
                    logger.error("播放音频失败: ${Pa_GetErrorText(result)?.toKString()}")
                    return@withLock -1
                }
            }
        } catch (e: Exception) {
            logger.error("播放音频异常: ${e.message}")
            return -1
        }
    }
    
    /**
     * 关闭音频流
     */
    override suspend fun closeStreams() {
        audioMutex.withLock {
            // 关闭输入流
            if (inputStreamPtr.value != null) {
                try {
                    Pa_StopStream(inputStreamPtr.value)
                    Pa_CloseStream(inputStreamPtr.value)
                    logger.info("输入音频流已关闭")
                } catch (e: Exception) {
                    logger.warn("关闭输入流时出错: ${e.message}")
                }
                inputStreamPtr.value = null
            }
            
            // 如果播放缓冲区有剩余数据，先播放完
            if (outputStreamPtr.value != null && audioPlayBufferPos > 0) {
                try {
                    logger.info("播放剩余缓冲区数据: $audioPlayBufferPos 帧")
                    val tempBuffer = nativeHeap.allocArray<ShortVar>(audioPlayBufferPos)
                    
                    // 复制缓冲区数据
                    for (i in 0 until audioPlayBufferPos) {
                        tempBuffer[i] = audioPlayBuffer[i]
                    }
                    
                    // 播放剩余数据
                    Pa_WriteStream(outputStreamPtr.value, tempBuffer, audioPlayBufferPos.toUInt())
                    
                    // 释放临时缓冲区
                    nativeHeap.free(tempBuffer)
                    
                    // 清空播放缓冲区
                    audioPlayBufferPos = 0
                } catch (e: Exception) {
                    logger.warn("播放剩余缓冲区数据时出错: ${e.message}")
                }
            }
            
            // 关闭输出流
            if (outputStreamPtr.value != null) {
                try {
                    Pa_StopStream(outputStreamPtr.value)
                    Pa_CloseStream(outputStreamPtr.value)
                    logger.info("输出音频流已关闭")
                } catch (e: Exception) {
                    logger.warn("关闭输出流时出错: ${e.message}")
                }
                outputStreamPtr.value = null
            }
        }
    }
    
    /**
     * 释放资源
     */
    override fun release() {
        logger.info("释放资源...")
        
        // 协程包裹，因为closeStreams是suspend函数
        kotlinx.coroutines.runBlocking {
            closeStreams()
        }
        
        // 终止PortAudio
        if (portAudioInitialized) {
            Pa_Terminate()
            portAudioInitialized = false
            logger.info("PortAudio已终止")
        }
        
        // 释放内存
        nativeHeap.free(inputStreamPtr.rawPtr)
        nativeHeap.free(outputStreamPtr.rawPtr)
        
        _deviceState.value = AudioDevice.AudioDeviceState.IDLE
    }
    
    /**
     * 获取设备信息
     * @return 设备信息的字符串表示
     */
    override fun getDeviceInfo(): String {
        val sb = StringBuilder()
        sb.appendLine("PortAudio设备信息:")
        
        if (!portAudioInitialized) {
            sb.appendLine("PortAudio未初始化")
            return sb.toString()
        }
        
        val deviceCount = Pa_GetDeviceCount()
        sb.appendLine("共发现 $deviceCount 个设备")
        
        for (i in 0 until deviceCount) {
            val info = Pa_GetDeviceInfo(i)?.pointed
            sb.appendLine("设备 $i: ${info?.name?.toKString() ?: "未知"}")
            sb.appendLine("  输入通道: ${info?.maxInputChannels ?: 0}")
            sb.appendLine("  输出通道: ${info?.maxOutputChannels ?: 0}")
            sb.appendLine("  默认采样率: ${info?.defaultSampleRate ?: 0.0}")
        }
        
        sb.appendLine("当前选择的输入设备: $selectedInputDeviceIndex")
        sb.appendLine("当前选择的输出设备: $selectedOutputDeviceIndex")
        
        return sb.toString()
    }
    
    /**
     * 播放器对音频设备的初始化
     * AudioPlayer 接口实现
     */
    override fun initialize(audioDevice: AudioDevice, deviceName: String, sampleRate: Int): Boolean {
        // 由于本身就是 AudioDevice 实例，直接调用自身的初始化方法
        logger.info("音频播放器初始化，使用自身作为音频设备")
        _playbackState.value = AudioPlayer.PlaybackState.LOADING
        
        val result = initialize(deviceName, sampleRate)
        if (result) {
            _playbackState.value = AudioPlayer.PlaybackState.IDLE
        } else {
            _playbackState.value = AudioPlayer.PlaybackState.ERROR
        }
        
        return result
    }
    
    /**
     * 播放音频文件
     * AudioPlayer 接口实现
     */
    override fun playAudio(filePath: String): Boolean {
        if (_deviceState.value != AudioDevice.AudioDeviceState.ACTIVE) {
            logger.warn("音频设备未处于活动状态，无法播放音频文件")
            return false
        }
        
        // 确保输出流已打开
        scope.launch {
            if (outputStreamPtr.value == null) {
                val success = openOutputStream(selectedOutputDeviceIndex, currentSampleRate, 1)
                if (!success) {
                    logger.error("播放音频文件失败：无法打开输出流")
                    _playbackState.value = AudioPlayer.PlaybackState.ERROR
                    return@launch
                }
            }
            
            _playbackState.value = AudioPlayer.PlaybackState.LOADING
            
            // 读取WAV文件
            val audioData = readWavFile(filePath)
            if (audioData == null) {
                logger.error("读取音频文件失败：$filePath")
                _playbackState.value = AudioPlayer.PlaybackState.ERROR
                return@launch
            }
            
            // 播放音频数据
            _playbackState.value = AudioPlayer.PlaybackState.PLAYING
            
            playShortArray(audioData)
            
            // 播放完成
            _playbackState.value = AudioPlayer.PlaybackState.IDLE
        }
        
        return true
    }
    
    /**
     * 播放音频缓冲区
     * AudioPlayer 接口实现
     */
    override fun playAudio(buffer: ShortArray): Boolean {
        if (_deviceState.value != AudioDevice.AudioDeviceState.ACTIVE) {
            logger.warn("音频设备未处于活动状态，无法播放音频")
            return false
        }
        
        // 确保输出流已打开
        scope.launch {
            if (outputStreamPtr.value == null) {
                val success = openOutputStream(selectedOutputDeviceIndex, currentSampleRate, 1)
                if (!success) {
                    logger.error("播放音频失败：无法打开输出流")
                    _playbackState.value = AudioPlayer.PlaybackState.ERROR
                    return@launch
                }
            }
            
            _playbackState.value = AudioPlayer.PlaybackState.PLAYING
            
            // 播放音频数据
            playShortArray(buffer)
            
            // 播放完成
            _playbackState.value = AudioPlayer.PlaybackState.IDLE
        }
        
        return true
    }
    
    /**
     * 停止播放
     * AudioPlayer 接口实现
     */
    override fun stopPlayback() {
        if (_playbackState.value == AudioPlayer.PlaybackState.PLAYING) {
            // 设置状态为空闲，播放协程将自行检测状态并停止
            _playbackState.value = AudioPlayer.PlaybackState.IDLE
            logger.info("停止音频播放")
        }
    }
    
    /**
     * 释放播放器
     * AudioPlayer 接口实现
     */
    override fun releasePlayer() {
        // 由于是集成的，这里调用设备的释放方法即可
        logger.info("释放音频播放器资源")
        stopPlayback()
        _playbackState.value = AudioPlayer.PlaybackState.IDLE
    }
    
    /**
     * 辅助方法：播放ShortArray数据
     */
    private suspend fun playShortArray(buffer: ShortArray) {
        try {
            // 分批次播放数据
            val chunkSize = 1024
            val tempBuffer = nativeHeap.allocArray<ShortVar>(chunkSize)
            
            var offset = 0
            while (offset < buffer.size && _playbackState.value == AudioPlayer.PlaybackState.PLAYING) {
                val remainingFrames = buffer.size - offset
                val framesToPlay = minOf(chunkSize, remainingFrames)
                
                // 复制数据到临时缓冲区
                for (i in 0 until framesToPlay) {
                    tempBuffer[i] = buffer[offset + i]
                }
                
                // 写入音频数据
                val framesWritten = writeAudio(tempBuffer, framesToPlay)
                if (framesWritten < 0) {
                    logger.error("播放音频数据失败")
                    break
                }
                
                offset += framesToPlay
                
                // 短暂延迟，避免过度占用CPU
                kotlinx.coroutines.delay(5)
            }
            
            // 释放临时缓冲区
            nativeHeap.free(tempBuffer)
            
        } catch (e: Exception) {
            logger.error("播放ShortArray时出错: ${e.message}")
            _playbackState.value = AudioPlayer.PlaybackState.ERROR
        }
    }
    
    /**
     * 读取WAV文件，仅支持简单的PCM格式
     */
    private fun readWavFile(filePath: String): ShortArray? {
        var file: CPointer<FILE>? = null
        
        try {
            file = fopen(filePath, "rb")
            if (file == null) {
                logger.error("无法打开文件: $filePath")
                return null
            }
            
            // 获取文件大小
            fseek(file, 0, SEEK_END)
            val fileSize = ftell(file)
            rewind(file)
            
            // 读取WAV头，跳过44字节
            val headerSize = 44
            val headerBuffer = ByteArray(headerSize)
            val bytesRead = fread(
                headerBuffer.refTo(0), 
                1u, 
                headerSize.toUInt(),
                file
            ).toInt()
            
            if (bytesRead != headerSize) {
                logger.error("读取WAV头失败")
                return null
            }
            
            // 计算数据大小和帧数
            val dataSize = fileSize - headerSize
            val frameCount = dataSize / 2  // 16位采样，每帧2字节
            
            // 读取音频数据
            val buffer = ShortArray(frameCount)
            val byteBuffer = ByteArray(dataSize)
            
            val dataBytesRead = fread(
                byteBuffer.refTo(0), 
                1u, 
                dataSize.toUInt(),
                file
            ).toInt()
            
            if (dataBytesRead != dataSize) {
                logger.error("读取WAV数据失败，期望读取 $dataSize 字节，实际读取 $dataBytesRead 字节")
                return null
            }
            
            // 转换字节数据为短整型
            for (i in 0 until frameCount) {
                val byte1 = byteBuffer[i * 2].toInt() and 0xFF
                val byte2 = byteBuffer[i * 2 + 1].toInt() and 0xFF
                buffer[i] = ((byte2 shl 8) or byte1).toShort()
            }
            
            logger.info("成功读取WAV文件，帧数: $frameCount")
            return buffer
            
        } catch (e: Exception) {
            logger.error("读取WAV文件异常: ${e.message}")
            return null
        } finally {
            if (file != null) {
                fclose(file)
            }
        }
    }
} 