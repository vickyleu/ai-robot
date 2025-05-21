@file:OptIn(
    ExperimentalForeignApi::class, ExperimentalTime::class,
    ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class
)

package voice.acquisition.portaudio

import com.airobot.core.utils.FormatUtil
import com.airobot.core.utils.format
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import voice.hal.AudioDevice
import voice.util.AudioDefaults
import voice.util.AudioUtils
import voice.util.LogManager
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.ExperimentalTime

/**
 * PortAudio音频采集实现
 * 负责从真实音频设备采集原始PCM数据
 */
@OptIn(ExperimentalTime::class)
class PortAudioAcquisition(
    internal val config: AudioConfig = AudioConfig(
        sampleRate = AudioDefaults.TARGET_SAMPLE_RATE, // 统一由常量控制
        channels = AudioDefaults.CHANNELS
    )
) : AudioDevice {
    private val logger = LogManager.getLogger("PortAudioAcquisition")

    // 音频设备实例
    private val audioDevice = PortAudioDevice.getInstance()

    // 设备状态 - PortAudioAcquisition自身也需要维护一个状态，尽管它可能委托给audioDevice
    private val _deviceState = MutableStateFlow(AudioDevice.AudioDeviceState.IDLE)
    override val deviceState: StateFlow<AudioDevice.AudioDeviceState> = _deviceState.asStateFlow()

    // 采集作用域和Job
    private var captureScope: CoroutineScope? = null
    private var captureJob: Job? = null

    // 原生缓冲区 - 根据config.channels调整
    private val frameSize = 256  // 每次读取的帧数

    // 增大缓冲区大小，避免 PortAudio 返回超出预期帧数时的越界问题
    // 使用 frameSize * channels * 安全系数(8)，确保足够大
    private val bufferSize = frameSize * 2 * 8 // 4096 shorts
    private val buffer = nativeHeap.allocArray<ShortVar>(bufferSize)

    // 添加日志记录每次实际读取的帧数
    private var maxFramesEverRead = 0

    // 复用的 ByteArray，大小 = bufferSize*2 字节
    private val byteBuffer = ByteArray(bufferSize * 2)

    // 状态标志和统计
    @Volatile
    private var isCapturing = false
    private var frameCounter = 0
    private var totalBytesRead = 0L
    private var startTime = 0L
    private var lastLogTime = 0L

    // 回调函数，用于传递采集到的音频数据
    var onAudioDataReceived: ((ByteArray, Int) -> Unit)? = null

    /**
     * 音频采集配置
     */
    data class AudioConfig(
        val sampleRate: Int = AudioDefaults.TARGET_SAMPLE_RATE,    // 采样率
        val channels: Int = AudioDefaults.CHANNELS,          // 通道数, Defaulting to 2 for Microsemi
        val bitsPerSample: Int = 16     // 每样本位数
    )

    override fun initialize(deviceName: String, sampleRate: Int): Boolean {
        _deviceState.value = AudioDevice.AudioDeviceState.INITIALIZING
        val success = audioDevice.initialize(deviceName, sampleRate)
        _deviceState.value =
            if (success) AudioDevice.AudioDeviceState.READY else AudioDevice.AudioDeviceState.IDLE
        return success
    }

    /**
     * 启动音频采集
     * @param callback 音频数据回调函数
     */
    fun startCapture(callback: (ByteArray, Int) -> Unit) {
        if (isCapturing) {
            logger.warn("音频采集已经在运行中")
            return
        }

        onAudioDataReceived = callback
        isCapturing = true
        frameCounter = 0
        totalBytesRead = 0L
        startTime = Clock.System.now().toEpochMilliseconds()
        lastLogTime = startTime

        // 创建专用的采集协程作用域
        captureScope = CoroutineScope(Dispatchers.Default)
        captureJob = captureScope?.launch {
            logger.info("开始采集音频")
            logger.info("音频采集中... Channels: ${config.channels}, SampleRate: ${config.sampleRate}, 缓冲区大小: $bufferSize")

            // 确保音频设备处于活动状态
            if (audioDevice.deviceState.value != AudioDevice.AudioDeviceState.ACTIVE) {
                logger.info("音频设备未激活，尝试启动...")
                val success = audioDevice.start()
                if (!success) {
                    logger.error("无法启动音频设备，采集终止")
                    isCapturing = false
                    return@launch
                }
                // 等待设备稳定
                delay(300)
            }

            // 确保输入流已打开
            if (!PortAudioDevice.isInputStreamActive()) {
                logger.info("输入流未打开，尝试打开输入流 ...")
                val opened = audioDevice.openInputStream(-1, config.sampleRate, config.channels)
                if (!opened) {
                    logger.error("打开输入流失败，采集终止")
                    isCapturing = false
                    return@launch
                }
                // 等待流稳定
                delay(200)
            }

            while (isActive && isCapturing) {
                try {
                    // 如检测到输入流被关闭，自动重试一次
                    if (!PortAudioDevice.isInputStreamActive()) {
                        logger.warn("检测到输入流非活跃，尝试重新打开 ...")
                        val reopened =
                            audioDevice.openInputStream(-1, config.sampleRate, config.channels)
                        if (!reopened) {
                            logger.error("重新打开输入流失败，填充静音并继续")
                            // 短暂延迟，继续循环
                            delay(100)
                            continue
                        }
                        delay(100)
                    }

                    // 读取音频数据
                    val framesRead = audioDevice.readAudioSuspend(buffer, frameSize)
                    // 更新最大帧数统计
                    if (framesRead > maxFramesEverRead) {
                        maxFramesEverRead = framesRead
                        logger.info("新的最大帧数记录: $maxFramesEverRead")
                    }

                    // 如果读取到数据
                    if (framesRead > 0) {
                        val totalSamples = framesRead * config.channels
                        val maxSamplesAllowed = bufferSize
                        val safeSamples =
                            if (totalSamples <= maxSamplesAllowed) totalSamples else maxSamplesAllowed
                        if (totalSamples > maxSamplesAllowed) {
                            logger.warn("样本数 $totalSamples 超过缓冲区限制 $maxSamplesAllowed，将被截断")
                        }

                        // 拷贝到 Kotlin ShortArray
                        val shortBuf = ShortArray(safeSamples)
                        for (i in 0 until safeSamples) {
                            shortBuf[i] = buffer[i]
                        }

                        // 转换为ByteArray并回调 (Little-Endian)
                        val bytesWritten =
                            AudioUtils.shortArrayToByteArray(shortBuf, safeSamples, byteBuffer)
                        onAudioDataReceived?.invoke(byteBuffer, bytesWritten)

                        // 更新统计
                        frameCounter++
                        totalBytesRead += bytesWritten.toLong()

                        // 定期记录统计信息
                        val currentTime = Clock.System.now().toEpochMilliseconds()
                        if (currentTime - lastLogTime > 5000) { // 每5秒记录一次
                            val duration = (currentTime - startTime) / 1000.0
                            val framesPerSecond = frameCounter / duration
                            val bytesPerSecond = totalBytesRead / duration
                            logger.info(
                                "采集统计: ${
                                    FormatUtil.formatDouble(
                                        framesPerSecond,
                                        1
                                    )
                                } 帧/秒, ${FormatUtil.formatDouble(bytesPerSecond, 1)} 字节/秒"
                            )
                            lastLogTime = currentTime
                        }

                        if (frameCounter % 50 == 0) {
                            logger.debug("readAudioSuspend返回 $framesRead 帧")
                        }
                    } else {
                        // 如果没有读取到数据，等待一小段时间
                        delay(10)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) {
                        logger.info("采集任务被取消")
                        break
                    }
                    logger.error("采集音频时发生异常: ${e.message}")
                    e.printStackTrace()
                    // 短暂延迟后继续
                    delay(100)
                }
            }

            logger.info("音频采集任务已结束")
        }
    }

    // 停止采集任务
    fun stopCapture() {
        if (!isCapturing) {
            logger.warn("音频采集未在运行")
            return
        }
        logger.info("正在停止音频采集...")
        isCapturing = false
        captureJob?.cancel() // Cancel the job
        captureScope?.cancel() // Cancel the scope
        captureJob = null
        captureScope = null

        // 调用底层设备的stop，以确保流状态正确
        audioDevice.stop()

        val duration = (Clock.System.now().toEpochMilliseconds() - startTime) / 1000.0
        if (duration > 0 && totalBytesRead > 0) {
            logger.info(
                "音频采集已停止. 总计: %.2f KB, 时长: %.2f 秒, 平均速率: %.2f KB/s".format(
                    totalBytesRead / 1024.0,
                    duration,
                    (totalBytesRead / 1024.0) / duration
                )
            )
        } else {
            logger.info("音频采集已停止. 未采集到有效数据或时长过短.")
        }
        _deviceState.value = AudioDevice.AudioDeviceState.READY
    }

    // --- 实现 AudioDevice 接口的其余方法，委托给 audioDevice --- 
    override suspend fun start(): Boolean {
        // PortAudioAcquisition的start主要用于启动采集循环，底层设备启动由initialize或采集循环内部管理
        // 如果需要一个通用的start方法，它应该触发startCapture
        logger.warn("PortAudioAcquisition.start() 被调用，但实际采集通过 startCapture(callback) 启动。若要开始采集，请使用 startCapture。")
        // 尝试启动底层设备，如果它尚未激活
        if (audioDevice.deviceState.value != AudioDevice.AudioDeviceState.ACTIVE) {
            val success = audioDevice.start()
            if (success) _deviceState.value =
                AudioDevice.AudioDeviceState.ACTIVE // Reflect underlying state
            return success
        }
        return audioDevice.deviceState.value == AudioDevice.AudioDeviceState.ACTIVE
    }

    override fun stop() {
        logger.info("PortAudioAcquisition.stop() 被调用，将停止采集任务 (如果正在运行)")
        stopCapture() // 主要功能是停止采集循环
        // 底层设备的stop已经由stopCapture调用
    }

    override fun setSampleRate(sampleRate: Int): Boolean = audioDevice.setSampleRate(sampleRate)
    override fun getSampleRate(): Int = audioDevice.getSampleRate()
    override fun listAudioDevices(): Pair<Int, Int> = audioDevice.listAudioDevices()
    override suspend fun openInputStream(
        deviceIndex: Int,
        sampleRate: Int,
        channels: Int
    ): Boolean = audioDevice.openInputStream(deviceIndex, sampleRate, channels)

    override suspend fun openOutputStream(
        deviceIndex: Int,
        sampleRate: Int,
        channels: Int
    ): Boolean = audioDevice.openOutputStream(deviceIndex, sampleRate, channels)

    // 实现非suspend的readAudio，委托给audioDevice的非suspend版本
    override fun readAudio(buffer: CPointer<ShortVar>, frameCount: Int): Int =
        audioDevice.readAudio(buffer, frameCount)

    // 实现非suspend的writeAudio，委托给audioDevice的非suspend版本
    override fun writeAudio(buffer: CPointer<ShortVar>, frameCount: Int): Int =
        audioDevice.writeAudio(buffer, frameCount)

    override suspend fun closeStreams(): Unit = audioDevice.closeStreams()
    override fun play(audioData: ByteArray, length: Int): Boolean =
        audioDevice.play(audioData, length)

    override fun playAsync(audioData: ByteArray, length: Int, onComplete: () -> Unit): Boolean =
        audioDevice.playAsync(audioData, length, onComplete)

    override fun pause(): Unit = audioDevice.pause()
    override fun resume(): Unit = audioDevice.resume()
    override fun isPlaying(): Boolean = audioDevice.isPlaying()
    override fun stopPlayback(): Unit = audioDevice.stopPlayback()
    override fun getDeviceInfo(): String = audioDevice.getDeviceInfo()

    override fun release() {
        logger.info("释放 PortAudioAcquisition 资源...")
        stopCapture()
        try {
            // 确保安全释放 buffer
            try {
                nativeHeap.free(buffer.rawValue)
                logger.info("已释放音频采集缓冲区")
            } catch (e: Exception) {
                logger.error("释放缓冲区时发生异常: ${e.message}")
            }
        } catch (e: Exception) {
            logger.error("释放资源时发生异常: ${e.message}")
        } finally {
            // 重置所有状态
            maxFramesEverRead = 0
            frameCounter = 0
            totalBytesRead = 0L
            isCapturing = false
            captureScope = null
            captureJob = null
            _deviceState.value = AudioDevice.AudioDeviceState.IDLE
            logger.info("PortAudioAcquisition 资源已释放")
        }
    }
} 