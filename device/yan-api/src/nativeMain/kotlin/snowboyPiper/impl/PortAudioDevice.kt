@file:OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)

package snowboyPiper.impl

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
import com.airobot.portaudiointerop.paFramesPerBufferUnspecified
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
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
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
import snowboyPiper.interfaces.AudioDevice
import kotlin.experimental.ExperimentalNativeApi
import kotlin.time.ExperimentalTime

/**
 * PortAudio音频设备实现
 * 负责音频设备的初始化、列举、打开和关闭等操作
 */
class PortAudioDevice(private val speechRecognizer: VoskSpeechRecognizer) : AudioDevice {
    // 音频流互斥锁，确保同一时间只有一个音频流在使用
    private val audioMutex = Mutex()

    // 输入流和输出流分开处理
    private var inputStreamPtr = nativeHeap.alloc<COpaquePointerVar>()
    private var outputStreamPtr = nativeHeap.alloc<COpaquePointerVar>()
    
    // 设备状态
    private val _deviceState = MutableStateFlow(AudioDevice.AudioDeviceState.IDLE)
    override val deviceState: StateFlow<AudioDevice.AudioDeviceState> = _deviceState.asStateFlow()
    
    // 存储设备信息
    private var selectedInputDeviceIndex = -1 // 将使用找到的第一个输入设备
    private var selectedOutputDeviceIndex = -1 // 将使用找到的第一个输出设备
    
    // 标记PortAudio初始化状态
    private var portAudioInitialized = false

    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Default)

    // 音频缓冲区，用于积累小帧避免欠载
    private val audioPlayBuffer = ShortArray(8192)
    private var audioPlayBufferPos = 0
    private val minPlayFrames = 666 // 降低最小播放帧数，确保数据更快被播放出来
    
    // Linux设备选择器
    private val deviceSelector = LinuxAudioDeviceSelector()

    // 音频读取计数器
    private var audioReadCounter = 0

    override fun isInitialized(): Boolean {
        return portAudioInitialized
    }

    /**
     * 初始化音频设备
     * @return 初始化是否成功
     */
    override suspend fun initialize(): Boolean {
        if (_deviceState.value == AudioDevice.AudioDeviceState.INITIALIZING || 
            _deviceState.value == AudioDevice.AudioDeviceState.ACTIVE) {
            println("[WARN] 音频设备已经初始化或正在初始化中")
            return true
        }
        
        _deviceState.value = AudioDevice.AudioDeviceState.INITIALIZING
        
        try {
            // 尝试修复ALSA配置
            if (deviceSelector.isRaspberryPi()) {
                println("[INFO] 检测到树莓派，尝试修复ALSA配置")
                deviceSelector.fixAlsaConfig()
            }
            
            // 初始化PortAudio
            val result = Pa_Initialize()
            if (result != paNoError) {
                println("[ERROR] 无法初始化PortAudio: ${Pa_GetErrorText(result)?.toKString()}")
                _deviceState.value = AudioDevice.AudioDeviceState.ERROR
                return false
            }
            
            println("[INFO] PortAudio初始化成功")
            
            // 打印设备信息
            val deviceCount = Pa_GetDeviceCount()
            println("[INFO] 发现 $deviceCount 个音频设备")
            
            for (i in 0 until deviceCount) {
                val info = Pa_GetDeviceInfo(i)?.pointed
                println("[INFO] 设备 $i: ${info?.name?.toKString() ?: "未知"}, 输入通道: ${info?.maxInputChannels}, 输出通道: ${info?.maxOutputChannels}, 默认采样率: ${info?.defaultSampleRate}")
            }
            
            // 注意：不再在初始化时打开流，由外部代码负责调用openInputStream和openOutputStream
            
            portAudioInitialized = true
            _deviceState.value = AudioDevice.AudioDeviceState.READY // 改为READY而不是ACTIVE，因为流未打开
            return true
        } catch (e: Exception) {
            println("[ERROR] 初始化音频设备时出错: ${e.message}")
            e.printStackTrace()
            _deviceState.value = AudioDevice.AudioDeviceState.ERROR
            return false
        }
    }
    
    /**
     * 列举音频设备
     * @return 输入设备索引和输出设备索引的对
     */
    override fun listAudioDevices(): Pair<Int, Int> {
        if (!portAudioInitialized) {
            println("[WARN] PortAudio未初始化，无法列举设备")
            return Pair(-1, -1)
        }
        
        try {
            val deviceCount = Pa_GetDeviceCount()
            if (deviceCount <= 0) {
                println("[WARN] 未找到音频设备")
                return Pair(-1, -1)
            }
            
            // 首先尝试使用Linux设备选择器
            if (deviceSelector.isRaspberryPi()) {
                println("[INFO] 使用Linux设备选择器查找最佳音频设备")
                val inputDeviceId = deviceSelector.getRecommendedRecordingDevice()
                val outputDeviceId = deviceSelector.getRecommendedPlaybackDevice()
                
                if (inputDeviceId >= 0 || outputDeviceId >= 0) {
                    println("[INFO] Linux设备选择器推荐输入设备: $inputDeviceId, 输出设备: $outputDeviceId")
                    selectedInputDeviceIndex = inputDeviceId
                    selectedOutputDeviceIndex = outputDeviceId
                    return Pair(inputDeviceId, outputDeviceId)
                }
            }
            
            // 如果Linux选择器失败，回退到PortAudio设备搜索
            var bestInputDeviceIndex = -1
            var bestOutputDeviceIndex = -1
            
            // 首先查找默认设备
            for (i in 0 until deviceCount) {
                val info = Pa_GetDeviceInfo(i)?.pointed ?: continue
                val hostInfo = Pa_GetHostApiInfo(info.hostApi)?.pointed
                
                // 检查是否为默认输入设备
                if (hostInfo != null && i == hostInfo.defaultInputDevice) {
                    bestInputDeviceIndex = i
                    println("[INFO] 找到默认输入设备: ${info.name?.toKString()}")
                }
                
                // 检查是否为默认输出设备
                if (hostInfo != null && i == hostInfo.defaultOutputDevice) {
                    bestOutputDeviceIndex = i
                    println("[INFO] 找到默认输出设备: ${info.name?.toKString()}")
                }
            }
            
            // 如果找不到默认设备，查找任何可用设备
            if (bestInputDeviceIndex == -1 || bestOutputDeviceIndex == -1) {
                for (i in 0 until deviceCount) {
                    val info = Pa_GetDeviceInfo(i)?.pointed ?: continue
                    
                    // 检查输入通道
                    if (bestInputDeviceIndex == -1 && info.maxInputChannels > 0) {
                        bestInputDeviceIndex = i
                        println("[INFO] 找到输入设备: ${info.name?.toKString()}")
                    }
                    
                    // 检查输出通道
                    if (bestOutputDeviceIndex == -1 && info.maxOutputChannels > 0) {
                        bestOutputDeviceIndex = i
                        println("[INFO] 找到输出设备: ${info.name?.toKString()}")
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
            println("[ERROR] 列举设备时出错: ${e.message}")
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
            println("[ERROR] PortAudio未初始化")
            return false
        }
        
        return try {
            audioMutex.withLock {
                println("[INFO] 尝试打开音频输入流...")
                
                // 关闭已存在的流
                if (inputStreamPtr.value != null) {
                    Pa_StopStream(inputStreamPtr.value)
                    Pa_CloseStream(inputStreamPtr.value)
                    inputStreamPtr.value = null
                }
                
                // 获取ALSA设备参数（如果是Linux系统）
                val actualDeviceIndex = deviceIndex.takeIf { it >= 0 } ?: selectedInputDeviceIndex
                var alsaDeviceParam = ""
                
                if (deviceSelector.isRaspberryPi()) {
                    alsaDeviceParam = deviceSelector.getALSADeviceString(actualDeviceIndex, true)
                    println("[INFO] 将使用ALSA输入设备: $alsaDeviceParam")
                } else {
                    println("[INFO] 将使用输入设备索引: $actualDeviceIndex")
                }
                
                // 尝试多次打开流，因为设备可能暂时被占用
                var success = false
                val maxRetries = 5
                var retryCount = 0
                
                while (!success && retryCount < maxRetries) {
                    retryCount++
                    
                    val result = if (actualDeviceIndex < 0) {
                        // 使用默认设备
                        Pa_OpenDefaultStream(
                            inputStreamPtr.ptr,
                            channels,  // 输入通道数
                            0,         // 输出通道数（不需要输出）
                            paInt16,   // 采样格式
                            sampleRate.toDouble(),
                            paFramesPerBufferUnspecified.toUInt(),
                            null,      // 回调函数（不使用回调）
                            null       // 用户数据
                        )
                    } else {
                        // 使用指定设备
                        // 注意：这里需要实现Pa_OpenStream，但原代码中没有使用，暂时使用默认流
                        Pa_OpenDefaultStream(
                            inputStreamPtr.ptr,
                            channels,  // 输入通道数
                            0,         // 输出通道数（不需要输出）
                            paInt16,   // 采样格式
                            sampleRate.toDouble(),
                            paFramesPerBufferUnspecified.toUInt(),
                            null,      // 回调函数（不使用回调）
                            null       // 用户数据
                        )
                    }
                    
                    if (result == paNoError) {
                        // 启动流
                        val startResult = Pa_StartStream(inputStreamPtr.value)
                        if (startResult == paNoError) {
                            println("[INFO] 音频输入流打开并启动成功")
                            success = true
                            _deviceState.value = AudioDevice.AudioDeviceState.ACTIVE
                        } else {
                            println("[ERROR] 无法启动音频输入流: ${Pa_GetErrorText(startResult)?.toKString()}")
                            Pa_CloseStream(inputStreamPtr.value)
                            inputStreamPtr.value = null
                        }
                    } else {
                        println("[WARN] 尝试 #$retryCount: 无法打开输入流: ${Pa_GetErrorText(result)?.toKString()}")
                        // 等待一段时间再重试
                        kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(1000) }
                    }
                }
                
                if (!success) {
                    println("[ERROR] 多次尝试后仍无法打开音频输入流")
                    _deviceState.value = AudioDevice.AudioDeviceState.ERROR
                }
                
                success
            }
        } catch (e: Exception) {
            println("[ERROR] 打开音频输入流时出错: ${e.message}")
            e.printStackTrace()
            _deviceState.value = AudioDevice.AudioDeviceState.ERROR
            false
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
            println("[ERROR] PortAudio未初始化")
            return false
        }
        
        return try {
            audioMutex.withLock {
                println("[INFO] 尝试打开音频输出流...")
                
                // 关闭已存在的流
                if (outputStreamPtr.value != null) {
                    Pa_StopStream(outputStreamPtr.value)
                    Pa_CloseStream(outputStreamPtr.value)
                    outputStreamPtr.value = null
                }
                
                // 获取实际设备索引或ALSA设备
                val actualDevice = deviceIndex.takeIf { it >= 0 } ?: selectedOutputDeviceIndex
                var alsaDeviceParam = ""
                
                if (deviceSelector.isRaspberryPi()) {
                    alsaDeviceParam = deviceSelector.getALSADeviceString(actualDevice, false)
                    println("[INFO] 将使用ALSA输出设备: $alsaDeviceParam")
                } else {
                    println("[INFO] 将使用输出设备: $actualDevice")
                }
                
                // 尝试打开输出流
                val result = if (actualDevice < 0) {
                    // 默认设备
                    Pa_OpenDefaultStream(
                        outputStreamPtr.ptr,
                        0,
                        channels,
                        paInt16,
                        sampleRate.toDouble(),
                        1024u, // 使用较小的缓冲区以减少延迟
                        null,
                        null
                    )
                } else {
                    // 使用指定设备
                    Pa_OpenDefaultStream(
                        outputStreamPtr.ptr,
                        0,
                        channels,
                        paInt16,
                        sampleRate.toDouble(),
                        1024u, // 使用较小的缓冲区以减少延迟
                        null,
                        null
                    )
                }
                
                if (result == paNoError) {
                    // 启动流
                    val startResult = Pa_StartStream(outputStreamPtr.value)
                    if (startResult == paNoError) {
                        println("[INFO] 音频输出流打开并启动成功")
                        _deviceState.value = AudioDevice.AudioDeviceState.ACTIVE
                        
                        true
                    } else {
                        println("[ERROR] 无法启动音频输出流: ${Pa_GetErrorText(startResult)?.toKString()}")
                        Pa_CloseStream(outputStreamPtr.value)
                        outputStreamPtr.value = null
                        false
                    }
                } else {
                    println("[ERROR] 无法打开音频输出流: ${Pa_GetErrorText(result)?.toKString()}")
                    false
                }
            }
        } catch (e: Exception) {
            println("[ERROR] 打开音频输出流时出错: ${e.message}")
            e.printStackTrace()
            _deviceState.value = AudioDevice.AudioDeviceState.ERROR
            false
        }
    }
    
    /**
     * 读取音频数据
     * @param buffer 数据缓冲区
     * @param frameCount 帧数
     * @return 读取的帧数，负值表示错误
     */
    override suspend fun readAudio(buffer: CPointer<ShortVar>, frameCount: Int): Int {
        if (inputStreamPtr.value == null) {
            println("[ERROR] 音频输入流未打开")
            return -1
        }
        
        return try {
            val startTime = kotlin.time.TimeSource.Monotonic.markNow()
            val result = Pa_ReadStream(inputStreamPtr.value, buffer, frameCount.toUInt())
            val elapsed = startTime.elapsedNow()
            
            if (result == paNoError || result == paInputOverflowed) {
                // 成功读取或输入溢出（可以接受）
                
                // 减少调试输出频率，从每100次改为每1000次
                if (audioReadCounter % 1000 == 0) {
                    // 简化输出，不打印样本值
                    println("[DEBUG-READ] 读取音频: 帧数=$frameCount, 耗时=${elapsed.inWholeMilliseconds}ms")
                    
                    // 计算RMS能量，但只在能量足够高时才输出
                    var sumSquares = 0.0
                    for (i in 0 until frameCount) {
                        val sample = buffer[i].toDouble()
                        sumSquares += (sample * sample)
                    }
                    val rms = kotlin.math.sqrt(sumSquares / frameCount)
                    
                    // 只有当能量足够高时才输出
                    if (rms > 20.0) {
                        println("[DEBUG-READ] 音频能量: RMS=$rms")
                    }
                }
                
                if (result == paInputOverflowed && audioReadCounter % 500 == 0) {
                    println("[WARN] 音频输入溢出，部分数据可能丢失")
                }
                
                audioReadCounter++
                frameCount
            } else {
                println("[ERROR] 读取音频数据失败: ${Pa_GetErrorText(result)?.toKString()}")
                -1
            }
        } catch (e: Exception) {
            println("[ERROR] 读取音频数据时出错: ${e.message}")
            e.printStackTrace()
            -1
        }
    }
    
    /**
     * 播放音频数据
     * @param buffer 数据缓冲区
     * @param frameCount 帧数
     * @return 播放的帧数，负值表示错误
     */
    fun playAudio(buffer: CPointer<ShortVar>, frameCount: Int): Int {
        if (outputStreamPtr.value == null) {
            println("[ERROR] 音频输出流未打开，请在调用playAudio前先调用openOutputStream方法")
            return -1
        }

        // 无论帧数大小，都尝试直接播放，确保声音输出
        println("[DEBUG] 直接尝试播放音频数据，帧数: $frameCount")
        
        // 提高音量，增大增益确保声音可听，但不要过度放大导致爆音
        val amplifiedBuffer = nativeHeap.allocArray<ShortVar>(frameCount)
        val gain = 3.0f // 降低增益从10.0f到3.0f，避免爆音
        
        for (i in 0 until frameCount) {
            val amplifiedValue = buffer[i].toInt() * gain
            // 限制在有效范围内
            amplifiedBuffer[i] = kotlin.math.max(-32768, kotlin.math.min(32767, amplifiedValue.toInt())).toShort()
        }
        
        try {
            val result = Pa_WriteStream(outputStreamPtr.value, amplifiedBuffer, frameCount.toUInt())
            if (result == paNoError) {
                println("[INFO] 播放放大音频数据成功")
                nativeHeap.free(amplifiedBuffer.rawValue)
                return frameCount
            } else if (result == paOutputUnderflowed) {
                println("[WARN] 播放放大音频数据出现欠载 (underflow)，继续尝试播放")
                nativeHeap.free(amplifiedBuffer.rawValue)
                return frameCount
            } else {
                println("[ERROR] 播放放大音频数据失败: ${Pa_GetErrorText(result)?.toKString()}")
                nativeHeap.free(amplifiedBuffer.rawValue)
                
                // 如果直接播放失败，回退到缓冲策略
                return playAudioWithBuffer(buffer, frameCount)
            }
        } catch (e: Exception) {
            println("[ERROR] 播放放大音频数据时出错: ${e.message}")
            e.printStackTrace()
            nativeHeap.free(amplifiedBuffer.rawValue)
            
            // 如果直接播放出错，回退到缓冲策略
            return playAudioWithBuffer(buffer, frameCount)
        }
    }
    
    /**
     * 使用缓冲策略播放音频
     */
    private fun playAudioWithBuffer(buffer: CPointer<ShortVar>, frameCount: Int): Int {
        // 如果帧数太小，积累到缓冲区
        if (frameCount < minPlayFrames && audioPlayBufferPos + frameCount < audioPlayBuffer.size) {
            println("[DEBUG] 帧数较小(${frameCount})，积累到缓冲区，当前缓冲区位置: $audioPlayBufferPos")
            
            // 复制数据到缓冲区
            for (i in 0 until frameCount) {
                audioPlayBuffer[audioPlayBufferPos + i] = buffer[i]
            }
            audioPlayBufferPos += frameCount
            
            // 不够播放，继续积累
            return frameCount
        }
        
        // 如果有缓冲数据并且当前帧不小，先播放缓冲数据
        if (audioPlayBufferPos > 0) {
            println("[DEBUG] 播放积累的缓冲数据，大小: $audioPlayBufferPos")
            
            // 复制缓冲数据到临时缓冲区
            val tempBuffer = nativeHeap.allocArray<ShortVar>(audioPlayBufferPos + frameCount)
            
            // 先复制缓冲区数据
            for (i in 0 until audioPlayBufferPos) {
                tempBuffer[i] = audioPlayBuffer[i]
            }
            
            // 再复制当前帧数据
            for (i in 0 until frameCount) {
                tempBuffer[audioPlayBufferPos + i] = buffer[i]
            }
            
            // 播放合并后的数据
            val totalFrames = audioPlayBufferPos + frameCount
            println("[DEBUG] 播放合并数据，总帧数: $totalFrames")
            
            val result = try {
                val writeResult = Pa_WriteStream(outputStreamPtr.value, tempBuffer, totalFrames.toUInt())
                if (writeResult == paNoError) {
                    println("[INFO] 播放合并音频数据成功")
                    totalFrames
                } else if (writeResult == paOutputUnderflowed) {
                    println("[WARN] 播放合并音频数据出现欠载 (underflow)，继续尝试播放")
                    totalFrames
                } else {
                    println("[ERROR] 播放合并音频数据失败: ${Pa_GetErrorText(writeResult)?.toKString()}")
                    -1
                }
            } catch (e: Exception) {
                println("[ERROR] 播放合并音频数据时出错: ${e.message}")
                e.printStackTrace()
                -1
            }
            
            // 清空缓冲区
            audioPlayBufferPos = 0
            
            // 释放临时缓冲区
            nativeHeap.free(tempBuffer.rawValue)
            
            return result
        }

        println("[DEBUG] 直接播放音频数据，帧数: $frameCount")
        return try {
            val result = Pa_WriteStream(outputStreamPtr.value, buffer, frameCount.toUInt())
            if (result == paNoError) {
                println("[INFO] 播放音频数据成功")
                frameCount
            } else if (result == paOutputUnderflowed) {
                println("[WARN] 播放音频数据出现欠载 (underflow)，继续尝试播放")
                // 即使发生欠载也返回成功，因为这通常是可以恢复的
                frameCount
            } else {
                println("[ERROR] 播放音频数据失败: ${Pa_GetErrorText(result)?.toKString()}")
                -1
            }
        } catch (e: Exception) {
            println("[ERROR] 播放音频数据时出错: ${e.message}")
            e.printStackTrace()
            -1
        }
    }


    /**
     * 关闭音频流
     */
    @OptIn(ExperimentalNativeApi::class)
    override suspend fun closeStreams() {
        try {
            audioMutex.withLock {
                // 关闭输入流
                if (inputStreamPtr.value != null) {
                    try {
                        Pa_StopStream(inputStreamPtr.value)
                        Pa_CloseStream(inputStreamPtr.value)
                        println("[INFO] 输入音频流已关闭 \n ${Throwable().getStackTrace().joinToString(" \n")} \n")
                    } catch (e: Exception) {
                        println("[WARN] 关闭输入流时出错: ${e.message}")
                    }
                    inputStreamPtr.value = null
                }
                
                // 在关闭输出流前，先播放缓冲区中的数据
                if (outputStreamPtr.value != null && audioPlayBufferPos > 0) {
                    try {
                        println("[INFO] 关闭输出流前播放剩余缓冲数据，大小: $audioPlayBufferPos")
                        val tempBuffer = nativeHeap.allocArray<ShortVar>(audioPlayBufferPos)
                        
                        // 复制缓冲区数据
                        for (i in 0 until audioPlayBufferPos) {
                            tempBuffer[i] = audioPlayBuffer[i]
                        }
                        
                        // 播放剩余数据
                        Pa_WriteStream(outputStreamPtr.value, tempBuffer, audioPlayBufferPos.toUInt())
                        
                        // 释放临时缓冲区
                        nativeHeap.free(tempBuffer.rawValue)
                        
                        // 清空缓冲区
                        audioPlayBufferPos = 0
                    } catch (e: Exception) {
                        println("[WARN] 关闭前播放剩余数据时出错: ${e.message}")
                    }
                }
                
                // 关闭输出流
                if (outputStreamPtr.value != null) {
                    try {
                        Pa_StopStream(outputStreamPtr.value)
                        Pa_CloseStream(outputStreamPtr.value)
                        println("[INFO] 输出音频流已关闭")
                    } catch (e: Exception) {
                        println("[WARN] 关闭输出流时出错: ${e.message}")
                    }
                    outputStreamPtr.value = null
                }
                
                _deviceState.value = AudioDevice.AudioDeviceState.READY
            }
        } catch (e: Exception) {
            println("[ERROR] 关闭音频流时出错: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * 释放资源
     */
    override suspend fun release() {
        try {
            // 关闭音频流
            closeStreams()
            
            // 终止PortAudio
            if (portAudioInitialized) {
                Pa_Terminate()
                portAudioInitialized = false
                println("[INFO] PortAudio已终止")
            }
            
            // 释放内存
            nativeHeap.free(inputStreamPtr.rawPtr)
            nativeHeap.free(outputStreamPtr.rawPtr)
            
            _deviceState.value = AudioDevice.AudioDeviceState.IDLE
        } catch (e: Exception) {
            println("[ERROR] 释放资源时出错: ${e.message}")
            e.printStackTrace()
            _deviceState.value = AudioDevice.AudioDeviceState.ERROR
        }
    }
}