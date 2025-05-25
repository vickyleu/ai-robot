@file:OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)

package voice.acquisition.portaudio

import com.airobot.portaudiointerop.PaStreamParameters
import com.airobot.portaudiointerop.Pa_CloseStream
import com.airobot.portaudiointerop.Pa_GetDefaultOutputDevice
import com.airobot.portaudiointerop.Pa_GetDeviceCount
import com.airobot.portaudiointerop.Pa_GetDeviceInfo
import com.airobot.portaudiointerop.Pa_GetErrorText
import com.airobot.portaudiointerop.Pa_Initialize
import com.airobot.portaudiointerop.Pa_OpenStream
import com.airobot.portaudiointerop.PaAlsaStreamInfo
import com.airobot.portaudiointerop.paALSA
import com.airobot.portaudiointerop.Pa_StartStream
import com.airobot.portaudiointerop.Pa_StopStream
import com.airobot.portaudiointerop.Pa_Terminate
import com.airobot.portaudiointerop.paInt16
import com.airobot.portaudiointerop.paNoError
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock.System
import platform.posix.pthread_self
import voice.hal.AudioDevice
import voice.hal.AudioDevice.AudioDeviceState
import voice.hal.LinuxAudioDeviceSelector
import voice.util.AudioDefaults
import voice.util.LogManager
import kotlin.concurrent.Volatile
import kotlin.time.ExperimentalTime
import com.airobot.portaudiointerop.PaStreamCallback
import com.airobot.portaudiointerop.PaStreamCallbackResult
import com.airobot.portaudiointerop.PaStreamCallbackTimeInfo
import com.airobot.portaudiointerop.Pa_GetDefaultInputDevice
import com.airobot.portaudiointerop.Pa_WriteStream
import com.airobot.portaudiointerop.paContinue
import com.airobot.portaudiointerop.paInputOverflow
import com.airobot.portaudiointerop.paInputUnderflow
import com.airobot.portaudiointerop.paNeverDropInput
import com.airobot.portaudiointerop.paOutputUnderflowed
import com.airobot.portaudiointerop.paPrimingOutput
import com.airobot.portaudiointerop.paUseHostApiSpecificDeviceSpecification
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.refTo
import kotlinx.cinterop.set
import kotlinx.coroutines.launch

/**
 * PortAudio音频设备实现类 - 回调模式
 * 提供基于PortAudio的音频设备功能，使用回调模式处理音频
 */
class PortAudioDevice private constructor() : AudioDevice {
    private val logger = LogManager.getLogger("PortAudioDevice")
    private val deviceSelector = LinuxAudioDeviceSelector()

    // 单例实现
    companion object {
        @Volatile
        private var instance: PortAudioDevice? = null

        // 流状态标志
        private var inputStreamActive = false
        private var outputStreamActive = false

        // 全局静态互斥锁，保护所有PortAudio调用
        private val portAudioLock = SynchronizedObject()

        // 流状态互斥锁
        private val streamStateLock = SynchronizedObject()

        // 设备级别的互斥锁
        private val deviceMutex = Mutex()

        fun getInstance(): PortAudioDevice {
            return instance ?: PortAudioDevice().also { instance = it }
        }

        // 检查输入流状态
        fun isInputStreamActive(): Boolean {
            return synchronized(streamStateLock) {
                inputStreamActive
            }
        }

        // 设置输入流状态
        fun setInputStreamActive(active: Boolean) {
            synchronized(streamStateLock) {
                inputStreamActive = active
            }
        }
        
        // 检查输出流状态
        fun isOutputStreamActive(): Boolean {
            return synchronized(streamStateLock) {
                outputStreamActive
            }
        }
        
        // 设置输出流状态
        fun setOutputStreamActive(active: Boolean) {
            synchronized(streamStateLock) {
                outputStreamActive = active
            }
        }
    }

    // 设备状态
    private val _deviceState = MutableStateFlow(AudioDeviceState.IDLE)
    override val deviceState: StateFlow<AudioDeviceState> = _deviceState.asStateFlow()
    
    // 播放状态
    enum class PlaybackState {
        IDLE, PLAYING, PAUSED, STOPPED, LOADING, ERROR
    }

    private val _playbackState = MutableStateFlow(PlaybackState.IDLE)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    // 存储设备信息
    private var selectedInputDeviceIndex = -1
    private var selectedOutputDeviceIndex = -1

    // PortAudio初始化状态
    private var portAudioInitialized = false
    
    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Default)
    
    // 暂时保留但不使用
    // private val audioProcessingDispatcher = Dispatchers.Default.limitedParallelism(1)
    
    // 音频流管理
    private val audioMutex = Mutex()
    
    // 写入锁，避免多线程并发访问输出流
    private val writeLock = Mutex()

    // 音频流指针
    private var inputStreamPtr = nativeHeap.alloc<COpaquePointerVar>()
    private var outputStreamPtr = nativeHeap.alloc<COpaquePointerVar>()

    // 当前采样率
    private var currentSampleRate = AudioDefaults.TARGET_SAMPLE_RATE

    // 当前输入通道数
    private var currentInputChannels: Int = AudioDefaults.CHANNELS
    
    // 音频播放缓冲
    private val audioPlayBuffer = ShortArray(8192)
    private var audioPlayBufferPos = 0
    private val minPlayFrames = 512  // 较小的最小播放帧数，减少延迟

    private var debugCallbackCounter=0

    // 添加一个变量跟踪当前使用的输出设备
    private var outputDeviceInUse: String = "未知"

    // 非实现不应被调用 - 在回调模式中不支持直接读取
    override fun readAudio(buffer: CPointer<ShortVar>, frameCount: Int): Int {
        logger.error("⚠️ readAudio: 在回调模式中不支持直接读取流")
        return 0
    }

    /**
     * 初始化音频设备
     * @param deviceName 设备名称
     * @param sampleRate 采样率
     * @return 初始化是否成功
     */
    override fun initialize(deviceName: String, sampleRate: Int): Boolean {
        runBlocking {
            deviceMutex.withLock {
                if (portAudioInitialized) {
                    logger.info("PortAudio已经初始化，不需要重复初始化")
                    return@withLock true
                }

                _deviceState.value = AudioDeviceState.INITIALIZING
                currentSampleRate = sampleRate

                try {
                    // 首先进行全面诊断和修复

                    // 释放音频资源
                    logger.info("正在释放音频资源...")
                    
                    // 重置流状态
                    synchronized(streamStateLock) {
                        inputStreamActive = false
                        outputStreamActive = false
                    }

                    // 关闭现有流
                    if (inputStreamPtr.value != null) {
                        try {
                            Pa_StopStream(inputStreamPtr.value)
                            Pa_CloseStream(inputStreamPtr.value)
                            inputStreamPtr.value = null
                        } catch (e: Exception) {
                            logger.warn("关闭现有输入流异常: ${e.message}")
                        }
                    }

                    // 释放系统资源
                    platform.posix.system("pkill pulseaudio 2>/dev/null || true")
                    platform.posix.system("pkill arecord 2>/dev/null || true")
                    platform.posix.system("pkill aplay 2>/dev/null || true")
                    platform.posix.system("sudo chmod -R 777 /dev/snd/* 2>/dev/null || true")
                    
                    // 终止PortAudio
                    if (!portAudioInitialized) {
                        try {
                            Pa_Terminate()
                            portAudioInitialized = false
                            delay(300)
                        } catch (e: Exception) {
                            logger.warn("终止PortAudio出错: ${e.message}")
                        }
                    }

                    // 创建优化的ALSA配置
                    deviceSelector.fixAlsaConfig()

                    // 初始化PortAudio
                    logger.info("正在初始化PortAudio...")
                    var initSuccess = false
                    var initAttempt = 0
                    val maxInitAttempts = 2

                    while (!initSuccess && initAttempt < maxInitAttempts) {
                        initAttempt++
                        
                        if (initAttempt > 1) {
                            delay(1000)
                        }

                        val result = Pa_Initialize()

                        if (result == paNoError) {
                            logger.info("✅ PortAudio初始化成功")
                            initSuccess = true
                            portAudioInitialized = true
                        } else {
                            val errorMsg = Pa_GetErrorText(result)?.toKString() ?: "未知错误"
                            logger.error("初始化PortAudio失败 (尝试 #$initAttempt): $errorMsg (错误码: $result)")
                        }
                    }

                    if (!initSuccess) {
                        logger.error("❌ 初始化尝试失败")
                        _deviceState.value = AudioDeviceState.IDLE
                        return@withLock false
                    }

                    // 列举设备
                    val (inputIdx, outputIdx) = listAudioDevices()
                    logger.info("选择的音频设备: 输入=$inputIdx, 输出=$outputIdx")
                    
                    portAudioInitialized = true
                    _deviceState.value = AudioDeviceState.READY

                    logger.info("✅ 音频设备初始化完成")
                    return@withLock true
                } catch (e: Exception) {
                    logger.error("初始化音频设备失败: ${e.message}")
                    e.printStackTrace()

                    _deviceState.value = AudioDeviceState.IDLE
                    portAudioInitialized = false
                    return@withLock false
                }
            }
        }

        return true
    }

    /**
     * 启动音频设备
     * @return 启动是否成功
     */
    override suspend fun start(): Boolean {
        logger.info("启动音频设备，当前状态: ${_deviceState.value}")

        runBlocking {
            deviceMutex.withLock {
                if (_deviceState.value == AudioDeviceState.ACTIVE) {
                    if (inputStreamPtr.value == null) {
                        logger.warn("⚠️ 设备状态为ACTIVE但流不存在，重置为READY状态")
                        _deviceState.value = AudioDeviceState.READY
                    } else {
                        logger.info("✅ 设备已在ACTIVE状态且流存在")
                        return@withLock true
                    }
                }

                if (_deviceState.value != AudioDeviceState.READY) {
                    logger.warn("⚠️ 音频设备未就绪，尝试初始化。当前状态: ${_deviceState.value}")
                    
                    if (!initialize("default", AudioDefaults.TARGET_SAMPLE_RATE)) {
                        logger.error("❌ 强制初始化失败，但仍将尝试启动")
                    } else {
                        logger.info("✅ 强制初始化成功，设备状态: ${_deviceState.value}")
                    }
                }

                // 设置状态为ACTIVE
                _deviceState.value = AudioDeviceState.ACTIVE
                logger.info("✅ 设备状态已设置为ACTIVE")
            }
        }

        // 检查最终状态
        val finalState = deviceState.value
        val streamActive = synchronized(streamStateLock) { inputStreamActive }
        logger.info("音频设备启动完成，最终状态: $finalState, 输入流: ${if (streamActive) "有效" else "无效"}")

        return finalState == AudioDeviceState.ACTIVE
    }

    /**
     * 停止音频设备
     */
    override fun stop() {
        synchronized(portAudioLock) {
            if (_deviceState.value != AudioDeviceState.ACTIVE) {
                logger.warn("音频设备未处于活动状态，无需停止。当前状态: ${_deviceState.value}")
                return
            }

            // 将状态设为就绪
            _deviceState.value = AudioDeviceState.READY
        }
    }

    /**
     * 设置采样率
     * @param sampleRate 采样率
     * @return 设置是否成功
     */
    override fun setSampleRate(sampleRate: Int): Boolean {
        if (sampleRate <= 0) {
            logger.error("无效的采样率: $sampleRate")
            return false
        }

        if (_deviceState.value != AudioDeviceState.IDLE && _deviceState.value != AudioDeviceState.READY) {
            logger.warn("无法在当前状态下更改采样率: ${_deviceState.value}")
            return false
        }

        currentSampleRate = sampleRate
        logger.info("采样率已设置为: $sampleRate")
        return true
    }

    /**
     * 获取当前采样率
     * @return 当前采样率
     */
    override fun getSampleRate(): Int {
        return currentSampleRate
    }

    /**
     * 获取当前通道数
     * @return 当前通道数
     */
    fun getChannels(): Int {
        return currentInputChannels
    }

    /**
     * 列出可用的音频设备
     * @return 默认输入和输出设备索引
     */
    override fun listAudioDevices(): Pair<Int, Int> {
        logger.info("列举设备: portAudioInitialized=$portAudioInitialized")

//        // 释放音频资源
//        try {
//            logger.info("强制释放音频资源...")
//            deviceSelector.killOtherAudioProcesses()
//        } catch (e: Exception) {
//            logger.warn("释放音频资源失败: ${e.message}")
//        }

        // 如果PortAudio未初始化，直接尝试重新初始化一次
        if (!portAudioInitialized) {
            logger.warn("PortAudio未初始化，尝试立即初始化")
            val result = Pa_Initialize()
            if (result == paNoError) {
                logger.info("直接Pa_Initialize成功")
                portAudioInitialized = true
                runBlocking { delay(500) }
            } else {
                logger.error("Pa_Initialize失败: ${Pa_GetErrorText(result)?.toKString()}")
                
                // 尝试终止并重新初始化
                try {
                    Pa_Terminate()
                    logger.info("已终止PortAudio，再次尝试初始化")
                    runBlocking { delay(1000) }

                    val reinitResult = Pa_Initialize()
                    if (reinitResult == paNoError) {
                        logger.info("第二次Pa_Initialize成功")
                        portAudioInitialized = true
                    } else {
                        logger.error("第二次Pa_Initialize失败: ${Pa_GetErrorText(reinitResult)?.toKString()}")
                    }
                } catch (e: Exception) {
                    logger.error("终止并重新初始化出错: ${e.message}")
                }
            }
        }
        
        val inDev = Pa_GetDefaultInputDevice().takeIf { it >= 0 } ?: 0
        val outDev = Pa_GetDefaultOutputDevice().takeIf { it >= 0 } ?: 0
        selectedInputDeviceIndex = inDev
        selectedOutputDeviceIndex = outDev
        return Pair(inDev, outDev)
    }

    /**
     * 关闭所有流
     */
    override suspend fun closeStreams() {
        deviceMutex.withLock {
            logger.info("关闭所有音频流...")
            try {
                // 关闭输入流
                if (inputStreamPtr.value != null) {
                    try {
                        Pa_StopStream(inputStreamPtr.value)
                        Pa_CloseStream(inputStreamPtr.value)
                        logger.info("输入音频流已关闭")
                        inputStreamPtr.value = null
                        synchronized(streamStateLock) {
                            inputStreamActive = false
                        }
                    } catch (e: Exception) {
                        logger.warn("关闭输入流时出错: ${e.message}")
                        inputStreamPtr.value = null
                        synchronized(streamStateLock) {
                            inputStreamActive = false
                        }
                    }
                }
                
                // 关闭输出流
                if (outputStreamPtr.value != null) {
                    try {
                        Pa_StopStream(outputStreamPtr.value)
                        Pa_CloseStream(outputStreamPtr.value)
                        logger.info("输出音频流已关闭")
                        outputStreamPtr.value = null
                        synchronized(streamStateLock) {
                            outputStreamActive = false
                        }
                    } catch (e: Exception) {
                        logger.warn("关闭输出流时出错: ${e.message}")
                        outputStreamPtr.value = null
                        synchronized(streamStateLock) {
                            outputStreamActive = false
                        }
                    }
                }

                logger.info("所有音频流已关闭")
            } catch (e: Exception) {
                logger.error("关闭流时发生异常: ${e.message}")
                inputStreamPtr.value = null
                outputStreamPtr.value = null
                synchronized(streamStateLock) {
                    inputStreamActive = false
                    outputStreamActive = false
                }
            }
        }
    }

    /**
     * 音频数据处理回调接口
     */
    interface AudioDataCallback {
        /**
         * 当有音频输入数据时回调
         * @param data 音频数据
         * @param frameCount 帧数
         */
        fun onAudioInput(data: ShortArray, frameCount: Int)
    }

    // 音频回调相关
    @Volatile
    private var audioCallback: AudioDataCallback? = null
    private var callbackBufferSize: Int = 0

    // 持有自身引用，用于回调
    private var thisStableRef: StableRef<PortAudioDevice>? = null
    private val callbackLock = SynchronizedObject()
    
    // 确保获取有效的稳定引用
    private fun getOrCreateStableRef(): StableRef<PortAudioDevice> {
        synchronized(callbackLock) {
            if (thisStableRef == null) {
                thisStableRef = StableRef.create(this)
            }
            return thisStableRef!!
        }
    }

    // 原生回调函数
    private val streamCallback = staticCFunction { input: CPointer<*>?,
                                                   output: CPointer<*>?,
                                                   frameCount: UInt,
                                                   timeInfo: CPointer<PaStreamCallbackTimeInfo>?,
                                                   statusFlags: UInt,
                                                   userData: CPointer<*>? ->

        try {
            // 检查状态标志，可能提供有用的调试信息
            if (statusFlags != 0u) {
                when {
                    (statusFlags and paInputUnderflow.toUInt()) != 0u ->
                        println("⚠️ 音频输入缓冲区欠载")
                    (statusFlags and paInputOverflow.toUInt()) != 0u ->
                        println("⚠️ 音频输入缓冲区溢出")
                    (statusFlags and paPrimingOutput.toUInt()) != 0u ->
                        println("ℹ️ 音频输出启动中")
                }
            }
            // 直接将 userData 作为 StableRef 的指针
            if (userData != null) {
                val device = userData.asStableRef<PortAudioDevice>().get()

                // 处理音频数据...
                if (input != null && frameCount.toInt() > 0) {
                    val channels = device.currentInputChannels.coerceAtLeast(1)
                    val totalSamples = frameCount.toInt() * channels

                    // 创建安全的 Kotlin 数组
                    val safeAudioData = try {
                        val buffer = ShortArray(totalSamples)
                        val inputShort = input.reinterpret<ShortVar>()
                        // 快速检查前几个样本是否为零
                        var nonZeroSamples = 0
                        val samplesToCheck = minOf(totalSamples, 10)

                        for (i in 0 until samplesToCheck) {
                            if (inputShort[i] != 0.toShort()) {
                                nonZeroSamples++
                            }
                        }
                        // 如果所有样本都是零，记录这个信息
                        if (nonZeroSamples == 0 && device.debugCallbackCounter++ % 500 == 0) {
                            println("🔍 回调接收到的原始数据全为零 (第${device.debugCallbackCounter}次)")
                            // 尝试读取更多样本来确认
                            var allZero = true
                            for (i in 0 until minOf(totalSamples, 100)) {
                                if (inputShort[i] != 0.toShort()) {
                                    allZero = false
                                    break
                                }
                            }

                            if (allZero) {
                                println("🚨 确认：音频输入流中没有有效数据")
                            }
                        }

                        // 复制数据
                        for (i in 0 until totalSamples) {
                            buffer[i] = inputShort[i]
                        }
                        buffer
                    } catch (e: Exception) {
                        println("❌ 复制音频数据失败: ${e.message}")
                        null
                    }

                    // 调用处理方法
                    if (safeAudioData != null) {
                        // 使用默认调度器
                        device.scope.launch {
                            device.onSafeAudioInput(safeAudioData, frameCount.toInt())
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("❌ FATAL ERROR in PortAudio callback: ${e.message}")
        }

        paContinue.toInt()
    }

    /**
     * 设置音频回调
     * @param callback 回调接口
     * @param bufferSize 缓冲区大小
     */
    fun setAudioCallback(callback: AudioDataCallback?, bufferSize: Int = 512) {
        synchronized(callbackLock) {
            this.audioCallback = callback
            this.callbackBufferSize = bufferSize
        }
    }

    /**
     * 在原生回调之后调用，已经转换为安全的Kotlin对象
     * @param safeAudioData 安全的Kotlin ShortArray
     * @param frameCount 帧数
     */
    private fun onSafeAudioInput(safeAudioData: ShortArray, frameCount: Int) {
        // 获取回调引用
        val callback = synchronized(callbackLock) { audioCallback }
        if (callback != null) {
            try {
                // 使用安全数据直接调用用户回调
                callback.onAudioInput(safeAudioData, frameCount)
            } catch (e: Exception) {
                logger.error("用户回调处理异常: ${e.message}")
            }
        }
    }


    /**
     * 使用回调模式打开输入流
     * @param deviceIndex 设备索引
     * @param sampleRate 采样率
     * @param channels 通道数
     * @param callback 音频回调
     * @return 打开是否成功
     */
    override suspend fun openInputStreamWithCallback(
        deviceIndex: Int,
        sampleRate: Int,
        channels: Int,
        callback: AudioDataCallback?
    ): Boolean {
        // 设置回调
        setAudioCallback(callback)

        return withContext(Dispatchers.Default) {
            deviceMutex.withLock {
                memScoped {
                    try {
                        logger.info("尝试打开带回调的音频输入流")

                        // 使用实际通道数
                        val actualChannels = 2//channels

                        // 尝试打开流
                        var success = false
                        var attempts = 0
                        val maxAttempts = 3
                        var lastError: String? = null

                        // 固定使用标准参数
                        val attemptRate = sampleRate
                        val bufferSize = 1024 // 增加缓冲区大小，原来是512

                        while (attempts < maxAttempts && !success) {
                            attempts++
                            logger.info("尝试打开回调流 #$attempts: 通道=$actualChannels, 采样率=$attemptRate, 缓冲区=$bufferSize")

                            // 分配输入参数
                            val inputParams = nativeHeap.alloc<PaStreamParameters>()
                            try {
                                val alsaStreamInfo = nativeHeap.alloc<PaAlsaStreamInfo>()
                                alsaStreamInfo.size = sizeOf<PaAlsaStreamInfo>().convert()
                                alsaStreamInfo.hostApiType = paALSA
                                alsaStreamInfo.version = 1u
                                alsaStreamInfo.deviceString = "plug:dsnoop".cstr.getPointer(this)  // 或者 "plughw:0,0"
                                inputParams.hostApiSpecificStreamInfo = alsaStreamInfo.ptr.reinterpret()
                                inputParams.device = paUseHostApiSpecificDeviceSpecification
                                inputParams.channelCount = actualChannels
                                inputParams.sampleFormat = paInt16
                                inputParams.suggestedLatency = 0.2 // 增加推荐延迟，原来是0.1

                                // 使用Pa_OpenStream打开流
                                val streamVar = nativeHeap.alloc<COpaquePointerVar>()
                                try {
                                    logger.info("即将使用回调打开Pa_OpenStream (rate=$attemptRate, buf=$bufferSize)")
                                    
                                    // 确保有一个有效的引用用于回调
                                    val stableRef = if (callback != null) getOrCreateStableRef() else null

                                    // 使用回调
                                    val result = Pa_OpenStream(
                                        stream = streamVar.ptr,
                                        inputParameters = inputParams.ptr,
                                        outputParameters = null,  // 不使用输出
                                        sampleRate = attemptRate.toDouble(),
                                        framesPerBuffer = bufferSize.toUInt(),
                                        streamFlags = 0u, // 使用默认标志
                                        streamCallback = if (callback != null) streamCallback else null,
                                        userData = stableRef?.asCPointer()
                                    )

                                    if (result == paNoError) {
                                        // 存储流指针
                                        inputStreamPtr.value = streamVar.value

                                        // 启动流
                                        val startResult = Pa_StartStream(inputStreamPtr.value)
                                        if (startResult == paNoError) {
                                            logger.info("带回调的音频输入流打开并启动成功, 通道=$actualChannels, 采样率=$attemptRate")

                                            // 确保设备状态是ACTIVE
                                            if (_deviceState.value != AudioDeviceState.ACTIVE) {
                                                _deviceState.value = AudioDeviceState.ACTIVE
                                            }

                                            // 更新当前采样率和通道数
                                            currentSampleRate = attemptRate
                                            this@PortAudioDevice.currentInputChannels = actualChannels
                                            
                                            // 设置输入流标志
                                            synchronized(streamStateLock) {
                                                inputStreamActive = true
                                            }

                                            success = true
                                            break
                                        } else {
                                            val errorMsg = Pa_GetErrorText(startResult)?.toKString() ?: "未知错误"
                                            logger.error("无法启动回调音频输入流（尝试 #$attempts）: $errorMsg")
                                            Pa_CloseStream(inputStreamPtr.value)
                                            inputStreamPtr.value = null
                                        }
                                    } else {
                                        val errorMsg = Pa_GetErrorText(result)?.toKString() ?: "未知错误"
                                        logger.error("无法打开回调音频输入流（尝试 #$attempts）: $errorMsg")
                                        lastError = errorMsg
                                    }
                                } finally {
                                    nativeHeap.free(streamVar.rawPtr)
                                }
                            } finally {
                                nativeHeap.free(inputParams.rawPtr)
                            }

                            // 失败后等待
                            if (!success && attempts < maxAttempts) {
                                delay(500)
                            }
                        }

                        if (!success) {
                            logger.error("所有回调流尝试都失败，最后一个错误: $lastError")
                            synchronized(streamStateLock) {
                                inputStreamActive = false
                            }
                            // 如果打开流失败，释放StableRef
                            releaseStableRef()
                            return@withLock false
                        }

                        return@withLock true
                    } catch (e: Exception) {
                        logger.error("打开回调音频输入流时发生异常: ${e.message}")
                        e.printStackTrace()
                        synchronized(streamStateLock) {
                            inputStreamActive = false
                        }
                        // 发生异常时释放StableRef
                        releaseStableRef()
                        return@withLock false
                    }
                }
            }
        }
    }

    /**
     * 诊断音频输出问题
     * 尝试使用不同方法播放测试音频
     */
    fun diagnoseSoundOutput() {
        logger.info("开始诊断音频输出问题...")
        try {
            // 检查音量设置
            logger.info("检查音量设置:")
            platform.posix.system("amixer")
            
            logger.info("音频诊断完成")
        } catch (e: Exception) {
            logger.error("诊断过程中出现异常: ${e.message}")
        }
    }

    /**
     * 打开输出流
     * @param deviceIndex 设备索引
     * @param sampleRate 采样率
     * @param channels 通道数
     * @return 打开是否成功
     */
    override suspend fun openOutputStream(
        deviceIndex: Int,
        sampleRate: Int,
        channels: Int
    ): Boolean {
        // 先运行诊断
        diagnoseSoundOutput()
        
        return deviceMutex.withLock {
            // 严格检查：如果输入流活跃，警告可能存在冲突
            if (inputStreamActive) {
                logger.warn("⚠️ 输入流处于活动状态，可能会影响播放质量")
            }

            // 如果输出流已经处于活跃状态
            if (outputStreamActive && outputStreamPtr.value != null) {
                logger.info("输出流已存在，直接复用")
                return@withLock true
            }

            if (!portAudioInitialized) {
                logger.error("PortAudio未初始化，尝试先初始化")
                val initSuccess = initialize("default", sampleRate)
                if (!initSuccess) {
                    logger.error("PortAudio初始化失败，无法打开输出流")
                    return@withLock false
                }
            }

            // 使用硬件采样率
            val actualSampleRate = AudioDefaults.TARGET_SAMPLE_RATE

            // 获取实际设备索引
            val actualDeviceIndex =
                if (deviceIndex >= 0) deviceIndex else selectedOutputDeviceIndex

            // 使用固定通道数
            val actualChannels = AudioDefaults.CHANNELS

            // 标记输出流正在处理中
            synchronized(streamStateLock) {
                outputStreamActive = true
            }

            var success = false
            memScoped {
                try {
                    // 开始尝试打开流
                    var attempts = 0
                    val maxAttempts = 3

                    // 尝试不同的设备字符串
                    val deviceStrings = listOf( "plug:dmix", "hw:0,0", "default","sysdefault:0,0", "dmix:0,0")
                    var currentDeviceStringIndex = 0

                    while (!success && attempts < maxAttempts) {
                        attempts++

                        // 在每次尝试之间短暂延迟
                        if (attempts > 1) {
                            delay(500)
                        }

                        // 创建流参数结构体
                        val outputParams = nativeHeap.alloc<PaStreamParameters>()

                        try {
                            // 可以考虑直接使用ALSA API指定详细设备参数
                            val alsaStreamInfo = nativeHeap.alloc<PaAlsaStreamInfo>()
                            alsaStreamInfo.size = sizeOf<PaAlsaStreamInfo>().convert()
                            alsaStreamInfo.hostApiType = paALSA
                            alsaStreamInfo.version = 1u
                            
                            // 循环尝试不同的设备字符串
                            val deviceString = deviceStrings[currentDeviceStringIndex % deviceStrings.size]
                            currentDeviceStringIndex++
                            
                            logger.info("尝试使用音频输出设备: $deviceString")
                            alsaStreamInfo.deviceString = deviceString.cstr.getPointer(this)
                            
                            outputParams.hostApiSpecificStreamInfo = alsaStreamInfo.ptr.reinterpret()
                            outputParams.device = paUseHostApiSpecificDeviceSpecification
                            outputParams.channelCount = actualChannels
                            outputParams.sampleFormat = paInt16
                            outputParams.suggestedLatency = 0.05

                            // 使用Pa_OpenStream打开流
                            val streamVar = nativeHeap.alloc<COpaquePointerVar>()
                            try {
                                logger.info("打开输出流: rate=$actualSampleRate, buf=2048")
                                val result = Pa_OpenStream(
                                    stream = streamVar.ptr,
                                    inputParameters = null,  // 不使用输入
                                    outputParameters = outputParams.ptr,
                                    sampleRate = actualSampleRate.toDouble(),
                                    framesPerBuffer = 2048u, // 增大缓冲区，提高稳定性
                                    streamFlags = 0u,
                                    streamCallback = null,
                                    userData = null
                                )

                                if (result == paNoError) {
                                    // 存储流指针
                                    outputStreamPtr.value = streamVar.value

                                    // 启动流
                                    val startResult = Pa_StartStream(outputStreamPtr.value)
                                    if (startResult == paNoError) {
                                        logger.info("音频输出流打开并启动成功（尝试 #$attempts）, 采样率: $actualSampleRate")

                                        // 清空播放缓冲区
                                        audioPlayBufferPos = 0

                                        outputDeviceInUse = deviceString
                                        logger.info("成功打开输出设备: $deviceString")
//                                        monitorOutputDeviceStatus()

                                        success = true
                                        break
                                    } else {
                                        val errorMsg = Pa_GetErrorText(startResult)?.toKString() ?: "未知错误"
                                        logger.error("无法启动音频输出流（尝试 #$attempts）: $errorMsg")
                                        Pa_CloseStream(outputStreamPtr.value)
                                        outputStreamPtr.value = null
                                    }
                                } else {
                                    val errorMsg = Pa_GetErrorText(result)?.toKString() ?: "未知错误"
                                    logger.error("无法打开音频输出流（尝试 #$attempts）: $errorMsg")
                                }
                            } finally {
                                nativeHeap.free(streamVar.rawPtr)
                            }
                        } finally {
                            // 确保释放参数内存
                            nativeHeap.free(outputParams.rawPtr)
                        }
                    }

                    return@withLock success
                } catch (e: Exception) {
                    logger.error("打开音频输出流时出错: ${e.message}")
                    e.printStackTrace()
                    return@withLock false
                } finally {
                    // 如果没有成功，重置标志
                    if (!success) {
                        synchronized(streamStateLock) {
                            outputStreamActive = false
                        }
                    }
                }
            }

        }
    }


    /**
     * 获取设备信息
     * @return 设备信息字符串
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
     * 写入音频数据
     * @param buffer 音频数据缓冲区
     * @param frameCount 帧数
     * @return 写入的帧数，失败返回负数
     */
    override fun writeAudio(buffer: CPointer<ShortVar>, frameCount: Int): Int {
        synchronized(portAudioLock) {
            // 先检查输出流是否活跃
            val isActive = synchronized(streamStateLock) { outputStreamActive }

            if (!isActive || outputStreamPtr.value == null) {
                logger.error("输出流未打开或不活跃")
                return -1
            }
            
            // 检查数据和帧数
            if (frameCount <= 0) {
                logger.error("无效的帧数: $frameCount")
                return -1
            }
            
            // 读取一些数据样本进行日志
            val firstSamples = StringBuilder()
            for (i in 0 until minOf(5, frameCount)) {
                firstSamples.append("${buffer[i]} ")
            }
            logger.debug("写入音频: $frameCount 帧, 样本: $firstSamples...")
            
            // 增强音频音量 - 对数据进行处理
            val volumeMultiplier = 2.0f // 将音量放大2倍
            val processedBuffer = nativeHeap.allocArray<ShortVar>(frameCount * 2) // 假设双声道
            
            try {
                // 复制并放大每个样本
                for (i in 0 until frameCount * 2) { // 遍历所有样本（包括双声道）
                    val originalValue = buffer[i].toInt()
                    // 放大音量但避免溢出
                    val amplifiedValue = (originalValue * volumeMultiplier).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    processedBuffer[i] = amplifiedValue.toShort()
                }
                
                // 写入处理后的音频数据
                val result = Pa_WriteStream(outputStreamPtr.value, processedBuffer, frameCount.toUInt())
                
                when (result) {
                    paNoError -> {
                        logger.warn("音频数据写入成功")
                        return frameCount
                    }
                    paOutputUnderflowed -> {
                        logger.warn("音频输出欠载，但数据已写入")
                        return frameCount
                    }
                    else -> {
                        val errorMsg = Pa_GetErrorText(result)?.toKString() ?: "未知错误"
                        logger.error("写入音频失败 (错误码: $result): $errorMsg")
                        return -1
                    }
                }
            } finally {
                // 释放临时缓冲区
                nativeHeap.free(processedBuffer.rawValue)
            }
        }
    }
    
    /**
     * 播放音频数据
     * @param audioData 音频数据
     * @param length 数据长度
     * @return 播放是否成功
     */
    override fun play(audioData: ByteArray, length: Int): Boolean {
        // 确保所有播放操作都在synchronized块内
        return synchronized(portAudioLock) {
            // 首先尝试使用PortAudio播放
            var portAudioSuccess = false
            
            if (_deviceState.value != AudioDeviceState.ACTIVE) {
                logger.warn("音频设备未处于活动状态，无法播放音频数据")
            } else {
                // 若输出流未打开，尝试打开
                if (outputStreamPtr.value == null) {
                    // 安全检查：确保不在打开输出流时尝试播放
                    if (outputStreamActive) {
                        logger.warn("输出流正在打开中，暂时无法播放音频")
                    } else {
                        logger.info("输出流不存在，尝试打开新的输出流")
                        val success = runBlocking {
                            openOutputStream(
                                selectedOutputDeviceIndex,
                                AudioDefaults.TARGET_SAMPLE_RATE,
                                AudioDefaults.CHANNELS
                            )
                        }
                        if (!success) {
                            logger.error("无法打开输出流，播放失败")
                        } else {
                            logger.info("成功打开输出流")
                        }
                    }
                }

                // 如果流成功打开，尝试写入数据
                if (outputStreamPtr.value != null) {
                    try {
                        // 转换数据格式
                        val shortArray = voice.util.AudioUtils.byteArrayToShortArray(audioData.copyOfRange(0, length))
                        
                        // 记录音频信号强度
                        val maxAmplitude = shortArray.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
                        logger.info("正在播放音频数据: ${length}字节, 最大振幅: $maxAmplitude")
                        
                        // 音量放大处理
                        val volumeMultiplier = 3.0f // 放大音量，使声音更清晰
                        val processedShortArray = ShortArray(shortArray.size)
                        for (i in shortArray.indices) {
                            val amplifiedValue = (shortArray[i] * volumeMultiplier).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            processedShortArray[i] = amplifiedValue.toShort()
                        }
                        
                        val nativeBuf = nativeHeap.allocArray<ShortVar>(processedShortArray.size)
                        for (i in processedShortArray.indices) nativeBuf[i] = processedShortArray[i]
                        val framesToWrite = processedShortArray.size / 2 // 假设2个通道

                        val written = writeAudio(nativeBuf, framesToWrite)
                        nativeHeap.free(nativeBuf.rawValue)

                        if (written == framesToWrite) {
                            logger.info("成功写入 $written 帧音频数据")
                            portAudioSuccess = true
                        } else {
                            logger.error("写入音频数据不完整: $written / $framesToWrite")
                        }
                    } catch (e: Exception) {
                        logger.error("写入音频数据异常: ${e.message}")
                    }
                }
            }
            
            // 如果PortAudio播放失败，尝试使用系统命令播放
            if (!portAudioSuccess) {
                try {
                    // 将音频数据写入临时文件
                    val tempFilePath = "/tmp/temp_audio_${System.now().toEpochMilliseconds()}.raw"
                    val tempFile = platform.posix.fopen(tempFilePath, "wb")
                    if (tempFile != null) {
                        //audioData: ByteArray
                        platform.posix.fwrite(audioData.refTo(0), 1u, length.toUInt(), tempFile)
                        platform.posix.fclose(tempFile)
                        
                        // 使用aplay播放临时文件
                        logger.info("尝试使用aplay播放音频")
                        val cmd = "aplay -f S16_LE -r ${AudioDefaults.TARGET_SAMPLE_RATE} -c ${AudioDefaults.CHANNELS} $tempFilePath && rm $tempFilePath"
                        val result = platform.posix.system(cmd)
                        
                        logger.info("aplay播放结果: $result")
                        return result == 0
                    }
                } catch (e: Exception) {
                    logger.error("使用系统命令播放音频失败: ${e.message}")
                    return false
                }
            }
            
            return portAudioSuccess
        }
    }
    
    /**
     * 异步播放音频数据
     * @param audioData 音频数据
     * @param length 数据长度
     * @param onComplete 完成回调
     * @return 播放是否成功
     */
    override fun playAsync(audioData: ByteArray, length: Int, onComplete: () -> Unit): Boolean {
        if (_deviceState.value != AudioDeviceState.ACTIVE) {
            logger.warn("音频设备未处于活动状态，无法播放音频数据")
            onComplete()
            return false
        }

        // 在协程中确保线程安全地访问输出流
        scope.launch {
            // 对整个播放过程应用锁保护
            writeLock.withLock {
                try {
                    if (outputStreamPtr.value == null) {
                        val success = openOutputStream(
                            selectedOutputDeviceIndex,
                            AudioDefaults.TARGET_SAMPLE_RATE,
                            AudioDefaults.CHANNELS
                        )
                        if (!success) {
                            logger.error("播放音频数据失败：无法打开输出流")
                            _playbackState.value = PlaybackState.ERROR
                            onComplete()
                            return@launch
                        }
                    }

                    _playbackState.value = PlaybackState.LOADING

                    // 将ByteArray转换为ShortArray
                    val shortArray = voice.util.AudioUtils.byteArrayToShortArray(audioData.copyOf(length))

                    // 播放音频数据
                    _playbackState.value = PlaybackState.PLAYING

                    playShortArray(shortArray)

                    // 播放完成
                    _playbackState.value = PlaybackState.IDLE
                } catch (e: Exception) {
                    logger.error("异步播放过程发生异常: ${e.message}")
                    e.printStackTrace()
                    _playbackState.value = PlaybackState.ERROR
                } finally {
                    // 确保回调被调用
                    onComplete()
                }
            }
        }
        return true
    }
    
    /**
     * 播放ShortArray数据
     * @param buffer 音频数据
     */
    private suspend fun playShortArray(buffer: ShortArray) {
        try {
            // 分批次播放数据
            val chunkSize = 1024
            val tempBuffer = nativeHeap.allocArray<ShortVar>(chunkSize)

            var offset = 0
            var shouldContinue = true
            while (offset < buffer.size && _playbackState.value == PlaybackState.PLAYING && shouldContinue) {
                val remainingFrames = buffer.size - offset
                val framesToPlay = minOf(chunkSize, remainingFrames)

                // 复制数据到临时缓冲区
                for (i in 0 until framesToPlay) {
                    tempBuffer[i] = buffer[offset + i]
                }

                // 写入音频数据
                synchronized(portAudioLock) {
                    if (outputStreamPtr.value == null) {
                        logger.error("输出流已关闭，播放中断")
                        shouldContinue = false
                    } else {
                        val result = Pa_WriteStream(outputStreamPtr.value, tempBuffer, framesToPlay.toUInt())
                        if (result != paNoError && result != paOutputUnderflowed) {
                            logger.error("播放音频失败: ${Pa_GetErrorText(result)?.toKString()}")
                            shouldContinue = false
                        }
                    }
                }

                if (shouldContinue) {
                    offset += framesToPlay

                    // 短暂延迟，避免过度占用CPU
                    delay(5)
                }
            }

            // 释放临时缓冲区
            nativeHeap.free(tempBuffer.rawValue)

        } catch (e: Exception) {
            logger.error("播放ShortArray时出错: ${e.message}")
            _playbackState.value = PlaybackState.ERROR
        }
    }
    
    /**
     * 暂停播放
     */
    override fun pause() {
        if (_playbackState.value == PlaybackState.PLAYING) {
            _playbackState.value = PlaybackState.PAUSED
            logger.info("暂停音频播放")
        }
    }
    
    /**
     * 恢复播放
     */
    override fun resume() {
        if (_playbackState.value == PlaybackState.PAUSED) {
            _playbackState.value = PlaybackState.PLAYING
            logger.info("恢复音频播放")
        }
    }
    
    /**
     * 检查是否正在播放
     * @return 是否正在播放
     */
    override fun isPlaying(): Boolean {
        return _playbackState.value == PlaybackState.PLAYING
    }
    
    /**
     * 停止播放
     */
    override fun stopPlayback() {
        if (_playbackState.value == PlaybackState.PLAYING || 
            _playbackState.value == PlaybackState.PAUSED || 
            _playbackState.value == PlaybackState.LOADING) {
            _playbackState.value = PlaybackState.IDLE
            logger.info("停止音频播放")
            audioPlayBufferPos = 0
        }
    }

    /**
     * 释放StableRef资源
     */
    private fun releaseStableRef() {
        synchronized(callbackLock) {
            try {
                thisStableRef?.dispose()
            } catch (e: Exception) {
                logger.error("释放StableRef时出错: ${e.message}")
            } finally {
                thisStableRef = null
            }
        }
    }

    /**
     * 释放资源
     */
    override fun release() {
        logger.info("释放PortAudioDevice资源")

        runBlocking {
            deviceMutex.withLock {
                try {
                    // 关闭流
                    closeStreams()

                    // 移除回调引用
                    synchronized(callbackLock) {
                        audioCallback = null
                    }

                    // 释放StableRef资源
                    releaseStableRef()

                    // 重置所有标志
                    synchronized(streamStateLock) {
                        inputStreamActive = false
                        outputStreamActive = false
                    }

                    // 终止PortAudio
                    if (portAudioInitialized) {
                        try {
                            Pa_Terminate()
                            portAudioInitialized = false
                            logger.info("PortAudio已终止")
                        } catch (e: Exception) {
                            logger.warn("终止PortAudio时出错: ${e.message}")
                        }
                    }

                    // 安全释放内存
                    try {
                        if (inputStreamPtr.value != null) {
                            nativeHeap.free(inputStreamPtr.rawPtr)
                            logger.info("已释放inputStreamPtr内存")
                        }
                    } catch (e: Exception) {
                        logger.error("释放inputStreamPtr内存时出错: ${e.message}")
                    }
                    
                    try {
                        if (outputStreamPtr.value != null) {
                            nativeHeap.free(outputStreamPtr.rawPtr)
                            logger.info("已释放outputStreamPtr内存")
                        }
                    } catch (e: Exception) {
                        logger.error("释放outputStreamPtr内存时出错: ${e.message}")
                    }

                    _deviceState.value = AudioDeviceState.IDLE
                    logger.info("PortAudioDevice资源已完全释放")
                } catch (e: Exception) {
                    logger.error("释放资源时出错: ${e.message}")
                }
            }
        }
    }

    /**
     * 监控音频输出设备状态
     * 记录当前正在使用的输出设备和状态
     */
    private fun monitorOutputDeviceStatus() {
        scope.launch {
            try {
                logger.info("开始监控音频输出设备状态")
                
                // 检查ALSA音量
                val volumeResult = platform.posix.system("amixer get Master 2>/dev/null || amixer get PCM 2>/dev/null || amixer get Speaker 2>/dev/null")
                logger.info("音量检查结果: $volumeResult")
                
                // 验证正在使用的设备
                val actualDevice = outputDeviceInUse
                logger.info("当前使用的输出设备: $actualDevice")
                
                // 测试直接播放
                logger.info("测试直接播放到当前设备")
                val testBuffer = nativeHeap.allocArray<ShortVar>(1024)
                
                // 生成简单的正弦波测试音频
                for (i in 0 until 1024) {
                    val value = (kotlin.math.sin(i * 0.05) * Short.MAX_VALUE * 0.8).toInt()
                    testBuffer[i] = value.toShort()
                }
                
                // 尝试播放测试音频
                if (outputStreamPtr.value != null) {
                    val result = Pa_WriteStream(outputStreamPtr.value, testBuffer, 512u)
                    logger.info("测试音频播放结果: $result (${Pa_GetErrorText(result)?.toKString()})")
                } else {
                    logger.warn("输出流不可用，无法进行测试播放")
                }
                
                // 释放测试缓冲区
                nativeHeap.free(testBuffer.rawValue)
                
            } catch (e: Exception) {
                logger.error("监控音频设备状态时出错: ${e.message}")
            }
        }
    }
}