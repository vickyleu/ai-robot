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
import kotlin.math.sqrt
import kotlin.time.ExperimentalTime

/**
 * PortAudio音频设备实现类
 * 提供基于PortAudio的音频设备功能
 */
class PortAudioDevice private constructor() : AudioDevice {
    private val logger = LogManager.getLogger("PortAudioDevice")

    // 单例实现
    companion object {
        @Volatile
        private var instance: PortAudioDevice? = null

        // 将单一标志拆分为两个独立标志
        private var inputStreamActive = false
        private var outputStreamActive = false

        // 全局静态互斥锁，保护所有PortAudio调用
        private val portAudioLock = SynchronizedObject()
        
        // 添加一个专门的流状态互斥锁，解决状态竞争问题
        private val streamStateLock = SynchronizedObject()
        
        // 添加一个设备级别的互斥锁，完全序列化所有设备操作
        private val deviceMutex = Mutex()

        fun getInstance(): PortAudioDevice {
            return instance ?: PortAudioDevice().also { instance = it }
        }

        // 修改检查方法，分别检查输入和输出流
        fun isInputStreamActive(): Boolean {
            return synchronized(streamStateLock) { 
                inputStreamActive 
            }
        }
        
        fun isOutputStreamActive(): Boolean {
            return synchronized(streamStateLock) { 
                outputStreamActive 
            }
        }

        // 设置流状态的方法也拆分为两个
        fun setInputStreamActive(active: Boolean) {
            synchronized(streamStateLock) {
                inputStreamActive = active
            }
        }
        
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

    // 音频流管理
    private val audioMutex = Mutex()
    // 添加专门的读取锁，避免多线程并发访问
    private val readLock = Mutex()
    // 添加专门的写入锁，避免多线程并发访问输出流
    private val writeLock = Mutex()
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

    // 添加流恢复状态标志
    @Volatile
    private var isStreamRecovering = false

    // 流恢复间隔控制
    private var lastRecoveryTime = 0L
    private val recoveryIntervalMs = 30000L // 30秒最多恢复一次

    // 工具: 获取当前线程ID (仅用于调试日志)
    private fun threadId(): ULong = pthread_self().toLong().toULong()

    // 非suspend版本的readAudio - 作为主要实现
    override fun readAudio(buffer: CPointer<ShortVar>, frameCount: Int): Int {
        synchronized(portAudioLock) {
            // 防御性检查：确保frameCount > 0
            if (frameCount <= 0) {
                logger.error("⚠️ readAudio: 无效的帧数要求: $frameCount")
                return 0
            }
            
            // 先检查输入流是否活跃
            val isActive = synchronized(streamStateLock) { inputStreamActive }
            
            // 检查流状态
            if (!isActive || inputStreamPtr.value == null) {
                if (audioReadCounter++ % 100 == 0) {  // 降低日志频率但增加可见性
                    logger.warn("⚠️ readAudio: 流无效 - 活跃状态=$isActive, 流指针=${if (inputStreamPtr.value != null) "有效" else "无效"}")
                }
                // 填充静音
                for (i in 0 until frameCount) {
                    buffer[i] = 0
                }
                return frameCount // 返回读取的帧数（静音）
            }
            
            // 直接调用PortAudio，不涉及协程
            val result = Pa_ReadStream(inputStreamPtr.value, buffer, frameCount.toUInt())
            
            // 音频能量验证，每200帧检查一次，帮助排查设备未捕获声音的问题
            if (audioReadCounter++ % 200 == 0) {
                var energy = 0.0
                for (i in 0 until frameCount) {
                    energy += buffer[i] * buffer[i]
                }
                if (energy > 0) {
                    energy = sqrt(energy / frameCount)
                    logger.info("📢 接收到音频数据，平均能量: $energy")
                } else {
                    logger.warn("⚠️ 接收到静音音频数据(能量为0)，音频设备可能未正确捕获声音")
                }
            }
            
            if (result == paNoError || result == paInputOverflowed) {
                // 正常返回，重置连续错误计数
                if (consecutiveErrors > 0) {
                    consecutiveErrors = 0
                }
                
                return frameCount
            } else {
                // 增加连续错误计数
                consecutiveErrors++
                
                if (audioReadCounter % 20 == 0 || consecutiveErrors >= 3) {  // 更频繁地报告错误
                    logger.warn("⚠️ readAudio失败: ${Pa_GetErrorText(result)?.toKString()} (错误码: $result, 连续错误: $consecutiveErrors)")
                }
                
                // 连续错误过多，触发自动恢复机制
                if (consecutiveErrors >= 5) {
                    logger.error("❌ 连续错误达到阈值，设置重置标记")
                    audioReadResetNeeded = true
                    
                    // 在全局标记恢复需求
                    val currentTimeMs = System.now().toEpochMilliseconds()
                    if (currentTimeMs - lastRecoveryTime > recoveryIntervalMs) {
                        // 这里不调用恢复，而是标记需要恢复，让具有协程上下文的readAudioSuspend处理
                        logger.info("标记需要恢复音频流")
                    }
                }
                
                // 填充静音
                for (i in 0 until frameCount) {
                    buffer[i] = 0
                }
                return frameCount // 即使错误也返回帧数，因为数据被静音填充
            }
        }
    }

    // suspend版本的readAudio - 委托给非suspend版本并添加读取锁
    suspend fun readAudioSuspend(buffer: CPointer<ShortVar>, frameCount: Int): Int {
        try {
            // 添加超时保护，避免无限等待锁
            val lockResult = kotlinx.coroutines.withTimeoutOrNull(5000) { // 5秒超时
                readLock.withLock {
                    performActualRead(buffer, frameCount)
                }
            }
            
            if (lockResult == null) {
                logger.error("readAudioSuspend获取锁超时，返回静音数据")
                // 超时情况下，填充静音数据作为备选
                for (i in 0 until frameCount) {
                    buffer[i] = 0
                }
                return frameCount
            }
            
            return lockResult
        } catch (e: Exception) {
            logger.error("readAudioSuspend发生异常: ${e.message}")
            // 发生异常时，填充静音数据
            for (i in 0 until frameCount) {
                buffer[i] = 0
            }
            return frameCount
        }
    }

    // 实际的读取实现
    private suspend fun performActualRead(buffer: CPointer<ShortVar>, frameCount: Int): Int {
        val currentTimeMs = System.now().toEpochMilliseconds()

        // 防御性检查：确保frameCount > 0
        if (frameCount <= 0) {
            logger.error("无效的帧数: $frameCount")
            return 0
        }

        // 同步检查流状态
        val isActive = synchronized(streamStateLock) { inputStreamActive }
        
        // 如果全局流标志激活但流指针为null，直接返回静音数据
        if (isActive && inputStreamPtr.value == null) {
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

        // 检查输入流是否为null或不活跃
        if (!isActive || inputStreamPtr.value == null) {
            // 输入流不可用，返回静音数据
            if (audioReadCounter++ % 100 == 0) {
                logger.warn("输入流不可用 (active=$isActive, ptr=${inputStreamPtr.value})")
            }

            // 填充静音
            for (i in 0 until frameCount) {
                buffer[i] = 0
            }

            return frameCount
        }

        // 检查是否需要重置音频流
        if (audioReadResetNeeded) {
            audioReadResetNeeded = false // 重置状态标志
            
            // 尝试恢复，但在协程上下文中安全执行
            // 确保恢复操作不会阻塞太久
            val recoverySuccess = kotlinx.coroutines.withTimeoutOrNull(3000) {
                attemptStreamRecovery()
            } ?: false
            
            // 无论恢复结果如何，这一帧都返回静音
            for (i in 0 until frameCount) {
                buffer[i] = 0
            }
            return frameCount
        }

        // 正常执行读取操作，但增加超时保护
        try {
            // 使用withTimeoutOrNull包装实际读取操作，最多等待2秒
            val result = kotlinx.coroutines.withTimeoutOrNull(2000) {
                // 安全检查
                if (frameCount <= 0) {
                    logger.error("无效的帧数: $frameCount")
                    0
                } else {
                    // 使用同步锁保护，确保同一时间只有一个线程访问inputStreamPtr
                    // 不使用readAudio，因为readAudio内部直接使用全局锁，容易死锁
                    synchronized(portAudioLock) {
                        // 再次检查指针有效性
                        val stream = inputStreamPtr.value
                        if (stream != null) {
                            // 直接调用Pa_ReadStream，避免多层嵌套
                            val paResult = Pa_ReadStream(stream, buffer, frameCount.toUInt())
                            if (paResult == paNoError || paResult == paInputOverflowed) {
                                // 成功读取
                                frameCount
                            } else {
                                // 读取失败，记录错误
                                logger.warn("Pa_ReadStream失败: ${Pa_GetErrorText(paResult)?.toKString()}")
                                -1
                            }
                        } else {
                            logger.warn("输入流指针为null")
                            -1
                        }
                    }
                }
            }
            
            // 处理读取结果
            if (result == null) {
                // 读取超时
                logger.error("从输入流读取数据超时，返回静音")
                // 填充静音
                for (i in 0 until frameCount) {
                    buffer[i] = 0
                }
                
                // 标记需要重置，因为读取超时可能是流出问题了
                audioReadResetNeeded = true
                
                return frameCount
            } else if (result < 0) {
                // 读取错误
                logger.error("读取音频数据失败")
                consecutiveErrors++
                
                // 填充静音
                for (i in 0 until frameCount) {
                    buffer[i] = 0
                }
                
                // 连续错误过多，触发恢复
                if (consecutiveErrors >= maxConsecutiveErrors) {
                    audioReadResetNeeded = true
                }
                
                return frameCount
            } else {
                // 读取成功
                logger.info("成功读取音频数据，帧数: $result")
                consecutiveErrors = 0
                return result
            }
        } catch (e: Exception) {
            // 捕获任何异常
            logger.error("读取音频时发生异常: ${e.message}")
            e.printStackTrace()

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
        // 检查是否已在恢复中，避免重复恢复
        if (isStreamRecovering) {
            logger.warn("流已在恢复过程中，跳过重复恢复尝试")
            return false
        }
        
        logger.warn("尝试恢复音频输入流...")
        isStreamRecovering = true // 设置恢复状态标志
        lastRecoveryTime = System.now().toEpochMilliseconds() // 更新恢复时间
        
        try {
            var recoveryAttempt = 0
            val maxRecoveryAttempts = 3  // 减少尝试次数，避免过度重试

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
                        // 重置输入流标志
                        inputStreamActive = false
                    }

                    // 延长延迟，给系统更多时间稳定
                    val delayTime = 500L * recoveryAttempt
                    kotlinx.coroutines.delay(delayTime)

                    // 尝试使用root权限修复设备访问权限
                    if (recoveryAttempt >= 1) {
                        logger.info("尝试修复设备权限...")
                        platform.posix.system("sudo chmod 666 /dev/snd/* 2>/dev/null || true")

                        // 加大间隔确保命令有效
                        kotlinx.coroutines.delay(500)
                    }

                    // 清理其他音频进程
                    logger.info("尝试清理其他音频进程...")
                    deviceSelector.killOtherAudioProcesses()
                    kotlinx.coroutines.delay(500) // 延长等待时间

                    // 特殊处理：从第2次恢复尝试开始，尝试更多的恢复策略
                    if (recoveryAttempt >= 2) {
                        logger.info("尝试更多恢复策略 - 尝试 #$recoveryAttempt")
                        when (recoveryAttempt) {
                            2 -> {
                                logger.info("尝试强制卸载并重新加载声卡模块...")
                                platform.posix.system("sudo rmmod snd_microsemi 2>/dev/null || true")
                                platform.posix.system("sudo modprobe snd_microsemi 2>/dev/null || true")
                                kotlinx.coroutines.delay(1000) // 延长等待模块加载时间
                            }

                            3 -> {
                                // 尝试重新初始化PortAudio
                                logger.info("尝试重新初始化整个PortAudio...")
                                Pa_Terminate()
                                kotlinx.coroutines.delay(1000)
                                initialize("default", currentSampleRate)
                            }
                        }
                    }

                    logger.info("尝试重新打开输入流，设备: $selectedInputDeviceIndex, 采样率: $currentSampleRate")

                    // 调用openInputStream重新打开流
                    val reopened = openInputStream(selectedInputDeviceIndex, currentSampleRate, 2)

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
        } finally {
            isStreamRecovering = false // 恢复完成，重置标志
        }
    }

    override fun initialize(deviceName: String, sampleRate: Int): Boolean {
        // 使用协程级别的锁，确保设备初始化的互斥性
        runBlocking {
            deviceMutex.withLock {
                // 更温和的重置，不全面重置PortAudio
                // 删除：forceResetPortAudio()
                
                // 防止重复初始化
                if (portAudioInitialized) {
                    logger.info("PortAudio已经初始化，不需要重复初始化")
                    return@withLock true
                }

                if (_deviceState.value == AudioDeviceState.INITIALIZING ||
                    _deviceState.value == AudioDeviceState.ACTIVE
                ) {
                    logger.warn("音频设备已经初始化或正在初始化中")
                    return@withLock true
                }

                _deviceState.value = AudioDeviceState.INITIALIZING
                currentSampleRate = sampleRate

                try {
                    // 树莓派上的特殊处理
                    logger.info("开始音频设备初始化")
                    println("开始音频设备初始化 - 直接打印到标准输出")

                    // 优化：温和地释放音频资源
                    logger.info("步骤1: 释放音频资源...")
                    println("步骤1: 释放音频资源...")

                    // 完全重置所有流和标志
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

                    // 更温和地释放系统资源 - 使用pkill而不是kill -9
                    platform.posix.system("pkill pulseaudio 2>/dev/null || true") // 使用pkill不带-9，更温和
                    platform.posix.system("pkill arecord 2>/dev/null || true")
                    platform.posix.system("pkill aplay 2>/dev/null || true")
                    platform.posix.system("sudo fuser -k /dev/snd/* 2>/dev/null || true")
                    logger.info("已终止音频相关进程")
                    println("已终止音频相关进程")

                    // 设置设备权限
                    platform.posix.system("sudo chmod -R 777 /dev/snd/* 2>/dev/null || true")
                    logger.info("已设置音频设备权限为777")
                    println("已设置音频设备权限为777")

                    // 优化：只在未初始化时终止PortAudio
                    if (!portAudioInitialized) {
                        try {
                            Pa_Terminate()
                            logger.info("已终止PortAudio")
                            portAudioInitialized = false
                            // 只等待短暂时间
                            kotlinx.coroutines.delay(300)
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

                        // 优化：重新加载模块的延迟更合理
                        logger.info("尝试卸载并重新加载声卡模块...")
                        println("尝试卸载并重新加载声卡模块...")
                        platform.posix.system("sudo modprobe -r snd_microsemi 2>/dev/null || true")
                        kotlinx.coroutines.delay(1000) // 增加卸载后的等待时间
                        platform.posix.system("sudo modprobe snd_microsemi 2>/dev/null || true")
                        kotlinx.coroutines.delay(1000) // 增加加载后的等待时间
                    }

                    // 初始化PortAudio，不超过2次尝试
                    logger.info("步骤4: 初始化PortAudio...")
                    println("步骤4: 初始化PortAudio...")
                    var initSuccess = false
                    var initAttempt = 0
                    val maxInitAttempts = 2

                    while (!initSuccess && initAttempt < maxInitAttempts) {
                        initAttempt++
                        logger.info("正在执行Pa_Initialize()...尝试 #$initAttempt")
                        println("正在执行Pa_Initialize()...尝试 #$initAttempt")

                        // 第二次尝试前等待
                        if (initAttempt > 1) {
                            kotlinx.coroutines.delay(1000)
                        }

                        val result = Pa_Initialize()

                        if (result == paNoError) {
                            logger.info("✅ PortAudio初始化成功")
                            println("✅ PortAudio初始化成功")
                            initSuccess = true
                            portAudioInitialized = true
                        } else {
                            val errorMsg = Pa_GetErrorText(result)?.toKString() ?: "未知错误"
                            logger.error("初始化PortAudio失败 (尝试 #$initAttempt): $errorMsg (错误码: $result)")
                            println("初始化PortAudio失败 (尝试 #$initAttempt): $errorMsg (错误码: $result)")

                            if (initAttempt < maxInitAttempts) {
                                logger.info("将在1秒后重试...")
                                println("将在1秒后重试...")
                            }
                        }
                    }

                    if (!initSuccess) {
                        logger.error("❌ 初始化尝试失败")
                        println("❌ 初始化尝试失败")
                        _deviceState.value = AudioDeviceState.IDLE
                        return@withLock false
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

                    logger.info("✅ 音频设备初始化完成")
                    println("✅ 音频设备初始化完成")
                    return@withLock true
                } catch (e: Exception) {
                    logger.error("初始化音频设备失败: ${e.message}")
                    println("初始化音频设备失败: ${e.message}")
                    e.printStackTrace()

                    _deviceState.value = AudioDeviceState.IDLE
                    portAudioInitialized = false
                    return@withLock false
                }
            }
        }
        
        // 这个函数应该是无条件返回true的，也就是说即使初始化失败这个函数仍然能正常结束
        // 该方法的运行状态通过_deviceState反映
        return true
    }
    
    // 完全重置PortAudio状态的函数
    private suspend fun forceResetPortAudio() {
        logger.info("正在强制重置PortAudio状态...")
        
        // 终止所有活动进程
        platform.posix.system("ps aux | grep -E 'portaudio|Pa_|arecord|aplay' | grep -v grep | awk '{print $2}' | xargs kill -9 2>/dev/null || true")
        
        // 强制卸载并重载音频模块
        platform.posix.system("sudo rmmod snd_microsemi 2>/dev/null || true")
        kotlinx.coroutines.delay(500)
        platform.posix.system("sudo modprobe snd_microsemi 2>/dev/null || true")
        kotlinx.coroutines.delay(500)
        
        // 强制关闭所有流
        closeAllStreams()
        
        // 如果PortAudio已初始化，强制终止它
        if (portAudioInitialized) {
            try {
                Pa_Terminate()
                logger.info("强制终止PortAudio完成")
                portAudioInitialized = false
                kotlinx.coroutines.delay(1000) // 等待更长时间确保完全释放
            } catch (e: Exception) {
                logger.warn("强制终止PortAudio时出错: ${e.message}")
            }
        }
        
        // 重置所有状态标志
        synchronized(streamStateLock) {
            inputStreamActive = false
            outputStreamActive = false
        }
        
        // 确保指针已重置
        inputStreamPtr.value = null
        outputStreamPtr.value = null
        
        logger.info("PortAudio状态强制重置完成")
    }
    
    // 强制关闭所有流的函数
    private fun closeAllStreams() {
        logger.info("正在强制关闭所有音频流...")
        
        // 关闭输入流
        if (inputStreamPtr.value != null) {
            try {
                Pa_StopStream(inputStreamPtr.value)
                Pa_CloseStream(inputStreamPtr.value)
                logger.info("输入流已强制关闭")
            } catch (e: Exception) {
                logger.warn("强制关闭输入流时出错: ${e.message}")
            } finally {
                inputStreamPtr.value = null
            }
        }
        
        // 关闭输出流
        if (outputStreamPtr.value != null) {
            try {
                Pa_StopStream(outputStreamPtr.value)
                Pa_CloseStream(outputStreamPtr.value)
                logger.info("输出流已强制关闭")
            } catch (e: Exception) {
                logger.warn("强制关闭输出流时出错: ${e.message}")
            } finally {
                outputStreamPtr.value = null
            }
        }
        
        logger.info("所有音频流已强制关闭")
    }

    override suspend fun start(): Boolean {
        logger.info("启动音频设备，当前状态: ${_deviceState.value}")
        
        runBlocking {
            deviceMutex.withLock {
                // 如果已经是ACTIVE状态，检查流是否真的存在，如果不存在则尝试修复
                if (_deviceState.value == AudioDeviceState.ACTIVE) {
                    if (inputStreamPtr.value == null) {
                        // 状态和实际情况不符，重置为READY并尝试开启流
                        logger.warn("⚠️ 设备状态为ACTIVE但流不存在，重置为READY状态")
                        println("⚠️ 设备状态为ACTIVE但流不存在，重置为READY状态")
                        _deviceState.value = AudioDeviceState.READY
                    } else {
                        // 一切正常，设备已激活且有有效流
                        logger.info("✅ 设备已在ACTIVE状态且流存在")
                        println("✅ 设备已在ACTIVE状态且流存在")
                        
                        // 执行健康检查 - 测试流是否能读取数据
                        val isActive = synchronized(streamStateLock) { inputStreamActive }
                        if (!isActive) {
                            logger.warn("⚠️ 流存在但标记为非活跃，重新激活流")
                            synchronized(streamStateLock) {
                                inputStreamActive = true
                            }
                        }
                        
                        return@withLock true
                    }
                }
        
                if (_deviceState.value != AudioDeviceState.READY) {
                    logger.warn("⚠️ 音频设备未就绪，尝试初始化。当前状态: ${_deviceState.value}")
                    println("⚠️ 音频设备未就绪，尝试初始化。当前状态: ${_deviceState.value}")
                    
                    // 强制初始化再试一次，但不中断操作
                    logger.info("🔄 尝试强制初始化设备...")
                    println("🔄 尝试强制初始化设备...")
                    
                    if(!initialize("default", 16000)) {
                        logger.error("❌ 强制初始化失败，但仍将尝试启动")
                        println("❌ 强制初始化失败，但仍将尝试启动")
                        // 即使初始化失败，也继续尝试设置状态和打开流
                    } else {
                        logger.info("✅ 强制初始化成功，设备状态: ${_deviceState.value}")
                        println("✅ 强制初始化成功，设备状态: ${_deviceState.value}")
                    }
                }
        
                // 设置状态为ACTIVE
                _deviceState.value = AudioDeviceState.ACTIVE
                logger.info("✅ 设备状态已设置为ACTIVE")
                println("✅ 设备状态已设置为ACTIVE")
        
                // --------- ⚠️ 锁已释放，此处执行可能会重新获取 deviceMutex ---------
        
                // 输入流将在 PortAudioAcquisition.startCapture() 中按需打开，
                // 这里不再尝试，以避免阻塞启动流程。
        
                return@runBlocking
            }
        }
        
        // 检查最终状态
        val finalState = deviceState.value
        val streamActive = synchronized(streamStateLock) { inputStreamActive }
        logger.info("音频设备启动完成，最终状态: $finalState, 输入流: ${if(streamActive) "有效" else "无效"}")
        
        return finalState == AudioDeviceState.ACTIVE && streamActive
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
                runBlocking { kotlinx.coroutines.delay(500) }
            } else {
                logger.error("Pa_Initialize失败: ${Pa_GetErrorText(result)?.toKString()}")
                println("Pa_Initialize失败: ${Pa_GetErrorText(result)?.toKString()}")

                // 尝试终止并重新初始化
                try {
                    Pa_Terminate()
                    logger.info("已终止PortAudio，再次尝试初始化")
                    println("已终止PortAudio，再次尝试初始化")
                    runBlocking { kotlinx.coroutines.delay(1000) }

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

        // 动态选择第一个具有输入通道的设备，若找不到则退回索引 0
        var firstInput = -1
        var firstOutput = -1
        for (idx in 0 until Pa_GetDeviceCount()) {
            val info = Pa_GetDeviceInfo(idx)?.pointed ?: continue
            if (firstInput < 0 && info.maxInputChannels > 0) firstInput = idx
            if (firstOutput < 0 && info.maxOutputChannels > 0) firstOutput = idx
        }

        if (firstInput < 0) firstInput = 0
        if (firstOutput < 0) firstOutput = 0

        logger.info("自动选择设备: 输入=$firstInput, 输出=$firstOutput")
        println("自动选择设备: 输入=$firstInput, 输出=$firstOutput")

        selectedInputDeviceIndex = firstInput
        selectedOutputDeviceIndex = firstOutput

        return Pair(firstInput, firstOutput)
    }

    override suspend fun openInputStream(
        deviceIndex: Int,
        sampleRate: Int,
        channels: Int
    ): Boolean {
        return deviceMutex.withLock {
            logger.info("⭐ [openInputStream ENTRY] 设备状态: ${_deviceState.value}, PortAudio初始化: $portAudioInitialized, 输入流活跃: $inputStreamActive, 输出流活跃: $outputStreamActive")
            // 打印更详细的设备初始化状态
            logger.info("⭐ 尝试打开输入流，当前设备状态: ${_deviceState.value}，初始化状态: ${if(portAudioInitialized) "已初始化" else "未初始化"}")
            // println("⭐ 尝试打开输入流，当前设备状态: ${_deviceState.value}，初始化状态: ${if(portAudioInitialized) "已初始化" else "未初始化"}") // User removed this
            
            // 如果已有输入流，直接返回成功但不实际打开新流
            if (inputStreamActive) {
                logger.warn("◆◆◆◆◆ [openInputStream EXIT] 输入流已存在，返回 true ◆◆◆◆◆")
                // println("◆◆◆◆◆ 输入流已存在，无法打开新的输入流 ◆◆◆◆◆")

                // 强制设置设备状态为活跃，以便允许播放
                if (_deviceState.value != AudioDeviceState.ACTIVE) {
                    _deviceState.value = AudioDeviceState.ACTIVE
                    logger.info("强制设置设备状态为ACTIVE以支持播放")
                }

                // 立即返回，不做任何流操作
                return@withLock true
            }

            if (!portAudioInitialized) {
                logger.error("⚠️ [openInputStream] PortAudio未初始化，尝试先初始化")
                // println("⚠️ PortAudio未初始化，尝试先初始化")
                val initSuccess = initialize("default", sampleRate)
                if (!initSuccess) {
                    logger.error("❌ [openInputStream EXIT] PortAudio初始化失败，无法打开输入流，返回 false")
                    // println("❌ PortAudio初始化失败，无法打开输入流")
                    return@withLock false
                }
                logger.info("✅ [openInputStream] PortAudio初始化成功")
                // println("✅ PortAudio初始化成功")
            }

            // 删除：不再使用forceResetPortAudio，避免过度重置
            // forceResetPortAudio()

            // 检查设备权限
            logger.info("[openInputStream] 打开输入流前检查设备权限")
            platform.posix.system("sudo chmod -R 777 /dev/snd/* 2>/dev/null || true")

            // 更温和地关闭已存在的流
            if (inputStreamPtr.value != null) {
                logger.info("[openInputStream] 关闭已存在的输入流")
                try {
                    Pa_StopStream(inputStreamPtr.value)
                    Pa_CloseStream(inputStreamPtr.value)
                } catch (e: Exception) {
                    logger.warn("[openInputStream] 关闭现有流出错: ${e.message}")
                }
                inputStreamPtr.value = null
                // 等待流完全关闭，但时间不要太长
                kotlinx.coroutines.delay(200)
            }

            // 更温和的资源释放策略
            val currentTime = System.now().toEpochMilliseconds()
            if (currentTime - lastRecoveryAttemptTimestamp > 30000) { // 只有超过30秒才释放资源
                logger.info("[openInputStream] 释放音频资源（间隔性）...")
                lastRecoveryAttemptTimestamp = currentTime
                // 使用更温和的方式释放资源
                platform.posix.system("pkill -9 pulseaudio 2>/dev/null || true")
                platform.posix.system("pkill -9 arecord 2>/dev/null || true")
                platform.posix.system("pkill -9 aplay 2>/dev/null || true")
                kotlinx.coroutines.delay(300)
            }

            try {
                logger.info("========== [openInputStream] 尝试打开音频输入流 ==========")
                // println("========== 尝试打开音频输入流 ==========")

                // 强制使用2个通道，即使请求的是单通道

                // 如果请求的不是2个通道，记录警告
                if (channels != 2) {
                    logger.warn("[openInputStream] Microsemi DAC设备要求使用2通道(立体声)，忽略请求的 $channels 通道")
                }

                // 尝试多次打开流，但减少次数
                var success = false
                var attempts = 0
                var lastError: String? = null

                // 减少参数组合，只使用最稳定的组合
                val paramCombinations = listOf(
                    Triple(2, 16000, 512),  // 立体声, 16kHz, 512帧
                    Triple(2, 16000, 1024)  // 立体声, 16kHz, 1024帧
                )

                logger.info("[openInputStream] 将尝试 ${paramCombinations.size} 种参数组合:")
                // println("将尝试 ${paramCombinations.size} 种参数组合:")

                paramCombinations.forEachIndexed { index, (ch, rate, buffer) ->
                    logger.info("[openInputStream] 组合 #${index + 1} (tid=${threadId()}): 通道数=$ch, 采样率=$rate, 缓冲区大小=$buffer")
                    // println("组合 #${index + 1} (tid=${threadId()}): 通道数=$ch, 采样率=$rate, 缓冲区大小=$buffer")
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
                                logger.warn("[openInputStream] 关闭之前的流失败: ${e.message}")
                            }
                        }

                        // 减少等待时间
                        kotlinx.coroutines.delay(300)

                        logger.info("[openInputStream] 尝试组合 #$attempts (tid=${threadId()}): 通道数=$attemptChannels, 采样率=$attemptRate, 缓冲区大小=$bufferSize")
                        // println("尝试组合 #$attempts (tid=${threadId()}): 通道数=$attemptChannels, 采样率=$attemptRate, 缓冲区大小=$bufferSize")
                    } else {
                        logger.info("[openInputStream] 开始尝试第一个参数组合: 通道数=$attemptChannels, 采样率=$attemptRate, 缓冲区大小=$bufferSize")
                        // println("开始尝试第一个参数组合: 通道数=$attemptChannels, 采样率=$attemptRate, 缓冲区大小=$bufferSize")
                    }

                    // 创建流参数结构体
                    val inputParams =
                        nativeHeap.alloc<com.airobot.portaudiointerop.PaStreamParameters>()
                    try {
                        inputParams.device = selectedInputDeviceIndex
                        inputParams.channelCount = attemptChannels
                        inputParams.sampleFormat = paInt16
                        inputParams.suggestedLatency = 0.05
                        inputParams.hostApiSpecificStreamInfo = null

                        // 使用Pa_OpenStream而不是Pa_OpenDefaultStream
                        val streamVar = nativeHeap.alloc<COpaquePointerVar>()
                        try {
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
                                    logger.info("====> [openInputStream] 音频输入流打开并启动成功（参数组合 #$attempts, tid=${threadId()}）, 通道=$attemptChannels, 采样率=$attemptRate")
                                    // println("====> 音频输入流打开并启动成功（参数组合 #$attempts: 通道=$attemptChannels, 采样率=$attemptRate）")

                                    // 重置语音检测器
                                    voiceDetector.reset()

                                    // 确保设备状态是ACTIVE
                                    if (_deviceState.value != AudioDeviceState.ACTIVE) {
                                        _deviceState.value = AudioDeviceState.ACTIVE
                                        logger.info("[openInputStream] 设备状态已设置为ACTIVE")
                                    }

                                    // 更新当前采样率
                                    currentSampleRate = attemptRate

                                    // 设置输入流标志 - 使用专用锁
                                    synchronized(streamStateLock) {
                                        inputStreamActive = true
                                    }

                                    // ---------- 可选的音频流测试读取 ----------
                                    try {
                                        val skipTest = platform.posix.getenv("PA_SKIP_STREAM_TEST") != null
                                        if (skipTest) {
                                            logger.info("🔄 跳过音频流测试读取 (PA_SKIP_STREAM_TEST 已设置)")
                                        } else {
                                            // 使用withTimeout限制测试读取时间，避免长时间阻塞
                                            kotlinx.coroutines.withTimeoutOrNull(1000) {
                                                val testBufferSize = 2048
                                                val testBuffer = nativeHeap.allocArray<ShortVar>(testBufferSize)
                                                try {
                                                    val framesToRead = 512u
                                                    val testResult = Pa_ReadStream(inputStreamPtr.value, testBuffer, framesToRead)
                                                    if (testResult == paNoError || testResult == paInputOverflowed) {
                                                        logger.info("✅ [openInputStream] 音频流测试读取成功 (frames=$framesToRead)")
                                                    } else {
                                                        logger.warn("⚠️ [openInputStream] 音频流测试读取失败: ${Pa_GetErrorText(testResult)?.toKString()}")
                                                    }
                                                } finally {
                                                    nativeHeap.free(testBuffer)
                                                }
                                            } ?: logger.warn("⚠️ [openInputStream] 流测试读取超时，跳过")
                                        }
                                    } catch (e: Exception) {
                                        logger.warn("⚠️ [openInputStream] 测试读取音频时异常: ${e.message}")
                                    }

                                    success = true
                                    break
                                } else {
                                    lastError = Pa_GetErrorText(startResult)?.toKString() ?: "未知错误"
                                    logger.error("[openInputStream] 无法启动音频输入流（参数组合 #$attempts）: $lastError")
                                    Pa_CloseStream(inputStreamPtr.value)
                                    inputStreamPtr.value = null
                                }
                            } else {
                                lastError = Pa_GetErrorText(result)?.toKString() ?: "未知错误"
                                logger.error("[openInputStream] 无法打开音频输入流（参数组合 #$attempts）: $lastError")
                            }
                        } finally {
                            // 不再手动释放 streamVar.rawPtr，以避免潜在的 double free。
                        }
                    } finally {
                        // 释放本地参数结构体
                        nativeHeap.free(inputParams.rawPtr)
                    }
                }

                if (!success) {
                    logger.error("❌ [openInputStream EXIT] 尝试所有参数组合后，仍无法打开音频输入流: $lastError，返回 false")
                    return@withLock false
                }

                logger.info("========== [openInputStream EXIT] 音频输入流打开完成，返回 true ==========")
                // println("========== 音频输入流打开完成 ==========")
                return@withLock true
            } catch (e: Exception) {
                logger.error("❌ [openInputStream EXIT] 打开音频输入流时出错: ${e.message}，返回 false")
                e.printStackTrace()

                // 确保在异常情况下重置标志
                synchronized(streamStateLock) {
                    inputStreamActive = false
                }
                inputStreamPtr.value = null
                return@withLock false
            }
        }
    }

    override suspend fun openOutputStream(
        deviceIndex: Int,
        sampleRate: Int,
        channels: Int
    ): Boolean {
        return deviceMutex.withLock {
            // 严格检查：如果输入流活跃，直接拒绝打开输出流
            if (inputStreamActive) {
                logger.warn("⛔⛔⛔ 输入流处于活动状态，为避免稳定性问题，禁止打开输出流 ⛔⛔⛔")
                return@withLock false
            }
            
            // 如果输出流已经处于活跃状态
            if (outputStreamActive) {
                logger.warn("⛔⛔⛔ 输出流已存在，无法创建新的输出流 (tid=${threadId()}) ⛔⛔⛔")
                return@withLock false
            }
            
            // 现有流可以继续使用
            if (outputStreamPtr.value != null) {
                logger.info("输出流已存在，直接复用 (tid=${threadId()})")
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

            // 固定使用16000采样率，无视传入参数
            val actualSampleRate = 16000

            // 获取实际设备索引
            val actualDeviceIndex =
                if (deviceIndex >= 0) deviceIndex else selectedOutputDeviceIndex

            // 强制使用2个通道，即使请求的是单通道
            val actualChannels = 2 // Microsemi DAC 要求使用立体声

            // 如果请求的不是2个通道，记录警告
            if (channels != 2) {
                logger.warn("检测到Microsemi DAC设备，强制使用2个通道(立体声)代替请求的 $channels 通道")
            }

            // 标记输出流正在处理中
            synchronized(streamStateLock) {
                outputStreamActive = true
            }
            
            var success = false
            try {
                // 开始尝试打开流
                var attempts = 0
                val maxAttempts = 3

                while (!success && attempts < maxAttempts) {
                    attempts++

                    // 在每次尝试之间短暂延迟
                    if (attempts > 1) {
                        kotlinx.coroutines.delay(500)
                    }

                    // 最后一次安全检查：确保输入流没有变成活跃状态
                    if (inputStreamActive) {
                        logger.warn("⛔⛔⛔ 打开过程中检测到输入流已变为活跃状态，终止操作 ⛔⛔⛔")
                        return@withLock false
                    }

                    // 创建流参数结构体
                    val outputParams =
                        nativeHeap.alloc<com.airobot.portaudiointerop.PaStreamParameters>()
                    
                    try {
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
                        try {
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
                            // 不再手动释放 streamVar.rawPtr，以避免潜在的 double free。
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

    // 非suspend版本的writeAudio
    override fun writeAudio(buffer: CPointer<ShortVar>, frameCount: Int): Int {
        synchronized(portAudioLock) {
            // 先检查输出流是否活跃
            val isActive = synchronized(streamStateLock) { outputStreamActive }
            
            if (!isActive || outputStreamPtr.value == null) {
                logger.error("Sync writeAudio: Output stream is null or inactive")
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

    // suspend版本的writeAudio (改名为writeAudioSuspend)
    suspend fun writeAudioSuspend(buffer: CPointer<ShortVar>, frameCount: Int): Int {
        return writeLock.withLock {
            performActualWrite(buffer, frameCount)
        }
    }

    // 实际的写入实现
    private suspend fun performActualWrite(buffer: CPointer<ShortVar>, frameCount: Int): Int {
        // 检查输出流状态
        val isActive = synchronized(streamStateLock) { outputStreamActive }
        
        if (!isActive || outputStreamPtr.value == null) {
            logger.error("音频输出流未打开或不活跃")
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

    override suspend fun closeStreams() {
        deviceMutex.withLock {
            logger.info("关闭所有音频流...")
            // 先获取互斥锁，确保不与其他流操作冲突
            try {
                // 关闭输入流
                if (inputStreamPtr.value != null) {
                    try {
                        Pa_StopStream(inputStreamPtr.value)
                        Pa_CloseStream(inputStreamPtr.value)
                        logger.info("输入音频流已关闭")
                        
                        // 在关闭后重置输入流指针
                        inputStreamPtr.value = null
                        
                        // 重置输入流标志 - 使用专用锁
                        synchronized(streamStateLock) {
                            inputStreamActive = false
                        }
                    } catch (e: Exception) {
                        logger.warn("关闭输入流时出错: ${e.message}")
                        // 即使出错也重置指针和标志
                        inputStreamPtr.value = null
                        synchronized(streamStateLock) {
                            inputStreamActive = false
                        }
                    }
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
                        // 出错时也清空缓冲区
                        audioPlayBufferPos = 0
                    }
                }

                // 关闭输出流
                if (outputStreamPtr.value != null) {
                    try {
                        Pa_StopStream(outputStreamPtr.value)
                        Pa_CloseStream(outputStreamPtr.value)
                        logger.info("输出音频流已关闭")
                        
                        // 在关闭后重置输出流指针
                        outputStreamPtr.value = null
                        
                        // 重置输出流标志 - 使用专用锁
                        synchronized(streamStateLock) {
                            outputStreamActive = false
                        }
                    } catch (e: Exception) {
                        logger.warn("关闭输出流时出错: ${e.message}")
                        // 即使出错也重置指针和标志
                        outputStreamPtr.value = null
                        synchronized(streamStateLock) {
                            outputStreamActive = false
                        }
                    }
                }
                
                logger.info("所有音频流已关闭")
            } catch (e: Exception) {
                logger.error("关闭流时发生异常: ${e.message}")
                // 确保在任何异常情况下重置所有标志和指针
                inputStreamPtr.value = null
                outputStreamPtr.value = null
                synchronized(streamStateLock) {
                    inputStreamActive = false
                    outputStreamActive = false
                }
            }
        }
    }

    override fun play(audioData: ByteArray, length: Int): Boolean {
        // 确保所有播放操作都在synchronized块内
        return synchronized(portAudioLock) {
            if (inputStreamActive) {
                if (_deviceState.value != AudioDeviceState.ACTIVE) {
                    _deviceState.value = AudioDeviceState.ACTIVE
                }
            }
            if (_deviceState.value != AudioDeviceState.ACTIVE) {
                logger.warn("音频设备未处于活动状态，无法播放音频数据")
                return@synchronized false
            }

            // 若输出流未打开，主线程同步打开一次，避免并发冲突
            if (outputStreamPtr.value == null) {
                // 安全检查：确保不在打开输出流时尝试播放
                if (outputStreamActive) {
                    logger.warn("⛔⛔⛔ 输出流正在打开中，暂时无法播放音频 ⛔⛔⛔")
                    return@synchronized false
                }

                val success = runBlocking {
                    openOutputStream(selectedOutputDeviceIndex, 16000, 2)
                }
                if (!success) {
                    logger.error("无法打开输出流，播放失败")
                    return@synchronized false
                }
            }

            // 写入数据
            try {
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
        if (_deviceState.value != AudioDeviceState.ACTIVE) {
            logger.warn("音频设备未处于活动状态，无法播放音频数据")
            return false
        }

        // 先同步检查流状态 - 避免多个playAsync竞争
        val canPlay = synchronized(portAudioLock) {
            if (outputStreamActive && outputStreamPtr.value == null) {
                logger.warn("⛔⛔⛔ 输出流正在处理中，暂时无法播放音频 ⛔⛔⛔")
                false
            } else {
                true
            }
        }
        
        if (!canPlay) {
            // 调用完成回调，但报告播放失败
            scope.launch { onComplete() }
            return false
        }

        // 在协程中确保线程安全地访问输出流
        scope.launch {
            // 对整个播放过程应用锁保护
            writeLock.withLock {
                try {
                    if (outputStreamPtr.value == null) {
                        // 再次安全检查，协程内可能状态已变
                        synchronized(portAudioLock) {
                            if (outputStreamActive) {
                                logger.warn("⛔⛔⛔ 协程内检测到输出流正在处理中，跳过播放 ⛔⛔⛔")
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

                // 使用受保护的writeAudioSuspend
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
        
        runBlocking {
            deviceMutex.withLock {
                try {
                    // 首先强制重置所有状态
                    forceResetPortAudio()
                    
                    // 协程包裹，因为closeStreams是suspend函数
                    closeStreams()

                    // 确保指针已重置
                    inputStreamPtr.value = null
                    outputStreamPtr.value = null

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
                    
                    consecutiveErrors = 0
                    audioReadResetNeeded = false
                    _deviceState.value = AudioDeviceState.IDLE

                    logger.info("PortAudioDevice资源已完全释放")
                } catch (e: Exception) {
                    logger.error("释放资源时出错: ${e.message}")
                }
            }
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
            if (_deviceState.value != AudioDeviceState.ACTIVE) {
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