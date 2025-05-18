package voice.acquisition.portaudio

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
import com.airobot.portaudiointerop.PaStreamParameters
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
import kotlinx.cinterop.refTo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.posix.system
import platform.posix.usleep
import platform.posix.F_OK
import platform.posix.access
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.fclose
import platform.posix.getenv
import voice.audio.AudioPipeline
import voice.hal.LinuxAudioDeviceSelector
import voice.util.AudioUtils
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
    
    // Linux设备选择器
    private val deviceSelector = LinuxAudioDeviceSelector()
    
    // PortAudio初始化
    override fun initialize(): Boolean {
        logger.info("初始化PortAudio音频采集")
        println("初始化PortAudio音频采集 - 直接打印到标准输出")
        
        // 检查是否已有全局音频流, 如果有则不重复初始化
        if (PortAudioDevice.isGlobalStreamActive()) {
            logger.info("检测到全局音频流已激活，使用现有全局流")
            println("检测到全局音频流已激活，使用现有全局流")
            return true
        }
        
        // 只在需要时关闭现有流
        if (stream != null && stream?.value != null) {
            try {
                Pa_StopStream(stream?.value)
                Pa_CloseStream(stream?.value)
                stream?.value = null
                logger.info("已关闭现有流")
            } catch (e: Exception) {
                logger.warn("关闭现有流失败: ${e.message}")
            }
        }
        
        // 避免重复终止PortAudio
        try {
            Pa_Terminate()
            logger.info("已终止现有PortAudio实例")
            kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(300) }
        } catch (e: Exception) {
            logger.warn("终止PortAudio失败: ${e.message}")
        }
        
        // 使用设备选择器强制释放音频资源，但保持简单操作
        logger.info("强制释放音频资源...")
        system("sudo pkill -9 pulseaudio arecord aplay 2>/dev/null || true")
        system("sudo fuser -k /dev/snd/* 2>/dev/null || true")
        system("sudo chmod -R 777 /dev/snd/* 2>/dev/null || true")
        
        // 创建最简化ALSA配置
        logger.info("创建和应用最优ALSA配置...")
        val homeDir = getenv("HOME")?.toKString() ?: "/home/pi"
        val asoundrcPath = "$homeDir/.asoundrc"
        
        // 清理旧配置
        system("rm -f $asoundrcPath")
        
        // 直接创建最简化配置
        val file = fopen(asoundrcPath, "w")
        if (file != null) {
            fputs("pcm.!default { type hw card 0 device 0 }\n", file)
            fputs("ctl.!default { type hw card 0 }\n", file)
            fclose(file)
            logger.info("已创建简化ALSA配置文件")
        }
        
        // 直接测试设备是否可用，简短测试
        logger.info("测试音频设备直接可用性...")
        val testCmd = "arecord -d 1 -f S16_LE -r 16000 -c 2 -D hw:0,0 /dev/null 2>/tmp/arecord_acquisition_test.log"
        val testResult = system(testCmd)
        if (testResult == 0) {
            logger.info("ALSA设备直接测试成功")
        }
        
        // 简单初始化PortAudio
        logger.info("正在初始化PortAudio...")
        val result = Pa_Initialize()
        if (result != paNoError) {
            val errorMsg = Pa_GetErrorText(result)?.toKString() ?: "未知错误"
            logger.error("Pa_Initialize 失败: $errorMsg")
            return false
        }
        
        logger.info("Pa_Initialize 成功")
        
        // 简单检查默认输入设备
        val defaultInputDevice = Pa_GetDefaultInputDevice()
        if (defaultInputDevice == paNoDevice) {
            logger.error("未找到默认音频输入设备")
            Pa_Terminate()
            return false
        }
        
        val deviceInfo = Pa_GetDeviceInfo(defaultInputDevice)?.pointed
        logger.info("使用输入设备: ${deviceInfo?.name?.toKString() ?: "default"}, 最大输入通道: ${deviceInfo?.maxInputChannels ?: 0}")
        
        // 确保使用立体声模式
        logger.info("PortAudio初始化步骤完成，采样率: ${config.sampleRate}, 通道数: $actualChannels (立体声)")
        return true
    }
    
    // 开始采集
    override fun startCapture(callback: (ByteArray, Int) -> Unit) {
        if (isCapturing.value != 0) {
            logger.warn("音频采集已经在运行")
            println("音频采集已经在运行")
            return
        }
        
        // 检查主音频设备是否已激活
        if (PortAudioDevice.isGlobalStreamActive()) {
            logger.info("使用已存在的全局音频流，不再创建新流")
            println("使用已存在的全局音频流，不再创建新流")
            
            // 直接设置采集状态为活跃，但不启动新的捕获循环
            isCapturing.value = 1
            
            // 伪数据回调 - 创建一个协程提供虚拟音频数据
            captureScope = CoroutineScope(Dispatchers.Default)
            captureJob = captureScope?.launch {
                // 伪数据回调 - 等待直到停止
                while (isCapturing.value > 0) {
                    delay(500)
                }
            }
            return
        }
        
        logger.info("开始音频采集")
        println("开始音频采集")
        isCapturing.value = 1
        
        // 使用协程代替Worker
        captureScope = CoroutineScope(Dispatchers.Default)
        captureJob = captureScope?.launch {
            captureLoop(callback)
        }
    }
    
    // 音频采集循环 - 简化实现
    private suspend fun captureLoop(callback: (ByteArray, Int) -> Unit) {
        // 检查是否已有全局音频流
        if (PortAudioDevice.isGlobalStreamActive()) {
            logger.info("检测到全局音频流已存在，不再打开新流")
            println("检测到全局音频流已存在，不再打开新流")
            
            // 长期等待，不做实际操作
            while (isCapturing.value > 0) {
                delay(1000)
            }
            return
        }
        
        // 先确保已释放之前的资源
        if (stream != null && stream?.value != null) {
            try {
                Pa_StopStream(stream?.value)
                Pa_CloseStream(stream?.value)
                stream?.value = null
                logger.info("已关闭之前的流")
            } catch (e: Exception) {
                logger.warn("关闭之前的流失败: ${e.message}")
            }
        }
        
        // 分配COM指针用于PortAudio流
        val streamPointer = nativeHeap.alloc<COpaquePointerVar>()
        
        // 使用最可靠的参数组合，减少尝试次数
        val paramCombinations = listOf(
            Triple(2, 16000, 256),  // 立体声, 16kHz, 256帧
            Triple(2, 16000, 512),  // 立体声, 16kHz, 512帧
            Triple(2, 8000, 256),   // 立体声, 8kHz, 256帧 
            Triple(2, 8000, 512)    // 立体声, 8kHz, 512帧
        )
        
        logger.info("将尝试 ${paramCombinations.size} 种参数组合")
        
        // 尝试打开流
        var openSuccess = false
        var lastError: String? = null
        var selectedRate = 16000
        var selectedBufferSize = 256
        
        // 使用传统for循环并简化尝试过程
        for (index in paramCombinations.indices) {
            if (openSuccess) break
            
            val (ch, rate, buffer) = paramCombinations[index]
            
            if (index > 0) {
                delay(300) // 短暂等待
            }
            
            logger.info("尝试组合 #${index+1}: ${ch}通道, ${rate}Hz, ${buffer}帧")
            
            // 创建输入参数结构体
            val inputParams = nativeHeap.alloc<PaStreamParameters>()
            var needFreeParams = true // 跟踪是否需要释放参数
            
            try {
                inputParams.device = Pa_GetDefaultInputDevice()  // 默认输入设备
                inputParams.channelCount = ch                    // 通道数
                inputParams.sampleFormat = paInt16               // 16位PCM格式
                inputParams.suggestedLatency = 0.05              // 建议的延迟时间
                inputParams.hostApiSpecificStreamInfo = null     // 无特定主机API信息
                
                // 使用Pa_OpenStream代替Pa_OpenDefaultStream
                val openErr = Pa_OpenStream(
                    streamPointer.ptr,           // 流指针
                    inputParams.ptr,             // 输入参数
                    null,                        // 无输出参数
                    rate.toDouble(),             // 采样率
                    buffer.toUInt(),             // 缓冲区大小
                    0u,                          // 无特殊标志
                    null,                        // 无回调
                    null                         // 无用户数据
                )
                
                if (openErr == paNoError) {
                    // 尝试启动流
                    val startErr = Pa_StartStream(streamPointer.value)
                    if (startErr == paNoError) {
                        logger.info("成功打开并启动流（组合 #${index+1}）")
                        openSuccess = true
                        selectedRate = rate
                        selectedBufferSize = buffer
                        // 跳出循环
                        break
                    } else {
                        lastError = Pa_GetErrorText(startErr)?.toKString() ?: "未知错误"
                        logger.error("启动流失败（组合 #${index+1}）: $lastError")
                        
                        // 关闭流后尝试下一组参数
                        try {
                            Pa_CloseStream(streamPointer.value)
                            streamPointer.value = null
                        } catch (e: Exception) {
                            logger.warn("关闭流失败: ${e.message}")
                        }
                    }
                } else {
                    lastError = Pa_GetErrorText(openErr)?.toKString() ?: "未知错误"
                    logger.error("打开流失败（组合 #${index+1}）: $lastError")
                }
            } finally {
                // 释放本地资源
                if (needFreeParams) {
                    nativeHeap.free(inputParams.rawPtr)
                    needFreeParams = false
                }
            }
        }

        // 如果所有组合都失败，返回并稍后重试
        if (!openSuccess) {
            logger.error("所有打开音频流的尝试均失败: $lastError")
            nativeHeap.free(streamPointer)
            
            // 延迟一段时间后尝试重启采集
            delay(5000)
            if (isCapturing.value > 0) {
                logger.info("尝试重启采集...")
                captureLoop(callback)
            }
            return
        }
        
        val streamPtr = streamPointer.value
        stream = streamPointer
        
        logger.info("音频流启动成功，开始读取数据")
        
        // 分配缓冲区 - 使用成功参数的设置
        val framesPerBuffer = selectedBufferSize
        val bytesPerSample = 2                // 16位 = 2字节
        val bufferSizeInBytes = framesPerBuffer * actualChannels * bytesPerSample
        val buffer = ByteArray(bufferSizeInBytes)
        
        // 创建临时缓冲区
        val tempBuffer = nativeHeap.allocArray<ShortVar>(framesPerBuffer * actualChannels)
        
        var debugCounter = 0
        var errorCounter = 0
        
        // 主采集循环
        while (isCapturing.value > 0) {
            try {
                // 读取音频数据
                val readErr = Pa_ReadStream(streamPtr, tempBuffer, framesPerBuffer.toUInt())
                
                // 处理读取结果
                if (readErr == paNoError || readErr == paInputOverflowed) {
                    // 重置错误计数
                    errorCounter = 0
                    
                    // 将ShortArray转换为ByteArray，使用AudioUtils工具类
                    // 创建ShortArray来存储中间数据
                    val shortArray = ShortArray(framesPerBuffer * actualChannels)
                    
                    // 复制数据到shortArray
                    for (i in 0 until framesPerBuffer * actualChannels) {
                        shortArray[i] = tempBuffer[i]
                    }
                    
                    // 使用AudioUtils转换为ByteArray
                    val convertedBuffer = AudioUtils.shortArrayToByteArray(shortArray)
                    
                    // 确保长度正确
                    if (convertedBuffer.size == bufferSizeInBytes) {
                        // 调用回调函数，传递音频数据
                        callback(convertedBuffer, bufferSizeInBytes)
                    } else {
                        // 如果大小不匹配，使用原始缓冲区
                        convertedBuffer.copyInto(buffer, 0,
                            minOf(convertedBuffer.size, bufferSizeInBytes))
                        callback(buffer, bufferSizeInBytes)
                    }
                    
                    // 调试输出，降低频率
                    if (debugCounter++ % 500 == 0) {
                        val energy = calculateRMS(tempBuffer, framesPerBuffer * actualChannels)
                        logger.debug("音频帧 #$debugCounter: 能量=$energy")
                    }
                } else {
                    // 处理错误，但继续循环，降低错误日志频率
                    if (errorCounter++ % 100 == 0) {
                        val errorMsg = Pa_GetErrorText(readErr)?.toKString() ?: "未知错误"
                        logger.warn("读取音频数据失败: $errorMsg (错误计数: $errorCounter)")
                    }
                    
                    // 只有在真正大量错误时才重新初始化
                    if (errorCounter > 2000) {
                        logger.error("连续错误过多，尝试重新初始化音频流")
                        
                        // 关闭现有流
                        try {
                            Pa_StopStream(streamPtr)
                            Pa_CloseStream(streamPtr)
                        } catch (e: Exception) {
                            logger.warn("关闭出错流时异常: ${e.message}")
                        }
                        
                        // 等待资源释放
                        delay(1000)
                        
                        // 重新进入采集循环
                        captureLoop(callback)
                        return
                    }
                    
                    // 短暂延迟避免CPU过载
                    usleep(5000u) // 5ms 延迟
                }
            } catch (e: Exception) {
                // 捕获异常，防止崩溃
                logger.error("音频采集异常: ${e.message}")
                
                // 记录异常次数
                errorCounter++
                
                // 如果异常过多，尝试重启
                if (errorCounter > 50) {
                    logger.error("异常过多，尝试重启采集")
                    
                    // 关闭现有流
                    try {
                        Pa_StopStream(streamPtr)
                        Pa_CloseStream(streamPtr)
                    } catch (e2: Exception) {
                        // 忽略异常
                    }
                    
                    // 等待资源释放
                    delay(1000)
                    
                    // 重新进入采集循环
                    captureLoop(callback)
                    return
                }
                
                usleep(100000u) // 100ms 延迟
            }
        }
        
        // 清理资源
        logger.info("关闭音频流")
        try {
            Pa_StopStream(streamPtr)
            Pa_CloseStream(streamPtr)
        } catch (e: Exception) {
            logger.warn("关闭流时出错: ${e.message}")
        }
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
        
        logger.info("正在停止音频采集...")
        println("正在停止音频采集...")
        
        isCapturing.value = 0
        captureJob?.cancel("Stop requested")
        captureJob = null
        
        // 主动关闭流
        if (stream != null && stream?.value != null) {
            try {
                Pa_StopStream(stream?.value)
                Pa_CloseStream(stream?.value)
                stream?.value = null
                logger.info("已停止并关闭音频流")
                println("已停止并关闭音频流")
            } catch (e: Exception) {
                logger.warn("停止音频流时出错: ${e.message}")
                println("停止音频流时出错: ${e.message}")
            }
        }
        
        logger.info("已停止音频采集, 共采集 ${frameCounter.value} 帧")
        println("已停止音频采集, 共采集 ${frameCounter.value} 帧")
    }
    
    override fun release() {
        logger.info("释放PortAudioAcquisition资源...")
        println("释放PortAudioAcquisition资源...")
        
        // 停止采集
        stopCapture()
        
        // 显式终止PortAudio
        try {
            Pa_Terminate()
            logger.info("已终止PortAudio")
            println("已终止PortAudio")
        } catch (e: Exception) {
            logger.warn("终止PortAudio时出错: ${e.message}")
            println("终止PortAudio时出错: ${e.message}")
        }
        
        // 释放本地资源
        nativeHeap.free(buffer)
        
        // 取消协程作用域
        captureScope?.cancel("Release requested")
        captureScope = null
        
        // 清理系统资源
        try {
            deviceSelector.killOtherAudioProcesses()
            logger.info("已清理系统音频资源")
            println("已清理系统音频资源")
        } catch (e: Exception) {
            logger.warn("清理系统资源时出错: ${e.message}")
            println("清理系统资源时出错: ${e.message}")
        }
        
        logger.info("PortAudio资源已完全释放")
        println("PortAudio资源已完全释放")
    }
} 