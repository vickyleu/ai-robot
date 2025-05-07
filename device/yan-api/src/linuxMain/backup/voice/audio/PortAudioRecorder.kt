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
import kotlin.math.min
import kotlin.math.sqrt  // 确保有sqrt函数的导入
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
    private var channels = 1        // 改为单声道录制（原为2）
    private var framesPerBuffer = 1024
    // 设备默认采样率
    private var deviceDefaultSampleRate = 48000.0

    // 协程作用域
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recordingJob: Job? = null

    // 回调
    private var audioCallback: ((ShortArray, Int) -> Unit)? = null
    
    // 添加一个计数器用于控制打印频率
    private var frameCounter = 0
    private val printFrequency = 5 // 从10改为5，更频繁地打印统计信息
    private val detailedPrintFrequency = 30 // 从60改为30，更频繁地打印详细信息
    
    // 新增音频特征追踪变量
    private var lastRmsValues = DoubleArray(5) { 0.0 } // 记录最近5帧的RMS值
    private var lastRmsIndex = 0 // 当前RMS数组索引位置
    private var lastZcrValues = FloatArray(5) { 0.0f } // 记录最近5帧的ZCR值
    private var lastZcrIndex = 0 // 当前ZCR数组索引位置
    private var possibleWakewordCount = 0 // 记录可能的唤醒词特征连续出现次数

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
            
            // 保存设备默认采样率
            this.deviceDefaultSampleRate = deviceInfo.defaultSampleRate

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

                // 支持的设备类型列表（按优先级排序）
                val supportedDeviceTypes = listOf("duplex", "asymed", "dsnooped", "dmixed")

                // 尝试找到第一个工作的输入设备
                for (i in 0 until deviceCount) {
                    val info = PortAudio.getDeviceInfo(i)
                    if (info != null) {
                        val deviceName = info.name?.toKString() ?: "未知设备"
                        println("[DEBUG] 设备 $i: $deviceName, 输入通道: ${info.maxInputChannels}")
                        
                        // 检查是否是我们支持的设备类型
                        if (info.maxInputChannels > 0 && supportedDeviceTypes.any { deviceName.contains(it) }) {
                            // 保存设备默认采样率
                            deviceDefaultSampleRate = info.defaultSampleRate
                            
                            // 尝试打开此设备
                            inputParams.device = i
                            inputParams.channelCount = channels  // 使用单声道
                            inputParams.sampleFormat = paInt16
                            inputParams.suggestedLatency = 0.05  // 降低延迟
                            inputParams.hostApiSpecificStreamInfo = null

                            val (code, _) = PortAudio.openStream(
                                memScope = memScope,
                                inputParameters = inputParams.ptr,
                                outputParameters = null,
                                sampleRate = deviceDefaultSampleRate,  // 使用设备默认采样率
                                framesPerBuffer = 1024u,
                                streamFlags = 0u,
                                streamCallback = null,
                                userData = null
                            )

                            if (code == 0) {  // paNoError
                                selectedDevice = i
                                foundWorkingDevice = true
                                println("[INFO] 找到工作的输入设备: $i (${deviceName})")
                                break
                            } else {
                                println("[DEBUG] 设备 $i 不可用: ${PortAudio.getErrorText(code)}")
                            }
                        }
                    }
                }

                // 如果没找到优先设备，尝试任何可用的输入设备
                if (!foundWorkingDevice) {
                    println("[WARN] 未找到首选输入设备，尝试任何可用设备")
                    
                    for (i in 0 until deviceCount) {
                        val info = PortAudio.getDeviceInfo(i)
                        if (info != null && info.maxInputChannels > 0) {
                            // 保存设备默认采样率
                            deviceDefaultSampleRate = info.defaultSampleRate
                            
                            // 尝试打开此设备
                            inputParams.device = i
                            inputParams.channelCount = channels
                            inputParams.sampleFormat = paInt16
                            inputParams.suggestedLatency = 0.05
                            inputParams.hostApiSpecificStreamInfo = null

                            val (code, _) = PortAudio.openStream(
                                memScope = memScope,
                                inputParameters = inputParams.ptr,
                                outputParameters = null,
                                sampleRate = deviceDefaultSampleRate,  // 使用设备默认采样率
                                framesPerBuffer = 1024u,
                                streamFlags = 0u,
                                streamCallback = null,
                                userData = null
                            )

                            if (code == 0) {  // paNoError
                                selectedDevice = i
                                foundWorkingDevice = true
                                val deviceName = info.name?.toKString() ?: "未知设备"
                                println("[INFO] 找到备选输入设备: $i (${deviceName})")
                                break
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
                inputParams.suggestedLatency = 0.05  // 降低延迟
                inputParams.hostApiSpecificStreamInfo = null

                // 打开流
                val (err, streamPtr) = PortAudio.openStream(
                    memScope = memScope,
                    inputParameters = inputParams.ptr,
                    outputParameters = null,
                    sampleRate = deviceDefaultSampleRate,  // 使用设备默认采样率
                    framesPerBuffer = framesPerBuffer.convert(),
                    streamFlags = paClipOff,  // 添加防止裁剪的标志
                    streamCallback = null,
                    userData = null
                )

                if (err != paNoError) {
                    println("[ERROR] 打开音频流失败: $err - ${PortAudio.getErrorText(err)}")
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
            
            // 重置音频帧计数器和特征追踪变量
            frameCounter = 0
            possibleWakewordCount = 0
            for (i in lastRmsValues.indices) lastRmsValues[i] = 0.0
            for (i in lastZcrValues.indices) lastZcrValues[i] = 0.0f
            
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
                            sleep(5u)  // 降低等待时间，减少延迟
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

                        // ==================== 原始音频数据统计 ====================
                        // 计算音频统计数据
                        var sum = 0.0
                        var sumSquares = 0.0
                        var max = Short.MIN_VALUE
                        var min = Short.MAX_VALUE
                        var zeroCount = 0 // 计算零值样本数量
                        
                        for (sample in buffer) {
                            sum += sample
                            sumSquares += (sample * sample)
                            if (sample > max) max = sample
                            if (sample < min) min = sample
                            if (sample == 0.toShort()) zeroCount++
                        }
                        
                        val avg = sum / buffer.size
                        val rms = sqrt(sumSquares / buffer.size)
                        val zeroPercent = (zeroCount * 100.0 / buffer.size)
                        
                        // 增加计数器
                        frameCounter++
                        
                        // 计算频谱特征 - 简化版过零率计算
                        var zcrCount = 0
                        for (i in 1 until buffer.size) {
                            if ((buffer[i] >= 0 && buffer[i-1] < 0) || (buffer[i] < 0 && buffer[i-1] >= 0)) {
                                zcrCount++
                            }
                        }
                        val zcr = zcrCount.toFloat() / buffer.size
                        
                        // 更新历史值数组
                        lastRmsValues[lastRmsIndex] = rms
                        lastRmsIndex = (lastRmsIndex + 1) % lastRmsValues.size
                        lastZcrValues[lastZcrIndex] = zcr
                        lastZcrIndex = (lastZcrIndex + 1) % lastZcrValues.size
                        
                        // 计算平均RMS和ZCR
                        val avgRms = lastRmsValues.average()
                        val avgZcr = lastZcrValues.average()
                        
                        // 始终打印音频捕获信息，不受频率控制
                        println("【音频捕获】帧数=${buffer.size}, RMS=${rms.toInt()}, ZCR=${zcr.format(3)}, 最大值=$max, 最小值=$min")
                        
                        // 控制打印频率，避免日志刷屏
                        if (frameCounter % printFrequency == 0) {
                            // 基本音频统计打印
                            println("【详细音频】平均值=${avg.toInt()}, 零值比例=${zeroPercent.toInt()}%, 平均RMS=${avgRms.toInt()}, 平均ZCR=${avgZcr.format(3)}")
                        }
                        
                        // 唤醒词特征检测 - 更灵敏的检测条件
                        val isWakewordFeature = (rms > 150 && rms < 2000 && zcr < 0.25)
                        
                        // 更新连续唤醒词特征计数
                        if (isWakewordFeature) {
                            possibleWakewordCount++
                            // 无论何时，只要检测到可能的唤醒词特征，就打印详细信息
                            println("【可能唤醒词】帧#${frameCounter}, RMS=${rms.toInt()}, 过零率=${zcr.format(3)}, 连续帧=${possibleWakewordCount}, 样本: ${buffer.slice(0..min(49, buffer.size-1))}")
                        } else {
                            // 递减连续计数，但不低于0
                            if (possibleWakewordCount > 0) possibleWakewordCount--
                        }
                        
                        // 如果存在有效信号（RMS超过一定阈值），打印样本数据
                        if (rms > 100) {
                            // 每N帧打印一次详细有效音频信息或者当RMS显著高于平均值
                            if (frameCounter % detailedPrintFrequency == 0 || rms > avgRms * 1.5) {
                                println("【有效音频】帧#${frameCounter}, RMS=${rms.toInt()}, 过零率=${zcr.format(3)}, 平均RMS=${avgRms.toInt()}, 样本片段: ${buffer.slice(0..min(19, buffer.size-1))}")
                            }
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

    /**
     * 格式化浮点数为指定小数位数的字符串
     */
    private fun Float.format(digits: Int): String {
        return "%.${digits}f".format(this)
    }

    /**
     * 格式化双精度浮点数为指定小数位数的字符串
     */
    private fun Double.format(digits: Int): String {
        return "%.${digits}f".format(this)
    }
} 