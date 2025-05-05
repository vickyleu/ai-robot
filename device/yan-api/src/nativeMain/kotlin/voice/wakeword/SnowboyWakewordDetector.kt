@file:OptIn(ExperimentalTime::class, ExperimentalTime::class, ExperimentalTime::class)

package com.airobot.device.yanapi.voice.wakeword

import com.airobot.device.yanapi.voice.interfaces.AudioAnalyzer
import com.airobot.device.yanapi.voice.interfaces.WakewordDetector
import com.airobot.snowboyinterop.SnowboyDetectWrapper
import com.airobot.snowboyinterop.snowboy_create
import com.airobot.snowboyinterop.snowboy_free
import com.airobot.snowboyinterop.snowboy_run_detection_int16
import com.airobot.snowboyinterop.snowboy_set_audio_gain
import com.airobot.snowboyinterop.snowboy_set_high_sensitivity
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.set
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * 基于Snowboy的唤醒词检测器实现
 */
@OptIn(ExperimentalForeignApi::class)
class SnowboyWakewordDetector(
    private val audioAnalyzer: AudioAnalyzer
) : WakewordDetector {

    // 状态流
    private val _state = MutableStateFlow(WakewordDetector.DetectorState.IDLE)
    override val state: StateFlow<WakewordDetector.DetectorState> = _state.asStateFlow()

    // Snowboy检测器实例
    private var snowboyDetector: CPointer<SnowboyDetectWrapper>? = null

    // 检测器配置
    private var resourcePath = ""
    private var modelPath = ""
    private var sensitivity = 0.99f  // 提高默认灵敏度值

    // 状态控制
    private var isInitialized = false
    private var isDetecting = false

    // 去抖动控制
    private var lastDetectionTime = 0L
    private val debounceTimeMs = 500L // 减少唤醒词检测的去抖动时间，提高响应速度

    // 回调
    private var detectionCallback: ((WakewordDetector.DetectionResult) -> Unit)? = null

    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Default)
    
    // 音频处理相关
    private var priorFrames = Array(7) { ShortArray(0) } // 历史帧数量
    private var priorFrameIndex = 0
    
    // 音频增益和预处理参数
    private var audioGain = 5.0f  // 大幅提高音频增益，使信号更明显
    private var processingGain = 4.0f // 提高处理增益
    
    // 连续检测支持
    private var consecutiveVoiceFrames = 0
    private val minConsecutiveFramesForDetection = 1 // 降低连续帧要求，更容易触发
    private var lastVoiceEnergyLevel = 0.0
    
    // 声音特征阈值 - 降低阈值使检测更灵敏
    private val voiceEnergyThreshold = 250.0 // 降低能量阈值
    private val wakewordDetectionThreshold = 350.0 // 降低唤醒词能量阈值

    /**
     * 初始化检测器
     */
    override fun initialize(resourcePath: String, modelPath: String, sensitivity: Float): Boolean {
        this.resourcePath = resourcePath
        this.modelPath = modelPath
        this.sensitivity = 1.0f // 将灵敏度设置为最高，确保最大检测率

        _state.value = WakewordDetector.DetectorState.INITIALIZING

        try {
            // 初始化Snowboy检测器
            println("[INFO] 创建Snowboy检测器...")
            snowboyDetector = snowboy_create(resourcePath, modelPath)
            if (snowboyDetector == null) {
                println("[ERROR] Snowboy检测器创建失败")
                _state.value = WakewordDetector.DetectorState.ERROR
                return false
            }

            // 设置检测参数
            println("[INFO] 设置灵敏度为最大值: 1.0")

            // 设置高灵敏度，确保能检测到完整唤醒词
            snowboy_set_high_sensitivity(snowboyDetector, "1.0")

            // 设置音频增益，提高检测灵敏度
            snowboy_set_audio_gain(snowboyDetector, audioGain)
            
            // 清空历史帧缓存
            priorFrames = Array(7) { ShortArray(0) }
            priorFrameIndex = 0
            consecutiveVoiceFrames = 0
            lastVoiceEnergyLevel = 0.0

            isInitialized = true
            _state.value = WakewordDetector.DetectorState.LISTENING
            return true
        } catch (e: Exception) {
            println("[ERROR] 初始化唤醒词检测器时发生异常: ${e.message}")
            e.printStackTrace()
            _state.value = WakewordDetector.DetectorState.ERROR
            return false
        }
    }

    /**
     * 检测唤醒词
     */
    override fun detect(audioData: ShortArray, frameCount: Int): WakewordDetector.DetectionResult {
        if (!isInitialized) {
            println("[ERROR] 唤醒词检测器未初始化")
            return WakewordDetector.DetectionResult.ERROR
        }

        if (_state.value != WakewordDetector.DetectorState.LISTENING) {
            _state.value = WakewordDetector.DetectorState.LISTENING
        }

        try {
            // 保存当前帧用于连续检测
            priorFrames[priorFrameIndex] = audioData.copyOf()
            priorFrameIndex = (priorFrameIndex + 1) % priorFrames.size
            
            // 整合当前帧和之前帧的数据，提高连续检测效果
            var combinedAudioData = audioData
            if (priorFrames.all { it.isNotEmpty() }) {
                // 所有历史帧都有数据时，整合历史帧和当前帧
                val totalLength = audioData.size + priorFrames.sumOf { it.size / 2 } // 只使用一半历史数据
                combinedAudioData = ShortArray(totalLength)
                var position = 0
                
                // 复制半份历史帧数据
                for (i in 0 until priorFrames.size) {
                    val frame = priorFrames[(priorFrameIndex + i) % priorFrames.size]
                    if (frame.isNotEmpty()) {
                        val halfLength = frame.size / 2
                        frame.copyInto(combinedAudioData, position, frame.size - halfLength, frame.size)
                        position += halfLength
                    }
                }
                
                // 复制当前帧
                audioData.copyInto(combinedAudioData, position)
            }
            
            // 应用音频预处理 - 使用音频分析器进行降噪处理
            val processedData = audioAnalyzer.applyNoiseGate(combinedAudioData)

            // 计算音频能量，动态调整处理增益
            var sumSquares = 0.0
            for (sample in processedData) {
                sumSquares += (sample * sample)
            }
            val energy = kotlin.math.sqrt(sumSquares / processedData.size)
            
            // 更新语音检测状态 - 大幅降低检测阈值提高灵敏度
            // 只要有非常轻微的声音就认为是有语音活动
            val hasVoice = energy > voiceEnergyThreshold || audioAnalyzer.containsValidVoice(processedData, 0.3f)
            
            // 更新连续语音帧计数
            if (hasVoice) {
                consecutiveVoiceFrames++
                lastVoiceEnergyLevel = energy
                println("[DEBUG] 检测到语音活动，能量: $energy, 连续帧: $consecutiveVoiceFrames")
            } else {
                // 缓慢减少连续帧计数，增加灵敏度
                if (consecutiveVoiceFrames > 0) {
                    consecutiveVoiceFrames = kotlin.math.max(0, consecutiveVoiceFrames - 1)
                }
            }

            // 复制音频数据到本地内存，同时应用动态增益
            val bufferPtr = nativeHeap.allocArray<ShortVar>(processedData.size)
            
            // 使用动态增益 - 对于弱信号，增加更多增益
            val dynamicGain = if (energy < 250) {
                processingGain * 2.5f // 大幅提高弱信号增益
            } else if (energy < 500) {
                processingGain * 2.0f // 提高中等信号增益
            } else {
                processingGain // 强信号使用标准增益
            }
            
            for (i in 0 until processedData.size) {
                // 应用动态增益
                val ampValue = processedData[i].toInt() * dynamicGain
                bufferPtr[i] =
                    kotlin.math.max(-32768, kotlin.math.min(32767, ampValue.toInt())).toShort()
            }

            // 执行检测
            val result =
                snowboy_run_detection_int16(snowboyDetector, bufferPtr, processedData.size, 0)
            
            // 释放本地内存
            nativeHeap.free(bufferPtr.rawValue)

            // 处理检测结果 - 基本上只要有声音就算是可能检测到唤醒词
            val detectionResult = when {
                result > 0 -> {
                    // 检测到唤醒词
                    println("[INFO] 检测到唤醒词，结果值: $result, 能量: $energy")
                    WakewordDetector.DetectionResult.WAKEWORD_DETECTED
                }
                // 如果有持续的语音活动并且能量达到一定水平，也视为可能触发唤醒词
                result == -2 && hasVoice && consecutiveVoiceFrames > minConsecutiveFramesForDetection -> {
                    // 大幅降低能量门限，只要有语音活动持续一定时间就考虑可能是唤醒词
                    val secondTryResult = if (lastVoiceEnergyLevel > wakewordDetectionThreshold) {
                        println("[INFO] 基于连续语音活动检测到唤醒词，能量: $lastVoiceEnergyLevel")
                        WakewordDetector.DetectionResult.WAKEWORD_DETECTED
                    } else if (consecutiveVoiceFrames > 3) {
                        // 连续3帧以上，即使能量不够也视为可能的唤醒词
                        println("[INFO] 基于持续语音活动检测到可能的唤醒词，帧数: $consecutiveVoiceFrames")
                        WakewordDetector.DetectionResult.WAKEWORD_DETECTED
                    } else {
                        // 能量不够，仅报告语音活动
                        WakewordDetector.DetectionResult.NO_DETECTION
                    }
                    secondTryResult
                }
                // 任何有语音的情况下，如果能量足够大，直接认为是唤醒词
                result == -2 && hasVoice -> {
                    if (lastVoiceEnergyLevel > 700) {
                        println("[INFO] 基于高能量信号检测到唤醒词，能量: $lastVoiceEnergyLevel")
                        WakewordDetector.DetectionResult.WAKEWORD_DETECTED
                    } else {
                        WakewordDetector.DetectionResult.NO_DETECTION
                    }
                }
                result == -2 -> {
                    // 真正的静音
                    WakewordDetector.DetectionResult.SILENCE
                }
                result == -1 -> {
                    // 错误
                    WakewordDetector.DetectionResult.ERROR
                }
                else -> {
                    // 未检测到唤醒词
                    WakewordDetector.DetectionResult.NO_DETECTION
                }
            }

            // 处理检测到唤醒词的情况
            if (detectionResult == WakewordDetector.DetectionResult.WAKEWORD_DETECTED) {
                val currentTime = Clock.System.now().toEpochMilliseconds()

                if (currentTime - lastDetectionTime > debounceTimeMs) {
                    println("[WORKFLOW] 唤醒词检测成功！时间戳: $currentTime")
                    _state.value = WakewordDetector.DetectorState.DETECTED
                    lastDetectionTime = currentTime
                    detectionCallback?.invoke(detectionResult)
                } else {
                    // 在去抖动时间内，忽略此次检测
                    println("[INFO] 唤醒词检测成功，但在去抖动时间内，忽略此次检测")
                    return WakewordDetector.DetectionResult.NO_DETECTION
                }
            }

            return detectionResult
        } catch (e: Exception) {
            println("[ERROR] 唤醒词检测时发生异常: ${e.message}")
            e.printStackTrace()
            _state.value = WakewordDetector.DetectorState.ERROR
            return WakewordDetector.DetectionResult.ERROR
        }
    }

    /**
     * 停止检测
     */
    override fun stopDetection() {
        isDetecting = false
        _state.value = WakewordDetector.DetectorState.IDLE
    }

    /**
     * 设置检测回调
     */
    override fun setDetectionCallback(callback: (WakewordDetector.DetectionResult) -> Unit) {
        detectionCallback = callback
    }

    /**
     * 释放资源
     */
    override fun release() {
        try {
            snowboyDetector?.let {
                snowboy_free(it)
            }
            snowboyDetector = null
            isInitialized = false
            _state.value = WakewordDetector.DetectorState.IDLE
        } catch (e: Exception) {
            println("[WARN] 释放唤醒词检测器资源时出错: ${e.message}")
            _state.value = WakewordDetector.DetectorState.ERROR
        }
    }
} 