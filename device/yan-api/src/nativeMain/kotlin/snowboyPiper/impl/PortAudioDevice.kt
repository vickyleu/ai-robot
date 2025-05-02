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
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
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
import platform.posix.setenv
import snowboyPiper.interfaces.AudioDevice
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

    override fun isInitialized(): Boolean {
        return portAudioInitialized
    }

    /**
     * 初始化音频设备
     * @return 初始化是否成功
     */
    override suspend fun initialize(): Boolean {
        println("[INFO] 初始化PortAudio...")
        _deviceState.value = AudioDevice.AudioDeviceState.INITIALIZING
        
        // 设置环境变量，避免冲突
        try {
            setenv("ALSA_CARD", "0", 1)
            setenv("ALSA_PCM_CARD", "0", 1)
            setenv("ALSA_PCM_DEVICE", "0", 1)
            setenv("LIBASOUND_DEBUG", "0", 1) // 忽略ALSA错误消息
            setenv("ALSA_DEBUG", "0", 1) // 关闭调试信息减少噪音
            println("[INFO] 音频环境变量设置成功")
        } catch (e: Exception) {
            println("[WARN] 设置环境变量失败: ${e.message}，继续执行")
        }
        
        // 设置最小化的ALSA配置
        setupMinimalAlsaConfig()
        
        try {
            val paInitResult = Pa_Initialize()
            if (paInitResult != paNoError) {
                println("[ERROR] PortAudio初始化失败: ${Pa_GetErrorText(paInitResult)?.toKString()}")
                _deviceState.value = AudioDevice.AudioDeviceState.ERROR
                return false
            }
            portAudioInitialized = true
            println("[INFO] PortAudio初始化成功")
            
            // 寻找音频设备
            val (inputIdx, outputIdx) = listAudioDevices()
            selectedInputDeviceIndex = inputIdx
            selectedOutputDeviceIndex = outputIdx
            
            if (selectedInputDeviceIndex < 0) {
                // 如果没有找到输入设备，尝试使用默认设备(-1)
                println("[WARN] 没有找到输入设备，将尝试使用默认设备")
                selectedInputDeviceIndex = -1
            }
            speechRecognizer
            println("[INFO] 已选择：输入设备 #$selectedInputDeviceIndex, 输出设备 #$selectedOutputDeviceIndex")
            _deviceState.value = AudioDevice.AudioDeviceState.READY
            return true
        } catch (e: Exception) {
            println("[ERROR] PortAudio初始化异常: ${e.message}")
            e.printStackTrace()
            release()
            _deviceState.value = AudioDevice.AudioDeviceState.ERROR
            return false
        }
    }
    
    /**
     * 设置最小化的ALSA配置
     * 改为使用mono（单声道）配置
     */
    private fun setupMinimalAlsaConfig() {
        scope.launch {
            try {
                // 创建更简单的ALSA配置，减少冲突
                val minimalConfig = """
                    | # 最小化ALSA配置，使用独占模式
                    | pcm.!default {
                    |     type plug
                    |     slave.pcm "hw:0,0"
                    |     card 0
                    |     device 0
                    |     format S16_LE
                    |     channels 1  # 使用单声道
                    |     rate 48000
                    |     nonblock true
                    | }
                    | 
                    | ctl.!default {
                    |     type hw
                    |     card 0
                    | }
                """.trimMargin()
                
                // 检查文件是否存在
                val checkFileCmd = "test -f ~/.asoundrc && echo 'exists' || echo 'not exists'"
                val fileExists = VoskSpeechService.executeCommand(checkFileCmd).trim() == "exists"
                
                if (fileExists) {
                    // 备份现有文件
                    VoskSpeechService.executeCommand("mv ~/.asoundrc ~/.asoundrc.bak_$(date +%s)")
                }
                
                // 创建新的配置文件
                VoskSpeechService.executeCommand("echo '$minimalConfig' > ~/.asoundrc")
                println("[INFO] 创建了最小化ALSA配置(单声道)")
                
                // 让系统应用新配置
                VoskSpeechService.executeCommand("sudo alsactl kill rescan")
                
                // 列举ALSA设备（仅用于调试）
                val output = VoskSpeechService.executeCommand("cat /proc/asound/cards")
                println("[ALSA-CARDS]:\n$output")
                
                val recordDevices = VoskSpeechService.executeCommand("arecord -l")
                println("[ALSA-CAPTURE-DEVICES]:\n$recordDevices")
            } catch (e: Exception) {
                println("[ERROR] 设置ALSA配置时出错: ${e.message}")
            }
        }
    }
    
    /**
     * 列举可用的音频设备
     * @return 输入设备索引和输出设备索引的对
     */
    override fun listAudioDevices(): Pair<Int, Int> {
        println("[INFO] 列举音频设备...")
        var bestInputDeviceIndex = -1
        var bestOutputDeviceIndex = -1
        
        try {
            val deviceCount = Pa_GetDeviceCount()
            println("[INFO] PortAudio检测到 $deviceCount 个设备")
            
            if (deviceCount <= 0) {
                println("[WARNING] 没有检测到音频设备")
                return Pair(-1, -1)
            }
            
            // 寻找输入和输出设备
            for (i in 0 until deviceCount) {
                val deviceInfo = Pa_GetDeviceInfo(i) ?: continue
                val hostApi = Pa_GetHostApiInfo(deviceInfo.pointed.hostApi) ?: continue
                
                hostApi.pointed.name?.let {
                    println("[INFO] Host API: ${it.toKString()}")
                }
                
                val deviceName = deviceInfo.pointed.name?.toKString() ?: "未知"
                println("[DEVICE] #$i: $deviceName")
                println("  - 最大输入通道: ${deviceInfo.pointed.maxInputChannels}")
                println("  - 最大输出通道: ${deviceInfo.pointed.maxOutputChannels}")
                println("  - 默认采样率: ${deviceInfo.pointed.defaultSampleRate}Hz")
                
                // 寻找输入设备
                if (deviceInfo.pointed.maxInputChannels > 0 && bestInputDeviceIndex == -1) {
                    bestInputDeviceIndex = i
                    println("[INFO] 选择设备 #$i 用于音频输入")
                }
                
                // 寻找输出设备
                if (deviceInfo.pointed.maxOutputChannels > 0 && bestOutputDeviceIndex == -1) {
                    bestOutputDeviceIndex = i
                    println("[INFO] 选择设备 #$i 用于音频输出")
                }
                
                // 如果同时找到了输入和输出设备，可以提前结束搜索
                if (bestInputDeviceIndex != -1 && bestOutputDeviceIndex != -1) {
                    break
                }
            }
            
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
                
                // 尝试多次打开流，因为设备可能暂时被占用
                var success = false
                val maxRetries = 5
                var retryCount = 0
                
                while (!success && retryCount < maxRetries) {
                    retryCount++
                    
                    val result = if (deviceIndex < 0) {
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
                        println("[WARN] 尝试 #$retryCount: 无法打开默认输入流: ${Pa_GetErrorText(result)?.toKString()}")
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
                
                // 打开输出流
                val result = if (deviceIndex < 0) {
                    // 使用默认设备
                    Pa_OpenDefaultStream(
                        outputStreamPtr.ptr,
                        0,         // 输入通道数（不需要输入）
                        channels,  // 输出通道数
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
                        outputStreamPtr.ptr,
                        0,         // 输入通道数（不需要输入）
                        channels,  // 输出通道数
                        paInt16,   // 采样格式
                        sampleRate.toDouble(),
                        paFramesPerBufferUnspecified.toUInt(),
                        null,      // 回调函数（不使用回调）
                        null       // 用户数据
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
            val result = Pa_ReadStream(inputStreamPtr.value, buffer, frameCount.toUInt())
            if (result == paNoError || result == paInputOverflowed) {
                // 成功读取或输入溢出（可以接受）
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
            println("[ERROR] 音频输出流未打开")
            return -1
        }

        return try {
            val result = Pa_WriteStream(outputStreamPtr.value, buffer, frameCount.toUInt())
            if (result == paNoError) {
                // 成功播放
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
    override suspend fun closeStreams() {
        try {
            audioMutex.withLock {
                // 关闭输入流
                if (inputStreamPtr.value != null) {
                    try {
                        Pa_StopStream(inputStreamPtr.value)
                        Pa_CloseStream(inputStreamPtr.value)
                        println("[INFO] 输入音频流已关闭")
                    } catch (e: Exception) {
                        println("[WARN] 关闭输入流时出错: ${e.message}")
                    }
                    inputStreamPtr.value = null
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