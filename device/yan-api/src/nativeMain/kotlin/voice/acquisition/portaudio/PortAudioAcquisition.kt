package voice.audio.acquisition

import com.airobot.portaudiointerop.Pa_CloseStream
import com.airobot.portaudiointerop.Pa_GetDefaultInputDevice
import com.airobot.portaudiointerop.Pa_GetDeviceInfo
import com.airobot.portaudiointerop.Pa_GetErrorText
import com.airobot.portaudiointerop.Pa_Initialize
import com.airobot.portaudiointerop.Pa_OpenDefaultStream
import com.airobot.portaudiointerop.Pa_OpenStream
import com.airobot.portaudiointerop.Pa_ReadStream
import com.airobot.portaudiointerop.Pa_StartStream
import com.airobot.portaudiointerop.Pa_StopStream
import com.airobot.portaudiointerop.Pa_Terminate
import com.airobot.portaudiointerop.paFramesPerBufferUnspecified
import com.airobot.portaudiointerop.paInt16
import com.airobot.portaudiointerop.paNoDevice
import com.airobot.portaudiointerop.paNoError
import kotlinx.cinterop.COpaquePointerVar
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
import kotlinx.coroutines.launch
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
    private val config: AudioPipeline.Acquisition.Config = AudioPipeline.Acquisition.Config()
) : AudioPipeline.Acquisition {

    private val logger = LogManager.getLogger("PortAudioAcquisition")
    private var stream: COpaquePointerVar? = null
    private var isCapturing = AtomicInt(0)
    private var captureScope: CoroutineScope? = null
    private var captureJob: Job? = null

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
        
        logger.info("PortAudio初始化成功，采样率: ${config.sampleRate}, 通道数: ${config.channels}")
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
    private fun captureLoop(callback: (ByteArray, Int) -> Unit) {
        logger.info("【重要】音频采集循环开始******************************************")
        println("【重要】音频采集循环开始 - 直接打印到标准输出")
        
        // 打开默认输入流
        val streamPointer = nativeHeap.alloc<COpaquePointerVar>()
        /*Pa_OpenStream(
            stream = streamPointer.ptr,
            inputParameters = null,                     // 输入流
            outputParameters = null,                     // 输出流
            sampleRate = config.sampleRate.toDouble(),  // 采样率
            framesPerBuffer = null,
            streamFlags = null,                     // 无回调函数
            streamCallback = null,
            userData = null// 无用户数据
        )*/
        val err = Pa_OpenDefaultStream(
            streamPointer.ptr,
            config.channels,          // 输入通道数
            0,                        // 无输出通道
            paInt16,                  // 16位PCM格式
            config.sampleRate.toDouble(),  // 采样率
            paFramesPerBufferUnspecified.toUInt(),  // 让PortAudio决定缓冲区大小
            null,                     // 无回调函数
            null                      // 无用户数据
        )
        
        if (err != paNoError) {
            logger.error("打开音频流失败: ${Pa_GetErrorText(err)?.toKString() ?: "未知错误"}")
            println("【重要错误】打开音频流失败: ${Pa_GetErrorText(err)?.toKString() ?: "未知错误"}")
            nativeHeap.free(streamPointer)
            return
        }
        
        stream = streamPointer
        val streamPtr = streamPointer.value
        
        // 开始流
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
        val framesPerBuffer = 1024                          // 每次读取1024帧
        val bytesPerSample = 2                              // 16位 = 2字节
        val bufferSizeInBytes = framesPerBuffer * config.channels * bytesPerSample
        val buffer = ByteArray(bufferSizeInBytes)
        
        // 创建临时缓冲区用于读取音频
        val tempBuffer = nativeHeap.allocArray<ShortVar>(framesPerBuffer * config.channels)
        
        var debugCounter = 0
        
        // 主采集循环
        while (isCapturing.value != 0) {
            // 从音频流读取数据
            val readErr = Pa_ReadStream(streamPtr, tempBuffer, framesPerBuffer.toUInt())
            
            // 每次读取都记录日志，无论成功与否
            val errorMsg = if (readErr != paNoError) Pa_GetErrorText(readErr)?.toKString() ?: "未知错误" else "成功"
            logger.info("【调试】读取音频: 状态=${errorMsg}, 帧数=${framesPerBuffer}")
            
            if (debugCounter++ == 0) {
                // 只在第一次循环时打印，确保至少看到这条消息
                println("【重要】第一次读取音频结果: 状态=${errorMsg} - 直接打印到标准输出")
            }
            
            if (readErr == paNoError) {
                // 计算原始音频能量以确认麦克风是否接收到声音
                var rawSum = 0.0
                for (i in 0 until framesPerBuffer * config.channels) {
                    val sample = tempBuffer[i].toInt()
                    rawSum += sample * sample
                }
                val rawEnergy = sqrt(rawSum / (framesPerBuffer * config.channels))
                logger.info("【调试】原始麦克风能量: $rawEnergy")
                
                if (debugCounter <= 5) {
                    // 前5帧直接打印到标准输出
                    println("【重要】麦克风原始能量(帧${debugCounter}): $rawEnergy - 直接打印到标准输出")
                }
                
                // 将临时缓冲区中的数据复制到字节数组中以供处理
                for (i in 0 until framesPerBuffer * config.channels) {
                    val sample = tempBuffer[i]
                    // 低字节
                    buffer[i * 2] = (sample.toInt() and 0xFF).toByte()
                    // 高字节
                    buffer[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
                }
                
                // 计算音频能量并记录
                if (debugCounter % 10 == 0) {  // 增加调试频率，从100改为10
                    var sum = 0.0
                    for (i in 0 until framesPerBuffer * config.channels) {
                        val sample = tempBuffer[i].toInt()
                        sum += sample * sample
                    }
                    val energy = sqrt(sum / (framesPerBuffer * config.channels))
                    logger.debug("麦克风音频能量: $energy")
                }
                
                // 处理音频数据
                callback(buffer, bufferSizeInBytes)
            } else {
                // 读取错误但不是致命错误，记录并继续
                logger.warn("读取音频流出现问题: ${Pa_GetErrorText(readErr)?.toKString() ?: "未知错误"}")
                println("【重要错误】读取音频流出现问题: ${Pa_GetErrorText(readErr)?.toKString() ?: "未知错误"} - 直接打印到标准输出")
            }
            
            // 短暂休眠，避免CPU占用过高
            usleep(5000u) // 5ms
        }
        
        // 停止并关闭流
        Pa_StopStream(streamPtr)
        Pa_CloseStream(streamPtr)
        
        // 释放资源
        nativeHeap.free(tempBuffer)
        nativeHeap.free(streamPointer)
        
        logger.info("音频采集循环结束")
    }
    
    // 生成简单的正弦波音频（440Hz A音）
    private fun generateSineWave(buffer: ByteArray, frameCount: Int, frequency: Double, sampleRate: Int) {
        val amplitude = 2000.0  // 振幅（小于32767以避免削波）
        
        for (i in 0 until frameCount) {
            val time = i.toDouble() / sampleRate
            val value = (amplitude * kotlin.math.sin(2 * kotlin.math.PI * frequency * time)).toInt()
            
            // 写入低字节
            buffer[i * 2] = (value % 256).toByte()
            // 写入高字节
            buffer[i * 2 + 1] = (value / 256).toByte()
        }
    }

    // 停止采集
    override fun stopCapture() {
        if (isCapturing.value == 0) {
            logger.warn("音频采集未在运行")
            return
        }
        
        logger.info("停止音频采集")
        isCapturing.value = 0
        
        // 取消协程
        captureJob?.cancel()
        captureScope?.cancel()
        captureJob = null
        captureScope = null
    }

    // 释放资源
    override fun release() {
        stopCapture()
        
        logger.info("释放PortAudio资源")
        Pa_Terminate()
    }
}