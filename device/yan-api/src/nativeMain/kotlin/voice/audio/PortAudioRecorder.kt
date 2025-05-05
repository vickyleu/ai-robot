@file:OptIn(ExperimentalForeignApi::class)

package com.airobot.device.yanapi.voice.audio

import com.airobot.alsainterop.sleep
import com.airobot.device.yanapi.voice.interfaces.AudioRecorder
import com.airobot.device.yanapi.voice.utils.PortAudio
import com.airobot.portaudiointerop.PaStreamParameters
import com.airobot.portaudiointerop.paClipOff
import com.airobot.portaudiointerop.paInputOverflowed
import com.airobot.portaudiointerop.paInt16
import com.airobot.portaudiointerop.paNoError
import com.airobot.voskinterop.memset
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.ExperimentalTime

/**
 * 基于PortAudio的音频录制器实现
 */
class PortAudioRecorder : AudioRecorder {
    // 状态流
    private val _state = MutableStateFlow(AudioRecorder.RecorderState.IDLE)
    override val state: StateFlow<AudioRecorder.RecorderState> = _state.asStateFlow()

    // PortAudio相关
    private var stream: COpaquePointer? = null
    private var isInitialized = false
    private var isRecording = false
    private var isPaused = false

    // 音频参数
    private var sampleRate = 48000  // 使用48kHz而不是44.1kHz
    private var channels = 2        // 尝试单声道录制
    private var framesPerBuffer = 1024

    // 协程作用域
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recordingJob: Job? = null

    // 回调
    private var audioCallback: ((ShortArray, Int) -> Unit)? = null
    /**
     * 初始化录制器
     */
    @OptIn(ExperimentalForeignApi::class)
    override fun initialize(sampleRate: Int, channels: Int): Boolean {
        if (isInitialized) return true

        _state.value = AudioRecorder.RecorderState.INITIALIZING

        this.sampleRate = sampleRate
        this.channels = channels

        try {
            // 初始化PortAudio
            val error = PortAudio.initialize()
            if (error != paNoError) {
                println("[ERROR] PortAudio初始化失败：$error - ${PortAudio.getErrorText(error)}")
                _state.value = AudioRecorder.RecorderState.ERROR
                return false
            }

            // 选择设备
            val actualDeviceId = PortAudio.getDefaultInputDevice()
            // 列出设备信息
            val deviceInfo = PortAudio.getDeviceInfo(actualDeviceId)
            if (deviceInfo == null) {
                println("[ERROR] 无法获取设备信息")
                _state.value = AudioRecorder.RecorderState.ERROR
                return false
            }

            println("[INFO] 使用录音设备: ${deviceInfo.name?.toKString() ?: "未知设备"}")
            println("[INFO] 最大输入通道数: ${deviceInfo.maxInputChannels}")
            println("[INFO] 默认采样率: ${deviceInfo.defaultSampleRate}")

            isInitialized = true
            _state.value = AudioRecorder.RecorderState.IDLE
            return true
        } catch (e: Exception) {
            println("[ERROR] 初始化录音器时发生异常: ${e.message}")
            e.printStackTrace()
            _state.value = AudioRecorder.RecorderState.ERROR
            return false
        }
    }

    /**
     * 开始录制
     */
    override fun startRecording(): Boolean {
        if (!isInitialized) {
            println("[ERROR] 录音器未初始化")
            return false
        }

        if (isRecording) {
            println("[WARN] 录音器已经在录制中")
            return true
        }

        try {
            if(stream==null){
                // 打开输入流
                val memScope = kotlinx.cinterop.MemScope()
                val inputParams = nativeHeap.alloc<PaStreamParameters>()
                memset(inputParams.ptr, 0, sizeOf<PaStreamParameters>().toUInt())
                // 打印所有可用设备
                val deviceCount = PortAudio.getDeviceCount()
                println("[DEBUG] 发现 $deviceCount 个音频设备")
                var foundWorkingDevice = false
                var selectedDevice = 0


                // 尝试找到第一个工作的输入设备
                for (i in 0 until deviceCount) {
                    val info = PortAudio.getDeviceInfo(i)
                    if (info != null) {
                        println("[DEBUG] 设备 $i: ${info.name?.toKString()}, 输入通道: ${info.maxInputChannels}")
                        if(info.name?.toKString()=="duplex"){
                            if (info.maxInputChannels > 0) {
                                // 尝试打开此设备
                                inputParams.device = i
                                inputParams.channelCount = 1  // 尝试单声道
                                inputParams.sampleFormat = paInt16
                                inputParams.suggestedLatency = 0.1
                                inputParams.hostApiSpecificStreamInfo = null

                                val (code, _) = PortAudio.openStream(
                                    memScope = memScope,
                                    inputParameters = inputParams.ptr,
                                    outputParameters = null,
                                    sampleRate = 48000.0,  // 尝试常用的采样率
                                    framesPerBuffer = 1024u,
                                    streamFlags = 0u,
                                    streamCallback = null,
                                    userData = null
                                )

                                if (code == 0) {  // paNoError
                                    selectedDevice = i
                                    foundWorkingDevice = true
                                    println("[INFO] 找到工作的输入设备: $i")
                                    break
                                } else {
                                    println("[DEBUG] 设备 $i 不可用: ${PortAudio.getErrorText(code)}")
                                }
                            }
                        }
                    }
                }

                if (!foundWorkingDevice) {
                    println("[ERROR] 未找到可用的输入设备")
                    _state.value = AudioRecorder.RecorderState.ERROR
                    return false
                }
                // 使用找到的工作设备
                inputParams.device = selectedDevice
                inputParams.channelCount = channels
                inputParams.sampleFormat = paInt16
                inputParams.suggestedLatency = 0.1
                inputParams.hostApiSpecificStreamInfo = null

                // 打开流
                val (err, streamPtr) = PortAudio.openStream(
                    memScope = memScope,
                    inputParameters =inputParams.ptr /* 输入参数构造 */,
                    outputParameters = null,      // 如果只录音，可将播放参数设为 null
                    sampleRate = sampleRate.toDouble(),
                    framesPerBuffer = framesPerBuffer.convert(),
                    streamFlags = 0u,
                    streamCallback = null,
                    userData = null
                )

                if (err != paNoError) {
                    println("[ERROR] 打开音频流失败: $streamPtr - ${PortAudio.getErrorText(err)}")
                    _state.value = AudioRecorder.RecorderState.ERROR
                    return false
                }

                stream = streamPtr?.get(0)
                if (stream == null) {
                    println("[ERROR] 创建音频流失败")
                    _state.value = AudioRecorder.RecorderState.ERROR
                    return false
                }
                // 尝试获取流信息
                println("[DEBUG] 流已打开，指针: $stream")

                // 启动流
                val startError = PortAudio.startStream(stream)
                if (startError != paNoError) {
                    println(
                        "[ERROR] 启动音频流失败: $startError - ${
                            PortAudio.getErrorText(
                                startError
                            )
                        }"
                    )
                    PortAudio.closeStream(stream)
                    stream = null
                    _state.value = AudioRecorder.RecorderState.ERROR
                    return false
                }
            }
            // 检查流是否真的活跃
            val active = PortAudio.isStreamActive(stream)
            println("[DEBUG] 流活跃状态: $active (1=活跃)")

            isRecording = true
            isPaused = false
            _state.value = AudioRecorder.RecorderState.RECORDING
            // 启动录制协程
            println("[INFO] 开始录制\n")
            recordingJob = scope.launch {
                val buffer = ShortArray(framesPerBuffer * channels)
                val bufferPtr = nativeHeap.allocArray<ShortVar>(buffer.size)

                while (isRecording && !isPaused) {

                    try {
                        // 检查流状态
                        val active = PortAudio.isStreamActive(stream)
                        if (active <= 0) {
                            println("[WARN] 音频流不活跃 (状态: $active)")
                            break
                        }

                        // 检查可读数据
                        val available = PortAudio.getStreamReadAvailable(stream)
                        if (available <= 0) {
                            // 没有数据可读，等待一会儿
                            sleep(10u)
                            continue
                        }

                        // 读取数据
                        val readError =
                            PortAudio.readStream(stream, bufferPtr, framesPerBuffer.convert())
                        if (readError != paNoError && readError != paInputOverflowed) { // paInputOverflowed可以忽略
                            println(
                                "[ERROR] 读取音频数据失败: $readError - ${
                                    PortAudio.getErrorText(
                                        readError
                                    )
                                }"
                            )
                            break
                        }

                        // 复制数据到缓冲区
                        for (i in buffer.indices) {
                            buffer[i] = bufferPtr[i].toShort()
                        }

                        // 调用回调处理音频数据
                        audioCallback?.invoke(buffer, framesPerBuffer)
                    } catch (e: Exception) {
                        println("[ERROR] 录制过程中发生异常: ${e.message}")
                        e.printStackTrace()
                        break
                    }
                }

                // 清理本地内存
                nativeHeap.free(bufferPtr.rawValue)

                if (isRecording && !isPaused) {
                    // 如果不是主动停止录制，说明发生了错误
                    stopRecording()
                    _state.value = AudioRecorder.RecorderState.ERROR
                }
            }
            return true
        } catch (e: Exception) {
            println("[ERROR] 开始录制时发生异常: ${e.message}")
            e.printStackTrace()
            _state.value = AudioRecorder.RecorderState.ERROR
            return false
        }
    }

    /**
     * 停止录制
     */
    override fun stopRecording() {
        if (!isRecording) return

        try {
            isRecording = false
            recordingJob?.cancel()
            recordingJob = null

            // 停止流
            stream?.let {
                PortAudio.stopStream(it)
                PortAudio.closeStream(it)
            }
            stream = null

            _state.value = AudioRecorder.RecorderState.IDLE
        } catch (e: Exception) {
            println("[ERROR] 停止录制时发生异常: ${e.message}")
            e.printStackTrace()
            _state.value = AudioRecorder.RecorderState.ERROR
        }
    }

    /**
     * 暂停录制
     */
    override fun pauseRecording() {
        if (!isRecording || isPaused) return

        isPaused = true
        _state.value = AudioRecorder.RecorderState.PAUSED

        try {
            stream?.let { PortAudio.stopStream(it) }
        } catch (e: Exception) {
            println("[ERROR] 暂停录制时发生异常: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 恢复录制
     */
    override fun resumeRecording() {
        if (!isRecording || !isPaused) return

        try {
            stream?.let {
                val error = PortAudio.startStream(it)
                if (error != paNoError) {
                    println("[ERROR] 恢复录制失败: $error - ${PortAudio.getErrorText(error)}")
                    _state.value = AudioRecorder.RecorderState.ERROR
                    return
                }
            }

            isPaused = false
            _state.value = AudioRecorder.RecorderState.RECORDING
        } catch (e: Exception) {
            println("[ERROR] 恢复录制时发生异常: ${e.message}")
            e.printStackTrace()
            _state.value = AudioRecorder.RecorderState.ERROR
        }
    }

    /**
     * 设置音频处理回调
     */
    override fun setAudioCallback(callback: (ShortArray, Int) -> Unit) {
        audioCallback = callback
    }

    /**
     * 释放资源
     */
    override fun release() {
        stopRecording()

        if (isInitialized) {
            PortAudio.terminate()
            isInitialized = false
        }

        audioCallback = null
        _state.value = AudioRecorder.RecorderState.IDLE
    }
} 