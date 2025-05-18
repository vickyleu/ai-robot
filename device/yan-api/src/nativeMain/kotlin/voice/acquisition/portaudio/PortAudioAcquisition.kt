package voice.acquisition.portaudio

import com.airobot.portaudiointerop.Pa_CloseStream
import com.airobot.portaudiointerop.Pa_GetDefaultInputDevice
import com.airobot.portaudiointerop.Pa_GetDeviceInfo
import com.airobot.portaudiointerop.Pa_GetErrorText
import com.airobot.portaudiointerop.Pa_Initialize
import com.airobot.portaudiointerop.Pa_OpenDefaultStream
import com.airobot.portaudiointerop.Pa_ReadStream
import com.airobot.portaudiointerop.Pa_StartStream
import com.airobot.portaudiointerop.Pa_StopStream
import com.airobot.portaudiointerop.Pa_Terminate
import com.airobot.portaudiointerop.paFramesPerBufferUnspecified
import com.airobot.portaudiointerop.paInt16
import com.airobot.portaudiointerop.paNoDevice
import com.airobot.portaudiointerop.paNoError
import com.airobot.portaudiointerop.paInputOverflowed
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
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.posix.system
import platform.posix.usleep
import voice.audio.AudioPipeline
import voice.util.LogManager
import kotlin.concurrent.AtomicInt
import kotlin.math.sqrt
import kotlin.time.ExperimentalTime

/**
 * PortAudio音频采集实现
 * 负责从真实音频设备采集原始PCM数据
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
class PortAudioAcquisition(
    // 修改构造函数，强制使用2通道（立体声）
    // 忽略传入的通道数，但保留其他配置参数
    internal val config: AudioPipeline.Acquisition.Config = AudioPipeline.Acquisition.Config(channels = 2)
) : AudioPipeline.Acquisition {
    private val logger = LogManager.getLogger("PortAudioAcquisition")
    
    // 确保始终使用立体声（2通道）
    private val actualChannels = 2
    
    // PortAudio状态
    private var stream: COpaquePointerVar? = null
    private var isCapturing = AtomicInt(0)
    private var captureScope: CoroutineScope? = null
    private var captureJob: Job? = null
    
    // 音频缓冲区
    private val frameSize = 1024
    private val buffer = nativeHeap.allocArray<ShortVar>(frameSize)
    
    // 采集协程
    private val scope = CoroutineScope(Dispatchers.Default)
    
    // 统计计数器
    private val frameCounter = AtomicInt(0)
    
    // PortAudio初始化
    override fun initialize(): Boolean {
        logger.info("初始化PortAudio音频采集")
        val result = Pa_Initialize()
        if (result != paNoError) {
            logger.error("Pa_Initialize 失败: ${Pa_GetErrorText(result)?.toKString() ?: "未知错误"}")
            return false
        }
        
        // 检查默认输入设备是否可用
        val defaultInputDevice = Pa_GetDefaultInputDevice()
        if (defaultInputDevice == paNoDevice) {
            logger.error("未找到默认音频输入设备")
            Pa_Terminate()
            return false
        }
        
        val deviceInfo = Pa_GetDeviceInfo(defaultInputDevice)?.pointed
        logger.info("使用输入设备: ${deviceInfo?.name?.toKString() ?: "未知"}, 最大输入通道: ${deviceInfo?.maxInputChannels ?: 0}")
        
        // 始终使用立体声（2通道），无论配置如何
        if (config.channels != 2) {
            logger.warn("忽略配置的通道数(${config.channels})，Microsemi DAC需要立体声模式，强制使用2通道")
        }
        
        logger.info("PortAudio初始化成功，采样率: ${config.sampleRate}, 通道数: $actualChannels (立体声)")
        return true
    }
    
    // 开始采集
    override fun startCapture(callback: (ByteArray, Int) -> Unit) {
        if (isCapturing.value != 0) {
            logger.warn("音频采集已经在运行")
            return
        }
        
        logger.info("开始音频采集")
        isCapturing.value = 1
        
        // 使用协程代替Worker
        captureScope = CoroutineScope(Dispatchers.Default)
        captureJob = captureScope?.launch {
            captureLoop(callback)
        }
    }
    
    // 采集循环 - 使用真实的PortAudio API
    private suspend fun captureLoop(callback: (ByteArray, Int) -> Unit) {
        // 分配COM指针用于PortAudio流
        val streamPointer = nativeHeap.alloc<COpaquePointerVar>()
        
        // 尝试使用多种方式打开音频设备
        var openSuccess = false
        
        // 0. 尝试直接使用ALSA命令行测试设备可用性
        logger.info("首先尝试使用ALSA直接测试设备可用性...")
        try {
            // 使用arecord测试设备
            val testCmd = "arecord -d 1 -f S16_LE -r 16000 -c 2 -D hw:0,0 /dev/null 2>/tmp/arecord_test.log"
            val testResult = system(testCmd)
            if (testResult == 0) {
                logger.info("ALSA设备测试成功，设备可直接访问")
            } else {
                logger.warn("ALSA设备测试失败，可能需要修复权限或配置")
                
                // 尝试修复权限
                system("sudo chmod -R 666 /dev/snd/* 2>/dev/null || true")
                
                // 再次测试
                val retestResult = system(testCmd)
                if (retestResult == 0) {
                    logger.info("修复权限后设备测试成功")
                } else {
                    logger.warn("修复权限后设备测试仍然失败，请查看/tmp/arecord_test.log")
                }
            }
        } catch (e: Exception) {
            logger.warn("ALSA设备测试异常: ${e.message}")
        }
        
        // 1. 尝试使用非常明确的参数直接打开设备
        logger.info("尝试打开默认音频流（使用明确参数）...")
        val openErr = Pa_OpenDefaultStream(
            streamPointer.ptr,
            actualChannels,  // 强制使用2通道（立体声）
            0,  // 无输出通道
            paInt16,  // 16位PCM格式
            config.sampleRate.toDouble(),  // 采样率
            256u,  // 使用较小的缓冲区，避免使用paFramesPerBufferUnspecified
            null,  // 无回调
            null  // 无用户数据
        )

        if (openErr == paNoError) {
            openSuccess = true
            logger.info("成功打开默认音频流")
        } else {
            val errorMsg = Pa_GetErrorText(openErr)?.toKString() ?: "未知错误"
            logger.error("打开默认音频流失败: $errorMsg，将尝试其他方法")
            
            // 2. 尝试通过明确的设备索引打开
            // 这里假设0是第一个设备
            logger.info("尝试直接使用硬件设备...")
            
            try {
                // 先尝试强制重置ALSA
                try {
                    logger.info("尝试重置ALSA设备...")
                    system("sudo alsactl -F restore 2>/dev/null || true")
                    system("sudo alsactl init 2>/dev/null || true")
                    delay(500) // 等待设备初始化
                } catch (e: Exception) {
                    logger.warn("ALSA重置失败: ${e.message}")
                }
                
                // 直接打开流
                Pa_CloseStream(streamPointer.value) // 关闭之前可能部分打开的流
                
                // 尝试使用较低采样率和不同的参数组合
                logger.info("尝试多种采样参数组合...")
                
                // 参数组合: 8kHz/单声道，低缓冲区
                var openErr2 = Pa_OpenDefaultStream(
                    streamPointer.ptr,
                    1,     // 试试单声道
                    0,
                    paInt16,
                    8000.0,  // 较低采样率
                    64u,   // 更小缓冲区
                    null,
                    null
                )
                
                if (openErr2 == paNoError) {
                    openSuccess = true
                    logger.info("成功打开音频流（单声道/8kHz备选方案）")
                } else {
                    // 参数组合: 低采样率/立体声
                    logger.info("尝试使用立体声/低采样率...")
                    openErr2 = Pa_OpenDefaultStream(
                        streamPointer.ptr,
                        2,
                        0,
                        paInt16,
                        8000.0,
                        128u,
                        null,
                        null
                    )
                    
                    if (openErr2 == paNoError) {
                        openSuccess = true
                        logger.info("成功打开音频流（立体声/8kHz备选方案）")
                    } else {
                        val errorMsg2 = Pa_GetErrorText(openErr2)?.toKString() ?: "未知错误"
                        logger.error("备选方案也失败: $errorMsg2")
                    }
                }
            } catch (e: Exception) {
                logger.error("尝试打开设备时发生异常: ${e.message}")
            }
        }

        // 如果无法打开设备，记录错误并返回
        if (!openSuccess) {
            logger.error("打开音频流失败: ${Pa_GetErrorText(openErr)?.toKString() ?: "未知错误"}")
            println("【重要错误】打开音频流失败: ${Pa_GetErrorText(openErr)?.toKString() ?: "未知错误"}")
            nativeHeap.free(streamPointer)
            return
        }
        
        val streamPtr = streamPointer.value
        stream = streamPointer
        
        // 启动流
        val startErr = Pa_StartStream(streamPtr)
        
        if (startErr != paNoError) {
            logger.error("启动音频流失败: ${Pa_GetErrorText(startErr)?.toKString() ?: "未知错误"}")
            println("【重要错误】启动音频流失败: ${Pa_GetErrorText(startErr)?.toKString() ?: "未知错误"}")
            Pa_CloseStream(streamPtr)
            nativeHeap.free(streamPointer)
            return
        }
        
        logger.info("【重要】音频流启动成功，开始读取数据******************************************")
        println("【重要】音频流启动成功，开始读取数据 - 直接打印到标准输出")
        
        // 分配缓冲区
        val framesPerBuffer = 256                          // 每次读取256帧，小一些避免延迟
        val bytesPerSample = 2                              // 16位 = 2字节
        val bufferSizeInBytes = framesPerBuffer * actualChannels * bytesPerSample // 确保用立体声(2通道)
        val buffer = ByteArray(bufferSizeInBytes)
        
        // 创建临时缓冲区用于读取音频
        val tempBuffer = nativeHeap.allocArray<ShortVar>(framesPerBuffer * actualChannels) // 立体声通道
        
        var debugCounter = 0
        
        // 主采集循环
        while (isCapturing.value > 0) {
            try {
                // 读取音频数据
                val readErr = Pa_ReadStream(streamPtr, tempBuffer, framesPerBuffer.toUInt())
                
                // 处理读取结果
                if (readErr == paNoError || readErr == paInputOverflowed) {
                    // 将ShortArray转换为ByteArray (little-endian)
                    var byteIndex = 0
                    for (i in 0 until framesPerBuffer * actualChannels) { // 乘以2因为是立体声
                        val sampleValue = tempBuffer[i]
                        buffer[byteIndex++] = (sampleValue.toInt() and 0xFF).toByte()
                        buffer[byteIndex++] = (sampleValue.toInt() shr 8).toByte()
                    }
                    
                    // 调用回调函数，传递音频数据
                    callback(buffer, bufferSizeInBytes)
                    
                    // 调试输出，降低频率
                    if (debugCounter++ % 100 == 0) {
                        val energy = calculateRMS(tempBuffer, framesPerBuffer * actualChannels)
                        logger.debug("音频帧 #$debugCounter: 能量=$energy")
                    }
                } else {
                    // 处理错误，但继续循环
                    val errorMsg = Pa_GetErrorText(readErr)?.toKString() ?: "未知错误"
                    logger.warn("读取音频数据失败: $errorMsg")
                    usleep(10000u) // 10ms 延迟避免过度消耗CPU
                }
            } catch (e: Exception) {
                // 捕获异常，防止崩溃
                logger.error("音频采集异常: ${e.message}")
                usleep(100000u) // 100ms 延迟
            }
        }
        
        // 清理资源
        logger.info("关闭音频流")
        Pa_StopStream(streamPtr)
        Pa_CloseStream(streamPtr)
        nativeHeap.free(streamPointer)
        nativeHeap.free(tempBuffer)
    }
    
    // 计算音频数据的均方根（RMS）能量
    private fun calculateRMS(buffer: CPointer<ShortVar>, size: Int): Double {
        var sum = 0.0
        for (i in 0 until size) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }
        return sqrt(sum / size)
    }
    
    override fun stopCapture() {
        if (isCapturing.value == 0) {
            return
        }
        
        isCapturing.value = 0
        captureJob?.cancel("Stop requested")
        captureJob = null
        
        logger.info("已停止音频采集, 共采集 ${frameCounter.value} 帧")
    }
    
    override fun release() {
        stopCapture()
        
        if (stream != null) {
            try {
                Pa_StopStream(stream?.value)
                Pa_CloseStream(stream?.value)
                Pa_Terminate()
            } catch (e: Exception) {
                logger.warn("释放PortAudio资源时出错: ${e.message}")
            }
            
            stream = null
        }
        
        // 释放本地资源
        nativeHeap.free(buffer)
        
        // 取消协程作用域
        captureScope?.cancel("Release requested")
        
        logger.info("PortAudio资源已释放")
    }
} 