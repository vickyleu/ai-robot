@file:OptIn(ExperimentalTime::class, ExperimentalTime::class)

package com.airobot.device.yanapi.voice.wakeword

import com.airobot.device.yanapi.voice.analysis.AudioFeatureExtractor
import com.airobot.device.yanapi.voice.analysis.format
import com.airobot.device.yanapi.voice.interfaces.AudioAnalyzer
import com.airobot.device.yanapi.voice.interfaces.WakewordDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * 增强型唤醒词检测器
 * 采用多层过滤和去抖动机制减少误触发
 * 集成AudioFeatureExtractor提供更精确的特征分析
 */
class EnhancedWakewordDetector(
    private val originalDetector: SnowboyWakewordDetector,
    private val audioAnalyzer: AudioAnalyzer
) : WakewordDetector {
    // 状态管理
    private val _state = MutableStateFlow(WakewordDetector.DetectorState.IDLE)
    override val state: StateFlow<WakewordDetector.DetectorState> = _state.asStateFlow()

    // 配置参数 - 提高默认灵敏度
    private var sensitivity = 0.9f // 提高灵敏度默认值(原为0.8f)
    private var isInitialized = false

    // 防抖动控制 - 降低延迟
    private var lastDetectionTime = 0L
    private val debouncePeriodMs = 1500L // 减少防抖动时间(原为2000L)

    // 降低信心等级要求 - 更容易触发
    private var confidenceLevel = 0
    private val maxConfidenceLevel = 2 // 降低所需信心值(原为3)
    private val confidenceDecayTimeMs = 2000L // 增加信心值保持时间(原为1500L)
    private var lastConfidenceIncreaseTime = 0L

    // 降低模式匹配要求 - 更容易验证通过
    private var patternMatchCount = 0
    private val requiredPatternMatches = 1 // 降低连续匹配需求(原为2)

    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Default)

    // 回调处理
    private var detectionCallback: ((WakewordDetector.DetectionResult) -> Unit)? = null
    private var lastReportedResult = WakewordDetector.DetectionResult.NO_DETECTION

    // 添加音频特征提取器
    private val featureExtractor = AudioFeatureExtractor()

    // 特征阈值 - 放宽特征阈值要求
    private val highEnergyThreshold = 600.0  // 降低高能量阈值(原为1000.0)
    private val zcrLowThreshold = 0.05       // 降低过零率下限(原为0.1)
    private val zcrHighThreshold = 0.8       // 提高过零率上限(原为0.7)
    private val spectralFlatnessThreshold = 0.45 // 提高频谱平坦度阈值(原为0.3)
    private val highLowFreqRatioThreshold = 2.0 // 提高高低频比阈值(原为1.5)

    /**
     * 初始化检测器
     */
    override fun initialize(resourcePath: String, modelPath: String, sensitivity: Float): Boolean {
        this.sensitivity = sensitivity

        _state.value = WakewordDetector.DetectorState.INITIALIZING

        // 初始化原始检测器
        val success = originalDetector.initialize(resourcePath, modelPath, sensitivity)

        if (success) {
            isInitialized = true
            _state.value = WakewordDetector.DetectorState.IDLE

            // 设置原始检测器的回调，用于内部监听
            originalDetector.setDetectionCallback { result ->
                processOriginalDetectorResult(result)
            }

            // 启动监听
            scope.launch {
                originalDetector.state.collect { state ->
                    if (state == WakewordDetector.DetectorState.ERROR) {
                        _state.value = WakewordDetector.DetectorState.ERROR
                    }
                }
            }
        } else {
            _state.value = WakewordDetector.DetectorState.ERROR
        }

        return success
    }

    /**
     * 处理原始检测器结果
     */
    private fun processOriginalDetectorResult(result: WakewordDetector.DetectionResult) {
        // 防止重复处理同一结果
        if (result == lastReportedResult) return

        lastReportedResult = result

        if (result == WakewordDetector.DetectionResult.WAKEWORD_DETECTED) {
            // 触发增强型检测逻辑（虽然这里有重复，但是保证兼容性）
            val confirmedResult = confirmDetection()

            if (confirmedResult == WakewordDetector.DetectionResult.WAKEWORD_DETECTED) {
                _state.value = WakewordDetector.DetectorState.DETECTED
                detectionCallback?.invoke(confirmedResult)
            }
        } else {
            // 对于其他结果直接传递
            detectionCallback?.invoke(result)
        }
    }

    /**
     * 确认检测结果，减少误报 - 降低确认要求
     */
    private fun confirmDetection(): WakewordDetector.DetectionResult {
        val currentTime = Clock.System.now().toEpochMilliseconds()

        // 防抖动检查
        val isDebouncePeriodOver = (currentTime - lastDetectionTime) > debouncePeriodMs

        // 降低信心要求 - 放宽条件
        if (!isDebouncePeriodOver && confidenceLevel < maxConfidenceLevel - 1) {
            // 即使在防抖动期，如果有足够的信心值，也可以触发
            if (confidenceLevel >= maxConfidenceLevel / 2) {
                lastDetectionTime = currentTime
                confidenceLevel = 0
                patternMatchCount = 0
                return WakewordDetector.DetectionResult.WAKEWORD_DETECTED
            }
            return WakewordDetector.DetectionResult.NO_DETECTION
        }

        // 检查信心值衰减 - 延长信心值保持时间
        if (currentTime - lastConfidenceIncreaseTime > confidenceDecayTimeMs) {
            confidenceLevel = maxOf(0, confidenceLevel - 1)
            patternMatchCount = 0 // 重置模式匹配计数
        }

        // 满足条件，确认检测 - 降低确认要求
        if (isDebouncePeriodOver || confidenceLevel >= maxConfidenceLevel - 1) {
            lastDetectionTime = currentTime
            confidenceLevel = 0
            patternMatchCount = 0
            return WakewordDetector.DetectionResult.WAKEWORD_DETECTED
        }

        return WakewordDetector.DetectionResult.NO_DETECTION
    }

    /**
     * 检测唤醒词 - 提高匹配成功几率
     */
    override fun detect(audioData: ShortArray, frameCount: Int): WakewordDetector.DetectionResult {
        if (!isInitialized) {
            return WakewordDetector.DetectionResult.ERROR
        }

        if (_state.value != WakewordDetector.DetectorState.LISTENING) {
            _state.value = WakewordDetector.DetectorState.LISTENING
        }

        // 先进行音频预处理和分析
        val processedAudio = audioAnalyzer.applyNoiseGate(audioData)
        val hasValidVoice = audioAnalyzer.containsValidVoice(processedAudio, 0.7f) // 降低语音活动阈值

        // 使用特征提取器获取更详细的特征
        val features = featureExtractor.extractFeatures(processedAudio)

        // 提取关键特征
        val rmsEnergy = features["rms_energy"] ?: 0.0
        val zeroCrossingRate = features["zero_crossing_rate"] ?: 0.0
        val spectralFlatness = features["spectral_flatness"] ?: 1.0
        val highLowFreqRatio = features["high_low_freq_ratio"] ?: 1.0

        // 基础检测
        val baseResult = originalDetector.detect(processedAudio, frameCount)
        
        // 检查额外的特征模式是否符合唤醒词特征 - 放宽匹配条件
        val matchesWakewordFeatures = (
                rmsEnergy > highEnergyThreshold &&
                zeroCrossingRate in zcrLowThreshold..zcrHighThreshold &&
                (spectralFlatness < spectralFlatnessThreshold || highLowFreqRatio < highLowFreqRatioThreshold)
        )
        
        // 打印调试信息
        if (rmsEnergy > highEnergyThreshold && zeroCrossingRate in zcrLowThreshold..zcrHighThreshold) {
            println("[INFO] 检测到符合唤醒词特征的音频: ZCR=${zeroCrossingRate.format(2)}, RMS=${rmsEnergy.toInt()}")
        }
        
        if(listOf(WakewordDetector.DetectionResult.SILENCE,
                WakewordDetector.DetectionResult.NO_DETECTION
            ).contains(baseResult).not()){
            println("[INFO] 音频帧: ${baseResult.name}")
        }
        
        // 基于特征提取结果打印详细信息
        if (baseResult == WakewordDetector.DetectionResult.WAKEWORD_DETECTED) {
            println(
                "[INFO] 唤醒词检测 - 能量:${rmsEnergy.toInt()}, ZCR:${zeroCrossingRate.format(2)}, " +
                        "平坦度:${spectralFlatness.format(2)}, 高/低频比:${highLowFreqRatio.format(2)}"
            )
        }

        // 如果检测到唤醒词，进行额外验证
        if (baseResult == WakewordDetector.DetectionResult.WAKEWORD_DETECTED) {
            val currentTime = Clock.System.now().toEpochMilliseconds()

            // 检查上次检测时间，实现去抖动
            val timeSinceLastDetection = currentTime - lastDetectionTime

            // 检查信心值衰减
            if (currentTime - lastConfidenceIncreaseTime > confidenceDecayTimeMs) {
                confidenceLevel = maxOf(0, confidenceLevel - 1)
            }

            // 根据特征匹配程度增加信心值 - 增加信心值增长速度
            var confidenceIncrement = 1

            if (matchesWakewordFeatures) {
                confidenceIncrement = 2
                patternMatchCount++

                if (patternMatchCount >= requiredPatternMatches) {
                    confidenceIncrement = maxConfidenceLevel // 直接给予最大信心值
                }
            } else {
                patternMatchCount = 0
            }

            // 更新信心值
            confidenceLevel = minOf(maxConfidenceLevel, confidenceLevel + confidenceIncrement)
            lastConfidenceIncreaseTime = currentTime

            // 判断是否应该触发 - 降低触发阈值
            if (confidenceLevel >= maxConfidenceLevel - 1 ||
                (timeSinceLastDetection > debouncePeriodMs && confidenceLevel >= maxConfidenceLevel / 2)
            ) {
                // 重置状态
                confidenceLevel = 0
                patternMatchCount = 0
                lastDetectionTime = currentTime

                // 状态更新和回调
                _state.value = WakewordDetector.DetectorState.DETECTED
                detectionCallback?.invoke(WakewordDetector.DetectionResult.WAKEWORD_DETECTED)
                return WakewordDetector.DetectionResult.WAKEWORD_DETECTED
            }
        } else if (hasValidVoice && matchesWakewordFeatures) {
            // 即使基础检测未检测到，但特征非常匹配时，增加更多信心值
            val currentTime = Clock.System.now().toEpochMilliseconds()
            if (currentTime - lastConfidenceIncreaseTime > confidenceDecayTimeMs * 2) {
                confidenceLevel = 0
            }

            // 增加信心值增量
            confidenceLevel = minOf(maxConfidenceLevel - 1, confidenceLevel + 2)
            lastConfidenceIncreaseTime = currentTime
            
            // 如果特征非常匹配，有一定概率直接触发
            if (confidenceLevel >= maxConfidenceLevel - 1 && rmsEnergy > highEnergyThreshold * 1.5) {
                // 在特征极度匹配的情况下，即使没有基础检测结果也可能触发
                confidenceLevel = 0
                lastDetectionTime = currentTime
                _state.value = WakewordDetector.DetectorState.DETECTED
                detectionCallback?.invoke(WakewordDetector.DetectionResult.WAKEWORD_DETECTED)
                return WakewordDetector.DetectionResult.WAKEWORD_DETECTED
            }
        }

        return WakewordDetector.DetectionResult.NO_DETECTION
    }

    /**
     * 停止检测
     */
    override fun stopDetection() {
        originalDetector.stopDetection()
        _state.value = WakewordDetector.DetectorState.IDLE
        confidenceLevel = 0
        patternMatchCount = 0
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
        originalDetector.release()
        _state.value = WakewordDetector.DetectorState.IDLE
    }
}