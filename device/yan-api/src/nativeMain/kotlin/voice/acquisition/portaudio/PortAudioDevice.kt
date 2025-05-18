@file:OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)

package voice.acquisition.portaudio

import com.airobot.portaudiointerop.Pa_CloseStream
import com.airobot.portaudiointerop.Pa_GetDeviceCount
import com.airobot.portaudiointerop.Pa_GetDeviceInfo
import com.airobot.portaudiointerop.Pa_GetErrorText
import com.airobot.portaudiointerop.Pa_GetHostApiInfo
import com.airobot.portaudiointerop.Pa_Initialize
import com.airobot.portaudiointerop.Pa_OpenDefaultStream
import com.airobot.portaudiointerop.Pa_ReadStream
import com.airobot.portaudiointerop.Pa_StartStream
import com.airobot.portaudiointerop.Pa_StopStream
import com.airobot.portaudiointerop.Pa_Terminate
import com.airobot.portaudiointerop.Pa_WriteStream
import com.airobot.portaudiointerop.paInputOverflowed
import com.airobot.portaudiointerop.paInt16
import com.airobot.portaudiointerop.paNoError
import com.airobot.portaudiointerop.paOutputUnderflowed
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock.System
import platform.posix.FILE
import platform.posix.F_OK
import platform.posix.SEEK_END
import platform.posix.access
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.getenv
import platform.posix.random
import platform.posix.rewind
import platform.posix.system
import voice.api.AudioPlayerApi
import voice.audio.vad.VoiceActivityDetector
import voice.hal.AudioDevice
import voice.hal.LinuxAudioDeviceSelector
import voice.util.LogManager
import kotlin.time.ExperimentalTime

/**
 * PortAudio音频设备实现类
 * 提供基于PortAudio的音频设备功能
 */
class PortAudioDevice : AudioDevice, AudioPlayerApi {
    private val logger = LogManager.getLogger("PortAudioDevice")
    
    // 设备状态 - 实现AudioDevice接口
    private val _deviceState = MutableStateFlow(AudioDevice.AudioDeviceState.IDLE)
    override val deviceState: StateFlow<AudioDevice.AudioDeviceState> = _deviceState.asStateFlow()
    
    // 播放状态
    enum class PlaybackState {
        IDLE,       // 空闲
        LOADING,    // 加载中
        PLAYING,    // 播放中
        PAUSED,     // 暂停
        ERROR       // 错误
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
    
    /**
     * 读取音频数据
     * @param buffer 数据缓冲区
     * @param frameCount 帧数
     * @return 读取的帧数，负值表示错误
     */
    override suspend fun readAudio(buffer: CPointer<ShortVar>, frameCount: Int): Int {
        val currentTimeMs = System.now().toEpochMilliseconds()

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
    
    /**
     * 尝试恢复音频输入流
     * @return Boolean 表示恢复是否成功
     */
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
                    system("sudo chmod 666 /dev/snd/* 2>/dev/null || true")
                    
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
                            system("sudo rmmod snd_microsemi 2>/dev/null || true")
                            system("sudo modprobe snd_microsemi 2>/dev/null || true")
                            // 检查系统日志中可能的音频错误
                            system("dmesg | grep -i audio > /tmp/audio_log.txt 2>/dev/null || true")
                            system("dmesg | grep -i alsa >> /tmp/audio_log.txt 2>/dev/null || true")
                            system("dmesg | grep -i snd >> /tmp/audio_log.txt 2>/dev/null || true")
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
                            system("sudo alsactl -F restore 2>/dev/null || true")
                            system("sudo alsactl store 2>/dev/null || true") // 保存当前状态
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
                val reopened = openInputStream(selectedInputDeviceIndex, currentSampleRate, 2)  // 确保使用2通道
                
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
            // 对于Linux/ALSA系统，尝试优化配置前先检查是否存在损坏的配置
            if (deviceSelector.isRaspberryPi()) {
                logger.info("检测到Linux/树莓派系统，检查现有ALSA配置...")
                
                // 获取用户主目录
                val homeDir = getenv("HOME")?.toKString() ?: ""
                val asoundrcPath = "$homeDir/.asoundrc"
                
                // 检查现有配置文件，如果存在但可能有问题，先删除
                if (access(asoundrcPath, F_OK) == 0) {
                    logger.info("发现现有的.asoundrc文件，备份并创建新文件")
                    
                    // 创建备份（如果可能）
                    val backupCmd = "cp $asoundrcPath ${asoundrcPath}.bak 2>/dev/null || true"
                    system(backupCmd)
                    
                    // 删除旧文件
                    val removeCmd = "rm -f $asoundrcPath"
                    system(removeCmd)
                }
                
                // 直接测试设备可用性
                logger.info("直接测试Microsemi DAC设备可用性...")
                system("arecord -l > /tmp/arecord_devices_list.txt 2>&1")
                system("cat /proc/asound/cards > /tmp/asound_cards.txt")
                system("ls -la /dev/snd > /tmp/snd_devices.txt")
                
                // 设置设备权限
                system("sudo chmod -R 777 /dev/snd/* 2>/dev/null || true")
                
                // 确保没有其他进程占用音频设备
                deviceSelector.killOtherAudioProcesses()
                
                // 创建新的ALSA配置
                deviceSelector.fixAlsaConfig()
                
                // 重新加载ALSA库
                logger.info("尝试重新加载ALSA库配置...")
                system("alsactl restore 2>/dev/null || true")
            }
            
            // 初始化前先检查设备是否可用 - 这是一种快速失败的方法
            if (deviceSelector.isRaspberryPi()) {
                val testCmd = "arecord -d 1 -f S16_LE -r 16000 -c 2 -D hw:0,0 /dev/null 2>/tmp/arecord_direct_test_init.log"
                val testResult = system(testCmd)
                if (testResult != 0) {
                    logger.warn("直接ALSA测试失败，设备可能不可用，但仍将尝试PortAudio初始化")
                    // 记录失败原因
                    system("cat /tmp/arecord_direct_test_init.log")
                } else {
                    logger.info("直接ALSA测试成功，设备可用")
                }
            }
            
            // 初始化PortAudio，增加重试机制和更长的等待时间
            var initSuccess = false
            var initAttempt = 0
            val maxInitAttempts = 8  // 增加到8次尝试
            
            while (!initSuccess && initAttempt < maxInitAttempts) {
                initAttempt++
                logger.info("正在执行Pa_Initialize()...尝试 #$initAttempt")
                
                // 如果是第二次或更高次尝试，先等待更长时间
                if (initAttempt > 1) {
                    // 使用递增的等待时间策略
                    val waitTime = if (initAttempt <= 3) 2000L else 3000L
                    logger.info("等待${waitTime/1000}秒后再次尝试初始化...")
                    kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(waitTime) }
                }
                
                val result = Pa_Initialize()
                
                if (result == paNoError) {
                    logger.info("PortAudio初始化成功")
                    initSuccess = true
                } else {
                    val errorMsg = Pa_GetErrorText(result)?.toKString() ?: "未知错误"
                    logger.error("初始化PortAudio失败 (尝试 #$initAttempt): $errorMsg (错误码: $result)")
                    
                    // 尝试特殊恢复措施
                    when (initAttempt) {
                        2 -> {
                            logger.info("尝试特殊恢复 - 重置ALSA...")
                            system("alsactl kill resurse 2>/dev/null || true")
                            system("alsactl restart 2>/dev/null || true")
                        }
                        3 -> {
                            logger.info("尝试特殊恢复 - 重新加载声卡模块...")
                            system("sudo modprobe -r snd_microsemi 2>/dev/null || true")
                            system("sudo modprobe snd_microsemi 2>/dev/null || true")
                            // 检查是否已加载模块
                            system("lsmod | grep snd_microsemi > /tmp/snd_microsemi_module.txt")
                        }
                        4 -> {
                            logger.info("尝试特殊恢复 - 尝试清理声卡锁...")
                            system("sudo fuser -k /dev/snd/* 2>/dev/null || true")
                            system("sudo chmod 777 /dev/snd/* 2>/dev/null || true")
                        }
                        5 -> {
                            logger.info("尝试特殊恢复 - 使用不同参数重新配置ALSA...")
                            // 创建非常简单的配置
                            val homeDir = getenv("HOME")?.toKString() ?: ""
                            val asoundrcPath = "$homeDir/.asoundrc"
                            val file = fopen(asoundrcPath, "w")
                            if (file != null) {
                                fputs("pcm.!default { type hw card 0 }\n", file)
                                fputs("ctl.!default { type hw card 0 }\n", file)
                                fclose(file)
                                logger.info("已创建最小化ALSA配置")
                            }
                            system("alsactl -F restore 2>/dev/null || true")
                        }
                    }
                    
                    // 最后一次尝试失败，直接返回错误
                    if (initAttempt >= maxInitAttempts) {
                        _deviceState.value = AudioDevice.AudioDeviceState.ERROR
                        logger.error("所有初始化尝试均失败，请检查硬件连接")
                        return false
                    }
                    
                    // 等待后重试
                    kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(1000) }
                }
            }
            
            // 列举设备
            logger.info("正在列举音频设备...")
            val (inputIdx, outputIdx) = listAudioDevices()
            logger.info("选择的音频设备: 输入=$inputIdx, 输出=$outputIdx")
            
            portAudioInitialized = true
            _deviceState.value = AudioDevice.AudioDeviceState.READY
            
            return true
        } catch (e: Exception) {
            e.printStackTrace()
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
        // 如果已经是ACTIVE状态，检查流是否真的存在，如果不存在则尝试修复
        if (_deviceState.value == AudioDevice.AudioDeviceState.ACTIVE) {
            if (inputStreamPtr.value == null) {
                // 状态和实际情况不符，重置为READY并尝试开启流
                logger.warn("设备状态为ACTIVE但流不存在，重置为READY状态")
                _deviceState.value = AudioDevice.AudioDeviceState.READY
            } else {
                // 一切正常，设备已激活且有有效流
                logger.info("设备已在ACTIVE状态且流存在")
                return true
            }
        }
        
        if (_deviceState.value != AudioDevice.AudioDeviceState.READY) {
            logger.warn("音频设备未就绪，无法启动。当前状态: ${_deviceState.value}")
            return false
        }

        // 设置状态为ACTIVE - 但不在此处开流，避免阻塞
        _deviceState.value = AudioDevice.AudioDeviceState.ACTIVE
        
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
    
    /**
     * 停止音频流
     * 明确指定实现的是AudioDevice接口的stop方法
     */
    override fun stop() {
        if (_deviceState.value != AudioDevice.AudioDeviceState.ACTIVE) {
            logger.warn("音频设备未处于活动状态，无需停止。当前状态: ${_deviceState.value}")
            return
        }
        
        // 将状态设为就绪，但不关闭流
        _deviceState.value = AudioDevice.AudioDeviceState.READY
        
        // 同时停止所有播放
        stopPlayback()
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
            logger.error("PortAudio未初始化，尝试先初始化")
            val initSuccess = initialize("default", sampleRate)
            if (!initSuccess) {
                logger.error("PortAudio初始化失败，无法打开输入流")
                return false
            }
        }
        
        return try {
            audioMutex.withLock {
                logger.info("尝试打开音频输入流...")
                
                // 先尝试使用ALSA命令行测试设备可用性
                val testCmd = "arecord -d 1 -f S16_LE -r $sampleRate -c 2 -D hw:0,0 /dev/null 2>/tmp/arecord_direct_test.log"
                val testResult = system(testCmd)
                if (testResult == 0) {
                    logger.info("ALSA直接测试成功，设备可以访问")
                } else {
                    logger.warn("ALSA直接测试失败，尝试修复权限")
                    system("sudo chmod -R 777 /dev/snd/* 2>/dev/null || true")
                    
                    // 第二次尝试，使用不同参数
                    val testCmd2 = "arecord -d 1 -f S16_LE -r 8000 -c 2 -D hw:0,0 /dev/null 2>/tmp/arecord_test2.log"
                    val testResult2 = system(testCmd2)
                    if (testResult2 == 0) {
                        logger.info("第二次ALSA测试成功 (8kHz)")
                    } else {
                        logger.warn("所有ALSA测试都失败，记录设备状态信息")
                        // 检查详细信息
                        system("arecord -l > /tmp/arecord_devices.txt 2>&1")
                        system("ls -la /dev/snd > /tmp/snd_devices_list.txt")
                    }
                }
                
                // 关闭已存在的流
                if (inputStreamPtr.value != null) {
                    Pa_StopStream(inputStreamPtr.value)
                    Pa_CloseStream(inputStreamPtr.value)
                    inputStreamPtr.value = null
                }
                
                // 强制使用2个通道，即使请求的是单通道
                val actualChannels = 2 // Microsemi DAC 要求使用立体声
                
                // 如果请求的不是2个通道，记录警告
                if (channels != 2) {
                    logger.warn("检测到Microsemi DAC设备，强制使用2个通道(立体声)代替请求的 $channels 通道")
                }
                
                // 尝试修复设备权限问题
                try {
                    logger.info("尝试修复音频设备权限...")
                    system("sudo chmod -R 777 /dev/snd/* 2>/dev/null || true")
                } catch (e: Exception) {
                    logger.warn("修复权限失败: ${e.message}")
                }
                
                // 尝试多次打开流
                var success = false
                var attempts = 0
                var lastError: String? = null
                
                // 尝试不同的参数组合
                val paramCombinations = listOf(
                    Triple(2, sampleRate, 256),   // 原始请求采样率，立体声
                    Triple(2, 16000, 256),        // 立体声, 16kHz, 256帧
                    Triple(2, 8000, 128),         // 立体声, 8kHz, 128帧
                    Triple(2, 16000, 512),        // 立体声, 16kHz, 512帧
                    Triple(2, 16000, 1024)        // 立体声, 16kHz, 1024帧
                )
                
                for ((attemptChannels, attemptRate, bufferSize) in paramCombinations) {
                    attempts++
                    
                    if (attempts > 1) {
                        kotlinx.coroutines.delay(300) // 等待一小段时间
                        
                        // 每次尝试前先测试ALSA直接访问
                        val directCmd = "arecord -d 1 -f S16_LE -r $attemptRate -c $attemptChannels -D hw:0,0 /dev/null 2>/dev/null || true"
                        system(directCmd)
                        
                        logger.info("尝试组合 #$attempts: 通道数=$attemptChannels, 采样率=$attemptRate, 缓冲区大小=$bufferSize")
                    }
                    
                    // 使用当前参数组合尝试打开流
                    val result = Pa_OpenDefaultStream(
                        inputStreamPtr.ptr,
                        attemptChannels,    // 通道数
                        0,                  // 无输出
                        paInt16,            // 采样格式
                        attemptRate.toDouble(), // 采样率
                        bufferSize.toUInt(),   // 缓冲区大小
                        null,               // 无回调
                        null                // 无用户数据
                    )
                    
                    // 处理结果
                    if (result == paNoError) {
                        // 启动流
                        val startResult = Pa_StartStream(inputStreamPtr.value)
                        if (startResult == paNoError) {
                            logger.info("音频输入流打开并启动成功（参数组合 #$attempts: 通道=$attemptChannels, 采样率=$attemptRate）")
                            
                            // 重置语音检测器
                            voiceDetector.reset()
                            
                            // 确保设备状态是ACTIVE
                            if (_deviceState.value != AudioDevice.AudioDeviceState.ACTIVE) {
                                _deviceState.value = AudioDevice.AudioDeviceState.ACTIVE
                            }
                            
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
                }
                
                if (!success) {
                    logger.error("尝试所有参数组合后，仍无法打开音频输入流: $lastError")
                    return@withLock false
                }
                
                return@withLock true
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
            logger.error("PortAudio未初始化，尝试先初始化")
            val initSuccess = initialize("default", sampleRate)
            if (!initSuccess) {
                logger.error("PortAudio初始化失败，无法打开输出流")
                return false
            }
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
                
                // 强制使用2个通道，即使请求的是单通道
                val actualChannels = 2 // Microsemi DAC 要求使用立体声
                
                // 如果请求的不是2个通道，记录警告
                if (channels != 2) {
                    logger.warn("检测到Microsemi DAC设备，强制使用2个通道(立体声)代替请求的 $channels 通道")
                }
                
                // 获取ALSA设备路径（在树莓派上）
                var alsaDeviceString = ""
                if (deviceSelector.isRaspberryPi()) {
                    alsaDeviceString = deviceSelector.getALSADeviceString(actualDeviceIndex, false)
                    logger.info("使用ALSA输出设备: $alsaDeviceString")
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
                    
                    // 优先使用默认设备打开流
                    val result = Pa_OpenDefaultStream(
                        outputStreamPtr.ptr,
                        0,                  // 无输入通道
                        actualChannels,     // 使用立体声
                        paInt16,            // 采样格式
                        sampleRate.toDouble(),
                        1024u,              // 使用较小的缓冲区以减少延迟
                        null,               // 无回调函数
                        null                // 无用户数据
                    )
                    
                    // 处理结果
                    if (result == paNoError) {
                        // 启动流
                        val startResult = Pa_StartStream(outputStreamPtr.value)
                        if (startResult == paNoError) {
                            logger.info("音频输出流打开并启动成功（尝试 #$attempts）")
                            
                            // 清空播放缓冲区
                            audioPlayBufferPos = 0
                            
                            success = true
                            break
                        } else {
                            lastError = Pa_GetErrorText(startResult)?.toKString() ?: "未知错误"
                            logger.error("无法启动音频输出流（尝试 #$attempts）: $lastError")
                            Pa_CloseStream(outputStreamPtr.value)
                            outputStreamPtr.value = null
                        }
                    } else {
                        lastError = Pa_GetErrorText(result)?.toKString() ?: "未知错误"
                        logger.error("无法打开音频输出流（尝试 #$attempts）: $lastError")
                    }
                }
                
                if (!success) {
                    logger.error("经过多次尝试后，仍无法打开音频输出流: $lastError")
                    return@withLock false
                }
                
                return@withLock true
            }
        } catch (e: Exception) {
            logger.error("打开音频输出流时出错: ${e.message}")
            e.printStackTrace()
            return false
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
     * 同时实现AudioDevice和AudioPlayerApi的release方法
     */
    override fun release() {
        stop()
        logger.info("释放PortAudioDevice资源")
        
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
     * AudioPlayerApi 接口实现
     */
    override fun initialize(sampleRate: Int, channels: Int): Boolean {
        // 由于本身就是 AudioDevice 实例，直接调用自身的初始化方法
        logger.info("音频播放器初始化，使用自身作为音频设备")
        _playbackState.value = PlaybackState.LOADING
        
        val result = initialize("default", sampleRate)
        if (result) {
            _playbackState.value = PlaybackState.IDLE
        } else {
            _playbackState.value = PlaybackState.ERROR
        }
        
        return result
    }
    
    /**
     * 播放音频数据
     * AudioPlayerApi 接口实现
     */
    override fun play(audioData: ByteArray, length: Int): Boolean {
        if (_deviceState.value != AudioDevice.AudioDeviceState.ACTIVE) {
            logger.warn("音频设备未处于活动状态，无法播放音频数据")
            return false
        }
        
        // 确保输出流已打开
        scope.launch {
            if (outputStreamPtr.value == null) {
                val success = openOutputStream(selectedOutputDeviceIndex, currentSampleRate, 2)
                if (!success) {
                    logger.error("播放音频数据失败：无法打开输出流")
                    _playbackState.value = PlaybackState.ERROR
                    return@launch
                }
            }
            
            _playbackState.value = PlaybackState.LOADING
            
            // 将ByteArray转换为ShortArray
            val shortArray = ShortArray(length / 2)
            for (i in shortArray.indices) {
                val lowByte = audioData[i * 2].toInt() and 0xFF
                val highByte = audioData[i * 2 + 1].toInt() and 0xFF
                shortArray[i] = ((highByte shl 8) or lowByte).toShort()
            }
            
            // 播放音频数据
            _playbackState.value = PlaybackState.PLAYING
            
            playShortArray(shortArray)
            
            // 播放完成
            _playbackState.value = PlaybackState.IDLE
        }
        
        return true
    }
    
    /**
     * 播放音频数据
     * @param buffer 要播放的音频数据
     * @return 是否成功开始播放
     */
    fun playAudio(buffer: ShortArray): Boolean {
        if (_deviceState.value != AudioDevice.AudioDeviceState.ACTIVE) {
            logger.warn("音频设备未处于活动状态，无法播放音频")
            return false
        }
        
        // 确保输出流已打开
        scope.launch {
            if (outputStreamPtr.value == null) {
                val success = openOutputStream(selectedOutputDeviceIndex, currentSampleRate, 2)
                if (!success) {
                    logger.error("播放音频失败：无法打开输出流")
                    _playbackState.value = PlaybackState.ERROR
                    return@launch
                }
            }
            
            _playbackState.value = PlaybackState.PLAYING
            
            // 播放音频数据
            playShortArray(buffer)
            
            // 播放完成
            _playbackState.value = PlaybackState.IDLE
        }
        
        return true
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
            while (offset < buffer.size && _playbackState.value == PlaybackState.PLAYING) {
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
            _playbackState.value = PlaybackState.ERROR
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
    
    /**
     * 异步播放音频数据
     * AudioPlayerApi 接口实现
     */
    override fun playAsync(audioData: ByteArray, length: Int, onComplete: () -> Unit): Boolean {
        if (_deviceState.value != AudioDevice.AudioDeviceState.ACTIVE) {
            logger.warn("音频设备未处于活动状态，无法播放音频数据")
            return false
        }
        
        // 确保输出流已打开
        scope.launch {
            if (outputStreamPtr.value == null) {
                val success = openOutputStream(selectedOutputDeviceIndex, currentSampleRate, 2)
                if (!success) {
                    logger.error("播放音频数据失败：无法打开输出流")
                    _playbackState.value = PlaybackState.ERROR
                    return@launch
                }
            }
            
            _playbackState.value = PlaybackState.LOADING
            
            // 将ByteArray转换为ShortArray
            val shortArray = ShortArray(length / 2)
            for (i in shortArray.indices) {
                val lowByte = audioData[i * 2].toInt() and 0xFF
                val highByte = audioData[i * 2 + 1].toInt() and 0xFF
                shortArray[i] = ((highByte shl 8) or lowByte).toShort()
            }
            
            // 播放音频数据
            _playbackState.value = PlaybackState.PLAYING
            
            playShortArray(shortArray)
            
            // 播放完成
            _playbackState.value = PlaybackState.IDLE
            
            // 调用完成回调
            onComplete()
        }
        
        return true
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
     * 是否正在播放
     */
    override fun isPlaying(): Boolean {
        return _playbackState.value == PlaybackState.PLAYING
    }
    
    /**
     * 停止播放
     */
    override fun stopPlayback() {
        if (_playbackState.value == PlaybackState.PLAYING) {
            _playbackState.value = PlaybackState.IDLE
            logger.info("停止音频播放")
            
            // 尝试清空播放缓冲区
            audioPlayBufferPos = 0
        }
    }
} 