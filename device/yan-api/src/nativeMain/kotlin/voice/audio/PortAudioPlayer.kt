@file:OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)

package com.airobot.device.yanapi.voice.audio

import com.airobot.alsainterop.sleep
import com.airobot.device.yanapi.voice.interfaces.AudioPlayer
import com.airobot.device.yanapi.voice.interfaces.AudioRecorder
import com.airobot.device.yanapi.voice.utils.PaStream
import com.airobot.device.yanapi.voice.utils.PortAudio
import com.airobot.portaudiointerop.PaStreamParameters
import com.airobot.portaudiointerop.paInt16
import com.airobot.portaudiointerop.paNoError
import com.airobot.portaudiointerop.paOutputUnderflow
import com.airobot.voskinterop.memset
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
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
 * 基于PortAudio的音频播放器实现
 */
class PortAudioPlayer : AudioPlayer {
    // 状态流
    private val _state = MutableStateFlow(AudioPlayer.PlayerState.IDLE)
    override val state: StateFlow<AudioPlayer.PlayerState> = _state.asStateFlow()

    // PortAudio相关
    private var stream: COpaquePointer? = null
    private var isInitialized = false
    private var isPlaying = false
    private var isPaused = false

    // 音频参数
    private var sampleRate = 48000
    private var channels = 1
    private var framesPerBuffer = 1024

    // 协程作用域
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var playbackJob: Job? = null

    // 回调
    private var playbackCompletedCallback: (() -> Unit)? = null

    /**
     * 初始化播放器
     */
    override fun initialize(sampleRate: Int, channels: Int): Boolean {
        if (isInitialized) return true

        _state.value = AudioPlayer.PlayerState.INITIALIZING

        this.sampleRate = sampleRate
        this.channels = channels

        try {
            // 初始化PortAudio
            val error = PortAudio.initialize()
            if (error != paNoError) {
                println("[ERROR] PortAudio初始化失败：$error - ${PortAudio.getErrorText(error)}")
                _state.value = AudioPlayer.PlayerState.ERROR
                return false
            }

            // 选择设备
            val actualDeviceId = PortAudio.getDefaultOutputDevice()

            // 列出设备信息
            val deviceInfo = PortAudio.getDeviceInfo(actualDeviceId)
            if (deviceInfo == null) {
                println("[ERROR] 无法获取设备信息")
                _state.value = AudioPlayer.PlayerState.ERROR
                return false
            }

            println("[INFO] 使用播放设备: ${deviceInfo.name ?: "未知设备"}")
            println("[INFO] 最大输出通道数: ${deviceInfo.maxOutputChannels}")
            println("[INFO] 默认采样率: ${deviceInfo.defaultSampleRate}")

            isInitialized = true
            _state.value = AudioPlayer.PlayerState.IDLE
            return true
        } catch (e: Exception) {
            println("[ERROR] 初始化播放器时发生异常: ${e.message}")
            e.printStackTrace()
            _state.value = AudioPlayer.PlayerState.ERROR
            return false
        }
    }

    /**
     * 播放音频数据
     */
    override fun playAudio(buffer: ShortArray, frameCount: Int): Boolean {
        if (!isInitialized) {
            println("[ERROR] 播放器未初始化")
            return false
        }

        if (isPlaying) {
            // 停止当前播放
            stopPlayback()
        }

        try {
            // 打开输出流
            if(stream==null){
                val memScope = kotlinx.cinterop.MemScope()
                val outputParams = nativeHeap.alloc<PaStreamParameters>()
                memset(outputParams.ptr, 0, sizeOf<PaStreamParameters>().toUInt())
                // 打印所有可用设备
                val deviceCount = PortAudio.getDeviceCount()
                println("[DEBUG] 发现 $deviceCount 个音频设备")
                var foundWorkingDevice = false
                var selectedDevice = 0

                // 尝试找到第一个工作的输入设备
                for (i in 0 until deviceCount) {
                    val info = PortAudio.getDeviceInfo(i)
                    if (info != null) {
                        println("[DEBUG] 设备 $i: ${info.name?.toKString()}, 输出通道: ${info.maxOutputChannels}")
                        if(info.name?.toKString()=="dmixed"){
                            if (info.maxOutputChannels > 0) {
                                // 尝试打开此设备
                                outputParams.device = i
                                outputParams.channelCount = 1  // 尝试单声道
                                outputParams.sampleFormat = paInt16
                                outputParams.suggestedLatency = 0.1
                                outputParams.hostApiSpecificStreamInfo = null

                                val (code, _) = PortAudio.openStream(
                                    memScope = memScope,
                                    inputParameters = null,
                                    outputParameters = outputParams.ptr,
                                    sampleRate = 48000.0,  // 尝试常用的采样率
                                    framesPerBuffer = 1024u,
                                    streamFlags = 0u,
                                    streamCallback = null,
                                    userData = null
                                )

                                if (code == 0) {  // paNoError
                                    selectedDevice = i
                                    foundWorkingDevice = true
                                    println("[INFO] 找到工作的输出设备: $i")
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
                    _state.value = AudioPlayer.PlayerState.ERROR
                    return false
                }

                outputParams.apply {
                    //dmixed
                    this.device = selectedDevice // 使用默认输出设备
                    this.channelCount = channels
                    this.sampleFormat = paInt16
                    this.suggestedLatency = 0.1 // 100ms latency
                    this.hostApiSpecificStreamInfo = null
                }
                // 打开流
                val (result, streamPtr) = PortAudio.openStream(
                    memScope,
                    inputParameters = null, // 输入参数
                    outputParameters = outputParams.ptr, // 输出参数
                    sampleRate = sampleRate.toDouble(),
                    framesPerBuffer = framesPerBuffer.convert(),
                    streamFlags = 0u,
                    streamCallback = null, // 回调函数
                    userData = null  // 用户数据
                )

                if (result != paNoError) {
                    println("[ERROR] 打开音频流失败: ${result} - ${PortAudio.getErrorText(result)}")
                    _state.value = AudioPlayer.PlayerState.ERROR
                    return false
                }
                stream = streamPtr?.get(0)
                // 启动流
                val startError = PortAudio.startStream(stream)
                if (startError != paNoError) {
                    println("[ERROR] 启动音频流失败: $startError - ${PortAudio.getErrorText(startError)}")
                    PortAudio.closeStream(stream)
                    stream = null
                    _state.value = AudioPlayer.PlayerState.ERROR
                    return false
                }
            }

            isPlaying = true
            isPaused = false
            _state.value = AudioPlayer.PlayerState.PLAYING

            // 启动播放协程
            playbackJob = scope.launch {
                try {
                    // 复制数据到本地内存
                    val bufferPtr = nativeHeap.allocArray<ShortVar>(buffer.size)
                    for (i in buffer.indices) {
                        bufferPtr[i] = buffer[i]
                    }

                    // 写入数据
                    var framesWritten = 0
                    while (framesWritten < frameCount && isPlaying && !isPaused) {
                        // 检查流状态
                        val active = PortAudio.isStreamActive(stream)
                        if (active <= 0) {
                            println("[WARN] 音频流不活跃")
                            break
                        }

                        // 检查可写数据
                        val available = PortAudio.getStreamWriteAvailable(stream)
                        if (available <= 0) {
                            // 无法写入，等待一会儿
                            sleep(10u)
                            continue
                        }

                        // 计算要写入的帧数
                        val framesToWrite = minOf(available.toInt(), frameCount - framesWritten)
                        if (framesToWrite <= 0) break

                        // 写入数据
                        val writeError = PortAudio.writeStream(
                            stream,
                            bufferPtr.plus(framesWritten * channels),
                            framesToWrite.convert()
                        )

                        if (writeError != paNoError && writeError != paOutputUnderflow.toInt()) {
                            println(
                                "[ERROR] 写入音频数据失败: $writeError - ${
                                    PortAudio.getErrorText(
                                        writeError
                                    )
                                }"
                            )
                            break
                        }

                        framesWritten += framesToWrite
                    }

                    // 清理本地内存
                    nativeHeap.free(bufferPtr.rawValue)

                    // 等待数据播放完成
                    while (isPlaying && !isPaused) {
                        val active = PortAudio.isStreamActive(stream)
                        if (active <= 0) break

                        sleep(10u)
                    }

                    // 播放完成
                    if (isPlaying && !isPaused) {
                        stopPlayback()
                        playbackCompletedCallback?.invoke()
                    }
                } catch (e: Exception) {
                    println("[ERROR] 播放过程中发生异常: ${e.message}")
                    e.printStackTrace()
                    stopPlayback()
                    _state.value = AudioPlayer.PlayerState.ERROR
                }
            }

            return true
        } catch (e: Exception) {
            println("[ERROR] 开始播放时发生异常: ${e.message}")
            e.printStackTrace()
            _state.value = AudioPlayer.PlayerState.ERROR
            return false
        }
    }

    /**
     * 播放音频文件
     */
    override fun playAudioFile(filePath: String): Boolean {
        // 简单实现，实际项目中应使用文件解码库读取音频文件内容
        println("[WARN] 播放音频文件功能尚未实现: $filePath")
        return false
    }

    /**
     * 停止播放
     */
    override fun stopPlayback() {
        if (!isPlaying) return

        try {
            isPlaying = false
            playbackJob?.cancel()
            playbackJob = null

            // 停止流
            stream?.let {
                PortAudio.stopStream(it)
                PortAudio.closeStream(it)
            }
            stream = null

            _state.value = AudioPlayer.PlayerState.STOPPED
        } catch (e: Exception) {
            println("[ERROR] 停止播放时发生异常: ${e.message}")
            e.printStackTrace()
            _state.value = AudioPlayer.PlayerState.ERROR
        }
    }

    /**
     * 暂停播放
     */
    override fun pausePlayback() {
        if (!isPlaying || isPaused) return

        isPaused = true
        _state.value = AudioPlayer.PlayerState.PAUSED

        try {
            stream?.let { PortAudio.stopStream(it) }
        } catch (e: Exception) {
            println("[ERROR] 暂停播放时发生异常: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 恢复播放
     */
    override fun resumePlayback() {
        if (!isPlaying || !isPaused) return

        try {
            stream?.let {
                val error = PortAudio.startStream(it)
                if (error != paNoError) {
                    println("[ERROR] 恢复播放失败: $error - ${PortAudio.getErrorText(error)}")
                    _state.value = AudioPlayer.PlayerState.ERROR
                    return
                }
            }

            isPaused = false
            _state.value = AudioPlayer.PlayerState.PLAYING
        } catch (e: Exception) {
            println("[ERROR] 恢复播放时发生异常: ${e.message}")
            e.printStackTrace()
            _state.value = AudioPlayer.PlayerState.ERROR
        }
    }

    /**
     * 设置播放完成回调
     */
    override fun setPlaybackCompletedCallback(callback: () -> Unit) {
        playbackCompletedCallback = callback
    }

    /**
     * 释放资源
     */
    override fun release() {
        stopPlayback()

        if (isInitialized) {
            PortAudio.terminate()
            isInitialized = false
        }

        playbackCompletedCallback = null
        _state.value = AudioPlayer.PlayerState.IDLE
    }
} 