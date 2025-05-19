@file:OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)

package voice.acquisition.portaudio

import com.airobot.portaudiointerop.Pa_CloseStream
import com.airobot.portaudiointerop.Pa_GetDefaultOutputDevice
import com.airobot.portaudiointerop.Pa_GetDeviceCount
import com.airobot.portaudiointerop.Pa_GetDeviceInfo
import com.airobot.portaudiointerop.Pa_GetErrorText
import com.airobot.portaudiointerop.Pa_Initialize
import com.airobot.portaudiointerop.Pa_OpenStream
import com.airobot.portaudiointerop.Pa_ReadStream
import com.airobot.portaudiointerop.Pa_StartStream
import com.airobot.portaudiointerop.Pa_StopStream
import com.airobot.portaudiointerop.Pa_Terminate
import com.airobot.portaudiointerop.Pa_WriteStream
import com.airobot.portaudiointerop.paInputOverflowed
import com.airobot.portaudiointerop.paInt16
import com.airobot.portaudiointerop.paNoError
import com.airobot.portaudiointerop.paOutputUnderflowed
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.free
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock.System
import platform.posix.FILE
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.pthread_self
import platform.posix.random
import platform.posix.rewind
import voice.audio.vad.VoiceActivityDetector
import voice.hal.AudioDevice
import voice.hal.AudioDevice.AudioDeviceState
import voice.hal.LinuxAudioDeviceSelector
import voice.util.LogManager
import kotlin.concurrent.Volatile
import kotlin.time.ExperimentalTime

/**
 * PortAudio音频设备实现类
 * 提供基于PortAudio的音频设备功能
 */
class PortAudioDevice private constructor() : SynchronizedObject(), AudioDevice {
    private val logger = LogManager.getLogger("PortAudioDevice")

    // 单例实现
    companion object {
        @Volatile
        private var instance: PortAudioDevice? = null

        // 添加全局标志以防止多个组件打开音频流
        private var globalStreamActive = false

        // 全局静态互斥锁，保护所有PortAudio调用
        private val portAudioLock = SynchronizedObject()

        fun getInstance(): PortAudioDevice {
            return instance ?: PortAudioDevice().also { instance = it }
        }

        // 添加全局方法检查是否已有音频流运行
        fun isGlobalStreamActive(): Boolean {
            return globalStreamActive
        }

        // 设置全局音频流状态
        fun setGlobalStreamActive(active: Boolean) {
            synchronized(portAudioLock) {
                globalStreamActive = active
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

    // 恢复尝试相关状态
    private var lastRecoveryAttemptTimestamp = 0L
    private val recoveryAttemptCooldownMs = 10000L // 10秒恢复尝试冷却时间

    // 流重置相关状态
    private var audioReadResetNeeded = false

    // 工具: 获取当前线程ID (仅用于调试日志)
    private fun threadId(): ULong = pthread_self().toLong().toULong()

    // 非suspend版本的readAudio
    override fun readAudio(buffer: CPointer<ShortVar>, frameCount: Int): Int {
        if (inputStreamPtr.value == null) {
            if (audioReadCounter++ % 200 == 0) {
                logger.warn("Sync readAudio: Input stream is null")
            }
            // 填充静音
            for (i in 0 until frameCount) {
                buffer[i] = 0
            }
            return frameCount // 返回读取的帧数（静音）
        }
        val result = Pa_ReadStream(inputStreamPtr.value, buffer, frameCount.toUInt())
        if (result == paNoError || result == paInputOverflowed) {
            return frameCount
        } else {
            if (audioReadCounter++ % 50 == 0) {
                logger.warn("Sync readAudio failed: ${Pa_GetErrorText(result)?.toKString()} (code: $result)")
            }
            // 填充静音
            for (i in 0 until frameCount) {
                buffer[i] = 0
            }
            return frameCount // 即使错误也返回帧数，因为数据被静音填充
        }
    }

    // suspend版本的readAudio (之前的内容)
    suspend fun readAudioSuspend(buffer: CPointer<ShortVar>, frameCount: Int): Int {
        val currentTimeMs = System.now().toEpochMilliseconds()

        // 如果全局流标志激活但流指针为null，直接返回静音数据
        if (globalStreamActive && inputStreamPtr.value == null) {
            // 填充静音数据
            for (i in 0 until frameCount) {
                buffer[i] = 0
            }

            // 每隔一定次数尝试恢复一次
            if (audioReadCounter++ % 200 == 0) {
                logger.info("全局音频流已激活但流指针为null，自动填充静音")
            }

            return frameCount
        }

        // 检查输入流是否为null
        if (inputStreamPtr.value == null) {
            if (audioReadCounter++ % 200 == 0) { // 降低日志频率
                logger.error("音频输入流为NULL，尝试恢复流...")
            }

            // 如果流为null，尝试恢复（带冷却）
            if (currentTimeMs - lastRecoveryAttemptTimestamp > recoveryAttemptCooldownMs) {
                lastRecoveryAttemptTimestamp = currentTimeMs

                // 先检查PortAudio初始化状态
                if (!portAudioInitialized) {
                    logger.warn("PortAudio未初始化，尝试重新初始化...")
                    initialize("default", currentSampleRate)
                }

                if (!attemptStreamRecovery()) {
                    // 返回静音数据而不是错误
                    for (i in 0 until frameCount) {
                        buffer[i] = 0 // 填充静音
                    }
                    return frameCount
                }

                // 恢复成功，继续尝试读取音频
                if (inputStreamPtr.value == null) {
                    logger.error("恢复报告成功但流仍为null，填充静音...")
                    for (i in 0 until frameCount) {
                        buffer[i] = 0
                    }
                    return frameCount
                }
            } else {
                // 冷却期内，返回静音数据
                for (i in 0 until frameCount) {
                    buffer[i] = 0
                }
                return frameCount
            }
        }

        // 检查是否需要处理占用音频设备的进程
        if (deviceSelector.isRaspberryPi() && (consecutiveErrors >= 3 || audioReadResetNeeded)) {
            logger.warn("检测到可能存在音频设备冲突，尝试清理其他音频进程...")
            deviceSelector.killOtherAudioProcesses()
            audioReadResetNeeded = false
            consecutiveErrors = 0

            // 给系统一些时间恢复
            kotlinx.coroutines.delay(100)
        }

        try {
            // 安全检查
            if (frameCount <= 0) {
                logger.error("无效的帧数: $frameCount")
                return 0
            }

            // 安全地从音频流读取数据
            val result = Pa_ReadStream(inputStreamPtr.value, buffer, frameCount.toUInt())

            // 处理不同的错误结果
            when (result) {
                paNoError -> {
                    // 正常读取成功
                    if (currentTimeMs - lastErrorTimestamp > errorResetIntervalMs) {
                        consecutiveErrors = 0
                    }
                    return frameCount
                }

                paInputOverflowed -> {
                    // 输入溢出，数据可能丢失但本次读取应该成功
                    if (audioReadCounter++ % 100 == 0) {
                        logger.warn("输入缓冲区溢出，数据可能丢失")
                    }
                    if (currentTimeMs - lastErrorTimestamp > errorResetIntervalMs) {
                        consecutiveErrors = 0
                    }
                    return frameCount
                }

                else -> {
                    // 其他错误情况
                    val errorMsg = Pa_GetErrorText(result)?.toKString() ?: "未知错误"

                    // 降低日志频率
                    if (audioReadCounter++ % 50 == 0) {
                        logger.warn("读取音频数据失败: $errorMsg (错误码: $result)")
                    }

                    // 记录错误时间和增加计数
                    lastErrorTimestamp = currentTimeMs
                    consecutiveErrors++

                    // 连续错误超过阈值，尝试恢复
                    if (consecutiveErrors >= maxConsecutiveErrors) {
                        // 添加随机性，避免多个实例同时尝试恢复
                        val jitter = (random() * 500).toLong()

                        logger.warn("检测到连续$consecutiveErrors 次错误，开始恢复流程")
                        audioReadResetNeeded = true

                        // 如果已超过冷却时间，尝试立即恢复
                        if (currentTimeMs - lastRecoveryAttemptTimestamp > recoveryAttemptCooldownMs) {
                            lastRecoveryAttemptTimestamp = currentTimeMs

                            // 延迟一个随机时间，避免雷同
                            kotlinx.coroutines.delay(50 + jitter)

                            // 如果是流错误，直接尝试重新打开流而不是整个初始化过程
                            attemptStreamRecovery()
                        }
                    }

                    // 返回静音数据
                    for (i in 0 until frameCount) {
                        buffer[i] = 0
                    }
                    return frameCount
                }
            }
        } catch (e: Exception) {
            // 捕获任何异常
            if (audioReadCounter++ % 50 == 0) {
                logger.error("读取音频时发生异常: ${e.message}")
            }

            // 标记流需要重建
            audioReadResetNeeded = true
            consecutiveErrors++

            // 返回静音数据
            for (i in 0 until frameCount) {
                buffer[i] = 0
            }
            return frameCount
        }
    }

    private suspend fun attemptStreamRecovery(): Boolean {
        logger.warn("尝试恢复音频输入流...")
        var recoveryAttempt = 0
        val maxRecoveryAttempts = 5  // 最多尝试5次

        while (recoveryAttempt < maxRecoveryAttempts) {
            recoveryAttempt++
            logger.info("恢复尝试 #$recoveryAttempt")

            audioMutex.withLock {
                // 首先关闭现有流
                if (inputStreamPtr.value != null) {
                    logger.info("关闭现有输入流")
                    try {
                        Pa_StopStream(inputStreamPtr.value)
                    } catch (e: Exception) {
                        logger.warn("停止输入流时发生异常: ${e.message}")
                    }

                    try {
                        Pa_CloseStream(inputStreamPtr.value)
                    } catch (e: Exception) {
                        logger.warn("关闭输入流时发生异常: ${e.message}")
                    }
                    inputStreamPtr.value = null
                }

                // 短暂延迟，时间随尝试次数增加
                val delayTime = 300L * recoveryAttempt
                kotlinx.coroutines.delay(delayTime)

                // 尝试使用root权限修复设备访问权限
                if (recoveryAttempt >= 1) {
                    logger.info("尝试修复设备权限...")
                    platform.posix.system("sudo chmod 666 /dev/snd/* 2>/dev/null || true")

                    // 加大间隔确保命令有效
                    kotlinx.coroutines.delay(300)
                }

                // 检查是否需要先处理其他音频进程
                if (recoveryAttempt >= 2 || deviceSelector.isRaspberryPi() && consecutiveErrors >= 3) {
                    logger.info("尝试清理其他音频进程...")
                    deviceSelector.killOtherAudioProcesses()
                    // 恢复后再等待一小段时间
                    kotlinx.coroutines.delay(200)
                }

                // 特殊处理：从第3次恢复尝试开始，尝试更多的恢复策略
                if (recoveryAttempt >= 3) {
                    logger.info("尝试更多恢复策略 - 尝试 #$recoveryAttempt")
                    when (recoveryAttempt) {
                        3 -> {
                            logger.info("尝试强制卸载并重新加载声卡模块...")
                            platform.posix.system("sudo rmmod snd_microsemi 2>/dev/null || true")
                            platform.posix.system("sudo modprobe snd_microsemi 2>/dev/null || true")
                            // 检查系统日志中可能的音频错误
                            platform.posix.system("dmesg | grep -i audio > /tmp/audio_log.txt 2>/dev/null || true")
                            platform.posix.system("dmesg | grep -i alsa >> /tmp/audio_log.txt 2>/dev/null || true")
                            platform.posix.system("dmesg | grep -i snd >> /tmp/audio_log.txt 2>/dev/null || true")
                            kotlinx.coroutines.delay(700) // 等待模块加载
                        }

                        4 -> {
                            // 尝试重新初始化PortAudio
                            logger.info("尝试重新初始化整个PortAudio...")
                            Pa_Terminate()
                            kotlinx.coroutines.delay(500)
                            initialize("default", currentSampleRate)
                        }

                        5 -> {
                            // 尝试应用最激进的修复方法
                            logger.info("应用最终修复策略...")
                            platform.posix.system("sudo alsactl -F restore 2>/dev/null || true")
                            platform.posix.system("sudo alsactl store 2>/dev/null || true") // 保存当前状态
                            kotlinx.coroutines.delay(1000)
                            // 重新初始化PortAudio
                            if (!portAudioInitialized) {
                                Pa_Initialize()
                                portAudioInitialized = true
                            }
                        }
                    }
                }

                logger.info("尝试重新打开输入流，设备: $selectedInputDeviceIndex, 采样率: $currentSampleRate")

                // 调用openInputStream重新打开流
                val reopened =
                    openInputStream(selectedInputDeviceIndex, currentSampleRate, 2)  // 确保使用2通道

                if (reopened) {
                    logger.info("音频输入流已成功恢复 (尝试 #$recoveryAttempt)")
                    consecutiveErrors = 0
                    audioReadResetNeeded = false
                    return true
                } else if (recoveryAttempt < maxRecoveryAttempts) {
                    logger.warn("恢复尝试 #$recoveryAttempt 失败，将继续尝试...")
                    // 继续下一次循环尝试
                } else {
                    logger.error("经过 $maxRecoveryAttempts 次尝试后，恢复音频输入流失败")
                    return false
                }
            }
        }
        return false
    }

    override fun initialize(deviceName: String, sampleRate: Int): Boolean {
        synchronized(portAudioLock) {
            // 防止重复初始化
            if (portAudioInitialized) {
                logger.info("PortAudio已经初始化，不需要重复初始化")
                return true
            }

            if (_deviceState.value == AudioDeviceState.INITIALIZING ||
                _deviceState.value == AudioDeviceState.ACTIVE
            ) {
                logger.warn("音频设备已经初始化或正在初始化中")
                return true
            }

            _deviceState.value = AudioDeviceState.INITIALIZING
            currentSampleRate = sampleRate

            try {
                // 树莓派上的特殊处理
                logger.info("开始音频设备初始化")
                println("开始音频设备初始化 - 直接打印到标准输出")

                // 强制释放已占用资源，但只做一次
                logger.info("步骤1: 释放音频资源...")
                println("步骤1: 释放音频资源...")

                // 关闭现有流但避免频繁关闭
                if (inputStreamPtr.value != null) {
                    try {
                        Pa_StopStream(inputStreamPtr.value)
                        Pa_CloseStream(inputStreamPtr.value)
                        inputStreamPtr.value = null
                        logger.info("已关闭现有输入流")
                    } catch (e: Exception) {
                        logger.warn("关闭现有输入流异常: ${e.message}")
                    }
                }

                if (outputStreamPtr.value != null) {
                    try {
                        Pa_StopStream(outputStreamPtr.value)
                        Pa_CloseStream(outputStreamPtr.value)
                        outputStreamPtr.value = null
                        logger.info("已关闭现有输出流")
                    } catch (e: Exception) {
                        logger.warn("关闭现有输出流异常: ${e.message}")
                    }
                }

                // 安全一次性释放音频资源
                platform.posix.system("sudo pkill -9 pulseaudio 2>/dev/null || true")
                platform.posix.system("sudo pkill -9 arecord 2>/dev/null || true")
                platform.posix.system("sudo pkill -9 aplay 2>/dev/null || true")
                platform.posix.system("sudo fuser -k /dev/snd/* 2>/dev/null || true")
                logger.info("已终止所有音频相关进程")
                println("已终止所有音频相关进程")

                // 设置设备权限
                platform.posix.system("sudo chmod -R 777 /dev/snd/* 2>/dev/null || true")
                logger.info("已设置音频设备权限为777")
                println("已设置音频设备权限为777")

                // 终止之前的PortAudio，但只在确实需要的时候
                if (portAudioInitialized) {
                    try {
                        Pa_Terminate()
                        logger.info("已终止PortAudio")
                        portAudioInitialized = false
                        // 只等待500ms就足够
                        kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(500) }
                    } catch (e: Exception) {
                        logger.warn("终止PortAudio出错: ${e.message}")
                    }
                }

                // 创建优化的ALSA配置
                logger.info("步骤2: 创建优化的ALSA配置文件...")
                println("步骤2: 创建优化的ALSA配置文件...")
                deviceSelector.fixAlsaConfig()

                // 直接测试设备可用性但不重复测试
                logger.info("步骤3: 检查设备状态...")
                println("步骤3: 检查设备状态...")

                // 测试设备是否可用
                logger.info("试图直接访问音频设备...")
                println("试图直接访问音频设备...")
                val testCmd =
                    "arecord -d 1 -f S16_LE -r 16000 -c 2 -D hw:0,0 /dev/null 2>/tmp/arecord_init_test.log"
                val testResult = platform.posix.system(testCmd)
                if (testResult == 0) {
                    logger.info("成功: 直接ALSA测试通过")
                    println("成功: 直接ALSA测试通过")
                } else {
                    // 只在测试失败时进行额外的重置尝试
                    logger.warn("警告: 直接ALSA测试失败，但仍将继续")
                    println("警告: 直接ALSA测试失败，但仍将继续")

                    // 不要重复加载声卡模块，效果有限且可能导致问题
                    // 如果已经测试失败，只尝试一次重新加载
                    logger.info("尝试卸载并重新加载声卡模块...")
                    println("尝试卸载并重新加载声卡模块...")
                    platform.posix.system("sudo modprobe -r snd_microsemi 2>/dev/null || true")
                    kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(500) }
                    platform.posix.system("sudo modprobe snd_microsemi 2>/dev/null || true")
                    kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(500) }
                }

                // 初始化PortAudio，最多尝试2次
                logger.info("步骤4: 初始化PortAudio...")
                println("步骤4: 初始化PortAudio...")
                var initSuccess = false
                var initAttempt = 0
                val maxInitAttempts = 2  // 减少尝试次数，避免过度重试

                while (!initSuccess && initAttempt < maxInitAttempts) {
                    initAttempt++
                    logger.info("正在执行Pa_Initialize()...尝试 #$initAttempt")
                    println("正在执行Pa_Initialize()...尝试 #$initAttempt")

                    // 第二次尝试前等待
                    if (initAttempt > 1) {
                        // 减少等待时间
                        kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(1000) }
                    }

                    val result = Pa_Initialize()

                    if (result == paNoError) {
                        logger.info("PortAudio初始化成功")
                        println("PortAudio初始化成功")
                        initSuccess = true
                        portAudioInitialized = true
                    } else {
                        val errorMsg = Pa_GetErrorText(result)?.toKString() ?: "未知错误"
                        logger.error("初始化PortAudio失败 (尝试 #$initAttempt): $errorMsg (错误码: $result)")
                        println("初始化PortAudio失败 (尝试 #$initAttempt): $errorMsg (错误码: $result)")

                        // 第一次失败后不再尝试太激进的措施
                        if (initAttempt < maxInitAttempts) {
                            logger.info("将在1秒后重试...")
                            println("将在1秒后重试...")
                        }
                    }
                }

                if (!initSuccess) {
                    logger.error("初始化尝试失败")
                    println("初始化尝试失败")
                    _deviceState.value = AudioDeviceState.IDLE
                    return false
                }

                // 列举设备
                logger.info("步骤5: 列举音频设备...")
                println("步骤5: 列举音频设备...")
                val (inputIdx, outputIdx) = listAudioDevices()
                logger.info("选择的音频设备: 输入=$inputIdx, 输出=$outputIdx")
                println("选择的音频设备: 输入=$inputIdx, 输出=$outputIdx")
                logger.info("音频播放器初始化，使用自身作为音频设备")
                _deviceState.value = AudioDeviceState.READY

                portAudioInitialized = true
                _deviceState.value = AudioDeviceState.READY

                logger.info("音频设备初始化完成")
                println("音频设备初始化完成")
                return true
            } catch (e: Exception) {
                logger.error("初始化音频设备失败: ${e.message}")
                println("初始化音频设备失败: ${e.message}")
                e.printStackTrace()

                _deviceState.value = AudioDeviceState.IDLE
                portAudioInitialized = false
                return false
            }
        }
    }

    override fun start(): Boolean {
        synchronized(portAudioLock) {
            // 如果已经是ACTIVE状态，检查流是否真的存在，如果不存在则尝试修复
            if (_deviceState.value == AudioDeviceState.ACTIVE) {
                if (inputStreamPtr.value == null) {
                    // 状态和实际情况不符，重置为READY并尝试开启流
                    logger.warn("设备状态为ACTIVE但流不存在，重置为READY状态")
                    _deviceState.value = AudioDeviceState.READY
                } else {
                    // 一切正常，设备已激活且有有效流
                    logger.info("设备已在ACTIVE状态且流存在")
                    return true
                }
            }

            if (_deviceState.value != AudioDeviceState.READY) {
                logger.warn("音频设备未就绪，无法启动。当前状态: ${_deviceState.value}")
                return false
            }

            // 设置状态为ACTIVE - 但不在此处开流，避免阻塞
            _deviceState.value = AudioDeviceState.ACTIVE

            // 启动后立即确保输入流打开 - 但使用非阻塞方式
            scope.launch {
                try {
                    if (inputStreamPtr.value == null) {
                        logger.info("设备已启动，正在自动打开输入流...")
                        // 确保使用立体声模式(2通道)
                        if (openInputStream(-1, currentSampleRate, 2)) {
                            logger.info("输入流自动打开成功")
                        } else {
                            logger.warn("自动打开输入流失败")
                        }
                    }
                } catch (e: Exception) {
                    logger.error("自动打开输入流时发生异常: ${e.message}")
                }
            }
            return true
        }
    }

    override fun stop() {
        synchronized(portAudioLock) {
            if (_deviceState.value != AudioDeviceState.ACTIVE) {
                logger.warn("音频设备未处于活动状态，无需停止。当前状态: ${_deviceState.value}")
                return
            }

            // 将状态设为就绪，但不关闭流
            _deviceState.value = AudioDeviceState.READY

            // 同时停止所有播放
            stopPlayback()
        }
    }

    override fun setSampleRate(sampleRate: Int): Boolean {
        if (sampleRate <= 0) {
            logger.error("无效的采样率: $sampleRate")
            return false
        }

        // 只有在设备闲置或就绪状态才能更改采样率
        if (_deviceState.value != AudioDeviceState.IDLE &&
            _deviceState.value != AudioDeviceState.READY
        ) {
            logger.warn("无法在当前状态下更改采样率: ${_deviceState.value}")
            return false
        }

        currentSampleRate = sampleRate
        logger.info("采样率已设置为: $sampleRate")
        return true
    }

    override fun getSampleRate(): Int {
        return currentSampleRate
    }

    override fun listAudioDevices(): Pair<Int, Int> {
        logger.info("列举设备: portAudioInitialized=$portAudioInitialized")
        println("列举设备: portAudioInitialized=$portAudioInitialized")

        // 先确保系统中没有其他进程占用音频设备
        try {
            logger.info("强制释放音频资源...")
            println("强制释放音频资源...")
            deviceSelector.killOtherAudioProcesses()
        } catch (e: Exception) {
            logger.warn("释放音频资源失败: ${e.message}")
            println("释放音频资源失败: ${e.message}")
        }

        // 如果PortAudio未初始化，直接尝试重新初始化一次
        if (!portAudioInitialized) {
            logger.warn("PortAudio未初始化，尝试立即初始化")
            println("PortAudio未初始化，尝试立即初始化")
            // 直接调用Pa_Initialize，不依赖完整的initialize方法
            val result = Pa_Initialize()
            if (result == paNoError) {
                logger.info("直接Pa_Initialize成功")
                println("直接Pa_Initialize成功")
                portAudioInitialized = true

                // 初始化后等待一点时间让系统稳定
                kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(500) }
            } else {
                logger.error("Pa_Initialize失败: ${Pa_GetErrorText(result)?.toKString()}")
                println("Pa_Initialize失败: ${Pa_GetErrorText(result)?.toKString()}")

                // 尝试终止并重新初始化
                try {
                    Pa_Terminate()
                    logger.info("已终止PortAudio，再次尝试初始化")
                    println("已终止PortAudio，再次尝试初始化")
                    kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(1000) }

                    val reinitResult = Pa_Initialize()
                    if (reinitResult == paNoError) {
                        logger.info("第二次Pa_Initialize成功")
                        println("第二次Pa_Initialize成功")
                        portAudioInitialized = true
                    } else {
                        logger.error("第二次Pa_Initialize失败: ${Pa_GetErrorText(reinitResult)?.toKString()}")
                        println("第二次Pa_Initialize失败: ${Pa_GetErrorText(reinitResult)?.toKString()}")
                        // 仍然继续，返回默认设备
                    }
                } catch (e: Exception) {
                    logger.error("终止并重新初始化出错: ${e.message}")
                    println("终止并重新初始化出错: ${e.message}")
                }
            }
        }

        // 重要：无论如何都返回默认设备索引（即便没有设备）
        // 因为我们使用的是Microsemi DAC设备，它有时可能不会被PortAudio正确枚举
        // 但可以通过直接打开hw:0,0来访问
        logger.info("始终使用默认设备索引(0)，以确保能访问Microsemi DAC")
        println("始终使用默认设备索引(0)，以确保能访问Microsemi DAC")

        // 保存选择的设备
        selectedInputDeviceIndex = 0
        selectedOutputDeviceIndex = 0

        return Pair(0, 0)
    }

    override suspend fun openInputStream(
        deviceIndex: Int,
        sampleRate: Int,
        channels: Int
    ): Boolean {
        synchronized(portAudioLock) {
            // 检查全局标志，如果已有流运行，禁止打开新流，以防止资源冲突
            if (globalStreamActive) {
                logger.warn("◆◆◆◆◆ 全局音频流已存在，强制阻止打开新流 ◆◆◆◆◆")
                println("◆◆◆◆◆ 全局音频流已存在，强制阻止打开新流 ◆◆◆◆◆")

                // 强制设置设备状态为活跃，以便允许播放
                if (_deviceState.value != AudioDevice.AudioDeviceState.ACTIVE) {
                    _deviceState.value = AudioDevice.AudioDeviceState.ACTIVE
                    logger.info("强制设置设备状态为ACTIVE以支持播放")
                }

                // 立即返回，完全不做任何流操作，以避免内存冲突
                return true
            }

            if (!portAudioInitialized) {
                logger.error("PortAudio未初始化，尝试先初始化")
                val initSuccess = initialize("default", sampleRate)
                if (!initSuccess) {
                    logger.error("PortAudio初始化失败，无法打开输入流")
                    return false
                }
            }

            // 检查设备权限，而不是杀死进程
            logger.info("打开输入流前检查设备权限")
            platform.posix.system("sudo chmod -R 777 /dev/snd/* 2>/dev/null || true")

            // 避免冗余的资源终止
            if (System.now().toEpochMilliseconds() - lastRecoveryAttemptTimestamp < 10000) {
                logger.info("短时间内已释放过资源，跳过...")
            } else {
                // 只在距离上次恢复时间超过10秒时执行
                lastRecoveryAttemptTimestamp = System.now().toEpochMilliseconds()
                deviceSelector.killOtherAudioProcesses()
            }

            // 再次检查全局标志（可能在资源清理后被其他线程设置）
            if (globalStreamActive) {
                logger.warn("◆◆◆◆◆ 资源清理后检测到全局流已激活，阻止打开新流 ◆◆◆◆◆")
                return true
            }

            return try {
                audioMutex.withLock {
                    logger.info("========== 尝试打开音频输入流 ==========")
                    println("========== 尝试打开音频输入流 ==========")

                    // 先关闭已存在的流
                    if (inputStreamPtr.value != null) {
                        logger.info("关闭已存在的输入流")
                        try {
                            Pa_StopStream(inputStreamPtr.value)
                            Pa_CloseStream(inputStreamPtr.value)
                        } catch (e: Exception) {
                            logger.warn("关闭现有流出错: ${e.message}")
                        }
                        inputStreamPtr.value = null
                        // 等待流完全关闭，但时间不要太长
                        kotlinx.coroutines.delay(200)
                    }

                    // 强制使用2个通道，即使请求的是单通道
                    val actualChannels = 2 // Microsemi DAC 要求使用立体声

                    // 如果请求的不是2个通道，记录警告
                    if (channels != 2) {
                        logger.warn("Microsemi DAC设备要求使用2通道(立体声)，忽略请求的 $channels 通道")
                    }

                    // 尝试多次打开流，但减少次数
                    var success = false
                    var attempts = 0
                    var lastError: String? = null

                    // 尝试不同的参数组合，但减少组合数量，只保留最可能成功的组合
                    val paramCombinations = listOf(
                        Triple(2, 16000, 256),  // 优先尝试：立体声, 16kHz, 256帧
                        Triple(2, 16000, 512),  // 立体声, 16kHz, 512帧
                        Triple(2, 8000, 256),   // 立体声, 8kHz, 256帧
                        Triple(2, 16000, 1024)  // 立体声, 16kHz, 1024帧
                    )

                    logger.info("将尝试 ${paramCombinations.size} 种参数组合:")
                    println("将尝试 ${paramCombinations.size} 种参数组合:")

                    paramCombinations.forEachIndexed { index, (ch, rate, buffer) ->
                        logger.info("组合 #${index + 1} (tid=${threadId()}): 通道数=$ch, 采样率=$rate, 缓冲区大小=$buffer")
                        println("组合 #${index + 1} (tid=${threadId()}): 通道数=$ch, 采样率=$rate, 缓冲区大小=$buffer")
                    }

                    for ((attemptChannels, attemptRate, bufferSize) in paramCombinations) {
                        attempts++

                        if (attempts > 1) {
                            // 再次尝试前确保之前的流已关闭
                            if (inputStreamPtr.value != null) {
                                try {
                                    Pa_StopStream(inputStreamPtr.value)
                                    Pa_CloseStream(inputStreamPtr.value)
                                    inputStreamPtr.value = null
                                } catch (e: Exception) {
                                    logger.warn("关闭之前的流失败: ${e.message}")
                                }
                            }

                            // 减少等待时间
                            kotlinx.coroutines.delay(300)

                            logger.info("尝试组合 #$attempts (tid=${threadId()}): 通道数=$attemptChannels, 采样率=$attemptRate, 缓冲区大小=$bufferSize")
                            println("尝试组合 #$attempts (tid=${threadId()}): 通道数=$attemptChannels, 采样率=$attemptRate, 缓冲区大小=$bufferSize")
                        } else {
                            logger.info("开始尝试第一个参数组合: 通道数=$attemptChannels, 采样率=$attemptRate, 缓冲区大小=$bufferSize")
                            println("开始尝试第一个参数组合: 通道数=$attemptChannels, 采样率=$attemptRate, 缓冲区大小=$bufferSize")
                        }

                        // 每次尝试前先用arecord测试设备可用性，但只做简单测试
                        val testCmd =
                            "arecord -d 1 -f S16_LE -r $attemptRate -c $attemptChannels -D hw:0,0 /dev/null 2>/tmp/arecord_test_${attempts}.log"
                        val testResult = platform.posix.system(testCmd)
                        if (testResult == 0) {
                            logger.info("ALSA设备测试成功")
                            println("ALSA设备测试成功")
                        }

                        // 创建流参数结构体
                        val inputParams =
                            nativeHeap.alloc<com.airobot.portaudiointerop.PaStreamParameters>()
                        inputParams.device = selectedInputDeviceIndex
                        inputParams.channelCount = attemptChannels
                        inputParams.sampleFormat = paInt16
                        inputParams.suggestedLatency = 0.05
                        inputParams.hostApiSpecificStreamInfo = null

                        // 使用Pa_OpenStream而不是Pa_OpenDefaultStream
                        val streamVar = nativeHeap.alloc<COpaquePointerVar>()
                        val result = Pa_OpenStream(
                            stream = streamVar.ptr,
                            inputParameters = inputParams.ptr,
                            outputParameters = null,  // 不使用输出
                            sampleRate = attemptRate.toDouble(),
                            framesPerBuffer = bufferSize.toUInt(),
                            streamFlags = 0u,
                            streamCallback = null,
                            userData = null
                        )

                        if (result == paNoError) {
                            // 存储流指针
                            inputStreamPtr.value = streamVar.value

                            // 启动流
                            val startResult = Pa_StartStream(inputStreamPtr.value)
                            if (startResult == paNoError) {
                                logger.info("====> 音频输入流打开并启动成功（参数组合 #$attempts, tid=${threadId()}）, 通道=$attemptChannels, 采样率=$attemptRate")
                                println("====> 音频输入流打开并启动成功（参数组合 #$attempts: 通道=$attemptChannels, 采样率=$attemptRate）")

                                // 重置语音检测器
                                voiceDetector.reset()

                                // 确保设备状态是ACTIVE
                                if (_deviceState.value != AudioDevice.AudioDeviceState.ACTIVE) {
                                    _deviceState.value = AudioDevice.AudioDeviceState.ACTIVE
                                    logger.info("设备状态已设置为ACTIVE")
                                }

                                // 更新当前采样率
                                currentSampleRate = attemptRate

                                success = true
                                break
                            } else {
                                lastError = Pa_GetErrorText(startResult)?.toKString() ?: "未知错误"
                                logger.error("无法启动音频输入流（参数组合 #$attempts）: $lastError")
                                Pa_CloseStream(inputStreamPtr.value)
                                inputStreamPtr.value = null
                            }
                        } else {
                            lastError = Pa_GetErrorText(result)?.toKString() ?: "未知错误"
                            logger.error("无法打开音频输入流（参数组合 #$attempts）: $lastError")
                        }

                        // 释放本地资源
                        nativeHeap.free(inputParams.rawPtr)
                    }

                    if (!success) {
                        logger.error("尝试所有参数组合后，仍无法打开音频输入流: $lastError")

                        // 失败后不要再尝试最后的挣扎，直接返回失败
                        return@withLock false
                    }

                    // 成功打开流后设置全局标志
                    globalStreamActive = true
                    logger.info("========== 音频输入流打开完成，设置全局标志 ==========")
                    println("========== 音频输入流打开完成，设置全局标志 ==========")
                    return@withLock true
                }
            } catch (e: Exception) {
                logger.error("打开音频输入流时出错: ${e.message}")
                e.printStackTrace()
                return false
            }
        }
    }

    override suspend fun openOutputStream(
        deviceIndex: Int,
        sampleRate: Int,
        channels: Int
    ): Boolean {
        return synchronized(portAudioLock) {
            // ⚠️⚠️⚠️ 修复内存崩溃核心逻辑 ⚠️⚠️⚠️
            // 问题根源：当全局音频流激活时，不能创建新的流，即使是输出流
            // 当任何类型的流已经存在时，必须阻止所有新的流操作
            if (globalStreamActive) {
                logger.warn("⛔⛔⛔ 全局音频流已存在，不允许创建任何新流 (tid=${threadId()}) ⛔⛔⛔")
                // 当全局标志激活时，直接返回失败，完全不尝试创建新流
                // 通过禁止所有并发音频流访问，避免内存崩溃
                return@synchronized false
            }

            // 现有流可以继续使用
            if (outputStreamPtr.value != null) {
                logger.info("输出流已存在，直接复用 (tid=${threadId()})")
                return@synchronized true
            }

            if (!portAudioInitialized) {
                logger.error("PortAudio未初始化，尝试先初始化")
                val initSuccess = initialize("default", sampleRate)
                if (!initSuccess) {
                    logger.error("PortAudio初始化失败，无法打开输出流")
                    return@synchronized false
                }
            }

            // 固定使用16000采样率，无视传入参数
            val actualSampleRate = 16000

            try {
                runBlocking { // Changed to runBlocking for synchronized block
                    audioMutex.withLock {
                        logger.info("尝试打开音频输出流...")

                        // 关闭已存在的流
                        if (outputStreamPtr.value != null) {
                            Pa_StopStream(outputStreamPtr.value)
                            Pa_CloseStream(outputStreamPtr.value)
                            outputStreamPtr.value = null
                        }

                        // 获取实际设备索引
                        val actualDeviceIndex =
                            if (deviceIndex >= 0) deviceIndex else selectedOutputDeviceIndex

                        // 强制使用2个通道，即使请求的是单通道
                        val actualChannels = 2 // Microsemi DAC 要求使用立体声

                        // 如果请求的不是2个通道，记录警告
                        if (channels != 2) {
                            logger.warn("检测到Microsemi DAC设备，强制使用2个通道(立体声)代替请求的 $channels 通道")
                        }

                        // 获取ALSA设备路径（在树莓派上）
                        var alsaDeviceString = ""
                        if (deviceSelector.isRaspberryPi()) {
                            alsaDeviceString =
                                deviceSelector.getALSADeviceString(actualDeviceIndex, false)
                            logger.info("使用ALSA输出设备: $alsaDeviceString, 采样率: $actualSampleRate")
                        }

                        // 尝试多次打开流
                        var success = false
                        var attempts = 0
                        var lastError: String? = null

                        while (!success && attempts < 5) {
                            attempts++

                            // 在每次尝试之间短暂延迟
                            if (attempts > 1) {
                                kotlinx.coroutines.delay(500)
                            }

                            // 创建流参数结构体
                            val outputParams =
                                nativeHeap.alloc<com.airobot.portaudiointerop.PaStreamParameters>()
                            outputParams.device = if (actualDeviceIndex >= 0)
                                actualDeviceIndex
                            else
                                Pa_GetDefaultOutputDevice()
                            outputParams.channelCount = actualChannels
                            outputParams.sampleFormat = paInt16
                            outputParams.suggestedLatency = 0.05
                            outputParams.hostApiSpecificStreamInfo = null

                            // 使用Pa_OpenStream而不是Pa_OpenDefaultStream
                            val streamVar = nativeHeap.alloc<COpaquePointerVar>()
                            val result = Pa_OpenStream(
                                stream = streamVar.ptr,
                                inputParameters = null,  // 不使用输入
                                outputParameters = outputParams.ptr,
                                sampleRate = actualSampleRate.toDouble(),
                                framesPerBuffer = 1024u,
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
                                    logger.info("音频输出流打开并启动成功（尝试 #$attempts, tid=${threadId()}）, 采样率: $actualSampleRate")

                                    // 清空播放缓冲区
                                    audioPlayBufferPos = 0

                                    success = true
                                    break
                                } else {
                                    lastError =
                                        Pa_GetErrorText(startResult)?.toKString() ?: "未知错误"
                                    logger.error("无法启动音频输出流（尝试 #$attempts）: $lastError")
                                    Pa_CloseStream(outputStreamPtr.value)
                                    outputStreamPtr.value = null
                                }
                            } else {
                                lastError = Pa_GetErrorText(result)?.toKString() ?: "未知错误"
                                logger.error("无法打开音频输出流（尝试 #$attempts）: $lastError")
                            }

                            // 释放本地资源
                            nativeHeap.free(outputParams.rawPtr)
                        }

                        if (!success) {
                            logger.error("经过多次尝试后，仍无法打开音频输出流: $lastError")
                            // return@withLock false // Cannot return from withLock in runBlocking directly
                            throw RuntimeException("Failed to open output stream: $lastError")
                        }
                        // true // Cannot return from withLock in runBlocking directly
                    }
                } // End of runBlocking
                true // Return true if runBlocking completes without exception
            } catch (e: Exception) {
                logger.error("打开音频输出流时出错: ${e.message}")
                e.printStackTrace()
                false
            }
        } // End of synchronized block
    }

    // 非suspend版本的writeAudio
    override fun writeAudio(buffer: CPointer<ShortVar>, frameCount: Int): Int {
        synchronized(portAudioLock) {
            if (outputStreamPtr.value == null) {
                logger.error("Sync writeAudio: Output stream is null")
                return -1
            }
            val result = Pa_WriteStream(outputStreamPtr.value, buffer, frameCount.toUInt())
            if (result == paNoError || result == paOutputUnderflowed) {
                return frameCount
            } else {
                logger.error("Sync writeAudio failed: ${Pa_GetErrorText(result)?.toKString()} (code: $result)")
                return -1
            }
        }
    }

    // suspend版本的writeAudio (之前的内容，重命名以避免冲突)
    suspend fun writeAudioSuspend(buffer: CPointer<ShortVar>, frameCount: Int): Int {
        synchronized(portAudioLock) {
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
                        val result =
                            Pa_WriteStream(outputStreamPtr.value, tempBuffer, totalFrames.toUInt())

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
    }

    override suspend fun closeStreams() {
        synchronized(portAudioLock) {
            audioMutex.withLock {
                // 关闭输入流
                if (inputStreamPtr.value != null) {
                    try {
                        Pa_StopStream(inputStreamPtr.value)
                        Pa_CloseStream(inputStreamPtr.value)
                        logger.info("输入音频流已关闭")
                        // 重置全局标志
                        globalStreamActive = false
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
                        Pa_WriteStream(
                            outputStreamPtr.value,
                            tempBuffer,
                            audioPlayBufferPos.toUInt()
                        )

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
    }

    override fun play(audioData: ByteArray, length: Int): Boolean {
        synchronized(portAudioLock) {
            if (globalStreamActive) {
                if (_deviceState.value != AudioDevice.AudioDeviceState.ACTIVE) {
                    _deviceState.value = AudioDevice.AudioDeviceState.ACTIVE
                }
            }
            if (_deviceState.value != AudioDevice.AudioDeviceState.ACTIVE) {
                logger.warn("音频设备未处于活动状态，无法播放音频数据")
                return false
            }

            // 若输出流未打开，主线程同步打开一次，避免并发冲突
            if (outputStreamPtr.value == null) {
                // 全局流激活标志检查 - 安全处理
                if (globalStreamActive) {
                    logger.warn("⛔⛔⛔ 全局音频流活跃中，暂时无法播放音频 ⛔⛔⛔")
                    // 此时不应尝试打开新流，否则会导致崩溃
                    return false
                }

                val success = runBlocking {
                    openOutputStream(selectedOutputDeviceIndex, 16000, 2)
                }
                if (!success) {
                    logger.error("无法打开输出流，播放失败")
                    return false
                }
            }

            // 写入数据
            return try {
                // byteArrayToShortArray then writeAudio (non-suspend)
                val shortArray =
                    voice.util.AudioUtils.byteArrayToShortArray(audioData.copyOfRange(0, length))
                val nativeBuf = nativeHeap.allocArray<ShortVar>(shortArray.size)
                for (i in shortArray.indices) nativeBuf[i] = shortArray[i]
                val framesToWrite = shortArray.size / 2 // Assuming 2 channels

                val written = writeAudio(nativeBuf, framesToWrite) // Call non-suspend writeAudio
                nativeHeap.free(nativeBuf)

                written == framesToWrite // Check if all frames were written
            } catch (e: Exception) {
                logger.error("写入音频数据异常: ${e.message}")
                false
            }
        }
    }

    override fun playAsync(audioData: ByteArray, length: Int, onComplete: () -> Unit): Boolean {
        if (_deviceState.value != AudioDevice.AudioDeviceState.ACTIVE) {
            logger.warn("音频设备未处于活动状态，无法播放音频数据")
            return false
        }

        // 全局流激活标志检查 - 安全处理
        synchronized(portAudioLock) {
            if (globalStreamActive && outputStreamPtr.value == null) {
                logger.warn("⛔⛔⛔ 全局音频流活跃中，暂时无法播放音频 ⛔⛔⛔")
                // 调用完成回调，但报告播放失败
                scope.launch { onComplete() }
                return false
            }
        }

        // 确保输出流已打开
        scope.launch {
            if (outputStreamPtr.value == null) {
                // 再次安全检查，协程内可能状态已变
                synchronized(portAudioLock) {
                    if (globalStreamActive) {
                        logger.warn("⛔⛔⛔ 协程内检测到全局音频流活跃，跳过播放 ⛔⛔⛔")
                        _playbackState.value = PlaybackState.IDLE
                        onComplete()
                        return@launch
                    }
                }

                val success = openOutputStream(selectedOutputDeviceIndex, currentSampleRate, 2)
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

            playShortArray(shortArray) // This internal method uses writeAudioSuspend

            // 播放完成
            _playbackState.value = PlaybackState.IDLE

            // 调用完成回调
            onComplete()
        }
        return true
    }

    // 辅助方法：播放ShortArray数据
    private suspend fun playShortArray(buffer: ShortArray) {
        try {
            // 分批次播放数据
            val chunkSize = 1024
            val tempBuffer = nativeHeap.allocArray<ShortVar>(chunkSize)

            var offset = 0
            while (offset < buffer.size && _playbackState.value == PlaybackState.PLAYING) {
                val remainingFrames = buffer.size - offset
                val framesToPlay = minOf(chunkSize, remainingFrames)

                // 复制数据到临时缓冲区
                for (i in 0 until framesToPlay) {
                    tempBuffer[i] = buffer[offset + i]
                }

                // 写入音频数据 (使用 suspend 版本)
                val framesWritten = writeAudioSuspend(tempBuffer, framesToPlay)
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
            _playbackState.value = PlaybackState.ERROR
        }
    }

    override fun pause() {
        if (_playbackState.value == PlaybackState.PLAYING) {
            _playbackState.value = PlaybackState.PAUSED
            logger.info("暂停音频播放")
        }
    }

    override fun resume() {
        if (_playbackState.value == PlaybackState.PAUSED) {
            _playbackState.value = PlaybackState.PLAYING
            logger.info("恢复音频播放")
        }
    }

    override fun isPlaying(): Boolean {
        return _playbackState.value == PlaybackState.PLAYING
    }

    override fun stopPlayback() {
        if (_playbackState.value == PlaybackState.PLAYING || _playbackState.value == PlaybackState.PAUSED || _playbackState.value == PlaybackState.LOADING) {
            _playbackState.value = PlaybackState.IDLE
            logger.info("停止音频播放")
            audioPlayBufferPos = 0
        }
    }

    override fun release() {
        stop()
        logger.info("释放PortAudioDevice资源")
        synchronized(portAudioLock) {
            // 协程包裹，因为closeStreams是suspend函数
            kotlinx.coroutines.runBlocking {
                closeStreams()
            }

            // 确保指针已重置
            inputStreamPtr.value = null
            outputStreamPtr.value = null

            // 重置所有标志
            globalStreamActive = false

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

            // 重置单例
            instance = null
        }
    }

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

    fun playAudio(audioData: ShortArray): Boolean {
        synchronized(portAudioLock) {
            if (_deviceState.value != AudioDevice.AudioDeviceState.ACTIVE) {
                logger.warn("音频设备未处于活动状态，无法播放音频数据")
                return false
            }

            // 确保输出流已打开
            if (outputStreamPtr.value == null) {
                val success = runBlocking { openOutputStream(selectedOutputDeviceIndex, 16000, 2) }
                if (!success) {
                    logger.error("无法打开输出流，播放失败")
                    return false
                }
            }

            logger.info("playAudio called, len=${audioData.size} tid=${threadId()} outputPtr=${outputStreamPtr.value}")

            return try {
                val nativeBuf = nativeHeap.allocArray<ShortVar>(audioData.size)
                for (i in audioData.indices) {
                    nativeBuf[i] = audioData[i]
                }
                val framesWritten = runBlocking {
                    writeAudioSuspend(
                        nativeBuf,
                        audioData.size
                    )
                } // use suspend version
                nativeHeap.free(nativeBuf)
                framesWritten > 0
            } catch (e: Exception) {
                logger.error("writeAudio 异常: ${e.message}")
                false
            }
        }
    }

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

            // 使用工具类转换字节数据为短整型
            val buffer = voice.util.AudioUtils.byteArrayToShortArray(byteBuffer)

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