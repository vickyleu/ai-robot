@file:OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)

package voice.acquisition.portaudio

import com.airobot.core.utils.FormatUtil
import com.airobot.core.utils.format
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.free
import kotlinx.cinterop.set
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import voice.util.AudioUtils
import voice.util.LogManager
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.experimental.and
import kotlin.time.ExperimentalTime

/**
 * PortAudio音频采集实现
 * 负责从真实音频设备采集原始PCM数据
 */
@OptIn(ExperimentalTime::class)
class PortAudioAcquisition(
    internal val config: AudioConfig = AudioConfig(
        channels = 2 // Default to 2 channels as per Microsemi DAC requirements
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
    // 使用 frameSize * channels * 安全系数(4)，确保足够大
    private val bufferSize = frameSize * 2 * 4 // 2048 shorts
    private val buffer = nativeHeap.allocArray<ShortVar>(bufferSize)
    // 添加日志记录每次实际读取的帧数
    private var maxFramesEverRead = 0

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
        val sampleRate: Int = 16000,    // 采样率
        val channels: Int = 2,          // 通道数, Defaulting to 2 for Microsemi
        val bitsPerSample: Int = 16     // 每样本位数
    )

    override fun initialize(deviceName: String, sampleRate: Int): Boolean {
        _deviceState.value = AudioDevice.AudioDeviceState.INITIALIZING
        val success = audioDevice.initialize(deviceName, sampleRate)
        _deviceState.value = if (success) AudioDevice.AudioDeviceState.READY else AudioDevice.AudioDeviceState.IDLE
        return success
    }

    // 启动音频采集任务，并通过回调传递数据
    fun startCapture(onData: (ByteArray, Int) -> Unit) {
        logger.info("⭐⭐⭐ startCapture 被调用，开始采集音频 ⭐⭐⭐")
        
        if (isCapturing) {
            logger.warn("音频采集已经在运行中")
            return
        }
        if (_deviceState.value != AudioDevice.AudioDeviceState.READY && _deviceState.value != AudioDevice.AudioDeviceState.ACTIVE) {
             logger.info("采集设备未就绪: ${_deviceState.value}, 尝试启动底层设备")
             if (!audioDevice.start()) {
                 logger.error("底层音频设备启动失败，无法开始采集")
                 return
             }
        }
        
        this.onAudioDataReceived = onData
        captureScope = CoroutineScope(Dispatchers.Default) // Create a new scope each time

        captureJob = captureScope?.launch {
            isCapturing = true
            _deviceState.value = AudioDevice.AudioDeviceState.ACTIVE
            startTime = Clock.System.now().toEpochMilliseconds()
            frameCounter = 0
            totalBytesRead = 0L
            lastLogTime = startTime

            logger.info("音频采集中... Channels: ${config.channels}, SampleRate: ${config.sampleRate}, 缓冲区大小: $bufferSize")

            try {
                while (isActive && isCapturing) { // Use isActive to respect coroutine cancellation
                    // 确保输入流已打开，否则尝试打开
                    if (PortAudioDevice.getInstance().deviceState.value != AudioDevice.AudioDeviceState.ACTIVE || 
                       !PortAudioDevice.isInputStreamActive()) {
                         logger.info("输入流未打开，尝试打开输入流...")
                         if (!PortAudioDevice.getInstance().openInputStream(-1, config.sampleRate, config.channels)) {
                            logger.error("采集循环：无法打开输入流，延迟后重试")
                            delay(1000) // Wait before retrying
                            continue
                         }
                         logger.info("输入流已成功打开")
                    }
                    
                    try {
                        // 读取音频数据
                        logger.info("⭐⭐⭐ 准备调用readAudioSuspend读取音频数据")
                        val framesRead = audioDevice.readAudioSuspend(buffer, frameSize)
                        logger.info("⭐⭐⭐ readAudioSuspend返回：读取了 $framesRead 帧")
                        
                        // 更新最大帧数统计
                        if (framesRead > maxFramesEverRead) {
                            maxFramesEverRead = framesRead
                            logger.info("新的最大帧数记录: $maxFramesEverRead")
                        }
                        
                        // 如果读取到数据
                        if (framesRead > 0) {
                            // 安全检查 - 确保framesRead不超过缓冲区大小的一半
                            val safeFramesRead = if (framesRead <= bufferSize/2) framesRead else bufferSize/2
                            if (framesRead > bufferSize/2) {
                                logger.warn("⚠️ 帧数 $framesRead 超过安全限制 ${bufferSize/2}，将被截断")
                            }
                            
                            // 将short数组转换为byte数组
                            logger.info("⭐⭐⭐ 准备处理和回调音频数据，帧数: $safeFramesRead")
                            val byteData = ByteArray(safeFramesRead * 2) // 每个short占用2个byte
                            
                            for (i in 0 until safeFramesRead) {
                                val shortVal = buffer[i]
                                // Little endian
                                byteData[i * 2] = (shortVal and 0xFF).toByte()
                                byteData[i * 2 + 1] = (shortVal.toInt() shr 8).toByte()
                            }
                            
                            // 更新统计
                            frameCounter++
                            totalBytesRead += byteData.size.toLong()
                            
                            // 每5秒或1000帧记录一次统计
                            val currentTime = Clock.System.now().toEpochMilliseconds()
                            if (frameCounter % 1000 == 0 || currentTime - lastLogTime > 5000) {
                                val elapsedSeconds = (currentTime - startTime) / 1000.0
                                logger.info("采集中: ${frameCounter}帧, ${FormatUtil.formatDouble(totalBytesRead / 1024.0).format(2)}KB, " +
                                           "${FormatUtil.formatDouble(elapsedSeconds).format(1)}秒, " +
                                           "${FormatUtil.formatDouble(totalBytesRead / 1024.0 / elapsedSeconds).format(2)}KB/s")
                                lastLogTime = currentTime
                            }
                            
                            // 回调通知
                            logger.info("⭐⭐⭐ 准备执行onAudioDataReceived回调")
                            onAudioDataReceived?.invoke(byteData, byteData.size)
                            logger.info("⭐⭐⭐ onAudioDataReceived回调完成")
                        } else if (framesRead < 0) {
                            // 读取错误
                            logger.error("音频读取错误: $framesRead")
                            delay(100) // 短延迟避免过度记录错误
                        } else {
                            // 没有读取到数据
                            delay(10) // 短延迟避免CPU满载
                        }
                    } catch (e: Exception) {
                        // 捕获异常但不中断循环
                        logger.error("采集循环出现异常: ${e.message}, 继续尝试...")
                        if (e is CancellationException) throw e // 重新抛出取消异常
                        delay(500) // 较长延迟，给系统恢复时间
                    }
                    
                    // 周期性短延迟，防止过度消耗CPU
                    delay(5)
                }
            } catch (e: CancellationException) {
                logger.info("采集任务被取消")
                throw e // 重新抛出以正确完成协程
            } catch (e: Exception) {
                logger.error("采集任务异常结束: ${e.message}")
                e.printStackTrace()
            } finally {
                logger.info("采集任务结束，更新状态")
                isCapturing = false
                if (_deviceState.value == AudioDevice.AudioDeviceState.ACTIVE) {
                    _deviceState.value = AudioDevice.AudioDeviceState.READY
                }
            }
        }
        logger.info("音频采集任务已启动")
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
             logger.info("音频采集已停止. 总计: %.2f KB, 时长: %.2f 秒, 平均速率: %.2f KB/s".format(
                totalBytesRead / 1024.0, 
                duration, 
                (totalBytesRead / 1024.0) / duration
            ))
        } else {
            logger.info("音频采集已停止. 未采集到有效数据或时长过短.")
        }
         _deviceState.value = AudioDevice.AudioDeviceState.READY
    }

    // --- 实现 AudioDevice 接口的其余方法，委托给 audioDevice --- 
    override fun start(): Boolean {
        // PortAudioAcquisition的start主要用于启动采集循环，底层设备启动由initialize或采集循环内部管理
        // 如果需要一个通用的start方法，它应该触发startCapture
        logger.warn("PortAudioAcquisition.start() 被调用，但实际采集通过 startCapture(callback) 启动。若要开始采集，请使用 startCapture。")
        // 尝试启动底层设备，如果它尚未激活
        if (audioDevice.deviceState.value != AudioDevice.AudioDeviceState.ACTIVE) {
            val success = audioDevice.start()
            if (success) _deviceState.value = AudioDevice.AudioDeviceState.ACTIVE // Reflect underlying state
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
    override suspend fun openInputStream(deviceIndex: Int, sampleRate: Int, channels: Int): Boolean = audioDevice.openInputStream(deviceIndex, sampleRate, channels)
    override suspend fun openOutputStream(deviceIndex: Int, sampleRate: Int, channels: Int): Boolean = audioDevice.openOutputStream(deviceIndex, sampleRate, channels)
    
    // 实现非suspend的readAudio，委托给audioDevice的非suspend版本
    override fun readAudio(buffer: CPointer<ShortVar>, frameCount: Int): Int = audioDevice.readAudio(buffer, frameCount)
    
    // 实现非suspend的writeAudio，委托给audioDevice的非suspend版本
    override fun writeAudio(buffer: CPointer<ShortVar>, frameCount: Int): Int = audioDevice.writeAudio(buffer, frameCount)
    
    override suspend fun closeStreams(): Unit = audioDevice.closeStreams()
    override fun play(audioData: ByteArray, length: Int): Boolean = audioDevice.play(audioData, length)
    override fun playAsync(audioData: ByteArray, length: Int, onComplete: () -> Unit): Boolean = audioDevice.playAsync(audioData, length, onComplete)
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
                nativeHeap.free(buffer)
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