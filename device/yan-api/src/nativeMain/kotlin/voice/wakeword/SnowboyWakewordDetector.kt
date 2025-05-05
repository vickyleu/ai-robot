@file:OptIn(ExperimentalTime::class, ExperimentalTime::class)

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
    private var sensitivity = 0.95f  // 提高默认灵敏度值(原为0.9f)

    // 状态控制
    private var isInitialized = false
    private var isDetecting = false

    // 去抖动控制
    private var lastDetectionTime = 0L
    private val debounceTimeMs = 2000L // 减少唤醒词检测的去抖动时间(原为3000L)，提高响应速度

    // 回调
    private var detectionCallback: ((WakewordDetector.DetectionResult) -> Unit)? = null

    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Default)

    /**
     * 初始化检测器
     */
    override fun initialize(resourcePath: String, modelPath: String, sensitivity: Float): Boolean {
        this.resourcePath = resourcePath
        this.modelPath = modelPath
        this.sensitivity = sensitivity

        _state.value = WakewordDetector.DetectorState.INITIALIZING

        try {
            // 验证文件是否存在
            scope.launch {
                // 在实际场景中应该检查文件存在
                println("[INFO] 使用资源文件: $resourcePath")
                println("[INFO] 使用模型文件: $modelPath")
            }

            // 初始化Snowboy检测器
            println("[INFO] 创建Snowboy检测器...")
            snowboyDetector = snowboy_create(resourcePath, modelPath)
            if (snowboyDetector == null) {
                println("[ERROR] Snowboy检测器创建失败")
                _state.value = WakewordDetector.DetectorState.ERROR
                return false
            }

            // 设置检测参数
            println("[INFO] 设置灵敏度 $sensitivity")

            // 设置高灵敏度，确保能检测到完整唤醒词
            snowboy_set_high_sensitivity(snowboyDetector, sensitivity.toString())

            // 设置音频增益，提高检测灵敏度 (增加到2.5)
            snowboy_set_audio_gain(snowboyDetector, 2.5f)

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
            // 应用音频预处理 - 使用音频分析器进行降噪处理
            val processedData = audioAnalyzer.applyNoiseGate(audioData)

            // 复制音频数据到本地内存，同时应用较大的增益
            val bufferPtr = nativeHeap.allocArray<ShortVar>(frameCount)
            for (i in 0 until frameCount) {
                // 应用较高增益，提高检测灵敏度(增加到2.0)
                val ampValue = processedData[i].toInt() * 2.0
                bufferPtr[i] =
                    kotlin.math.max(-32768, kotlin.math.min(32767, ampValue.toInt())).toShort()
            }

            // 检测是否有语音活动 - 降低有效语音的阈值
            val hasVoice = audioAnalyzer.containsValidVoice(processedData, 0.8f) // 降低语音活动阈值

            // 执行检测
            val result =
                snowboy_run_detection_int16(snowboyDetector, bufferPtr, frameCount.convert(), 0)

            // 释放本地内存
            nativeHeap.free(bufferPtr.rawValue)

            // 处理检测结果 - 提高检测灵敏度
            val detectionResult = when (result) {
                -2 -> if (hasVoice) WakewordDetector.DetectionResult.NO_DETECTION else WakewordDetector.DetectionResult.SILENCE // 有语音时认为不是静音
                -1 -> WakewordDetector.DetectionResult.ERROR      // 错误
                0 -> WakewordDetector.DetectionResult.NO_DETECTION // 未检测到
                else -> WakewordDetector.DetectionResult.WAKEWORD_DETECTED // 检测到唤醒词
            }

            // 处理检测到唤醒词的情况
            if (detectionResult == WakewordDetector.DetectionResult.WAKEWORD_DETECTED) {
                val currentTime = Clock.System.now().toEpochMilliseconds()

                if (currentTime - lastDetectionTime > debounceTimeMs) {
                    println("[WORKFLOW] 唤醒词检测成功！")
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