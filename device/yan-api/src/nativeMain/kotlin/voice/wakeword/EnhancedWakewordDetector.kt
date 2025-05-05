@file:OptIn(ExperimentalTime::class, ExperimentalTime::class, ExperimentalTime::class)

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
    private var sensitivity = 1.0f // 设置为最大灵敏度
    private var isInitialized = false

    // 防抖动控制 - 降低延迟
    private var lastDetectionTime = 0L
    private val debouncePeriodMs = 300L // 进一步减少防抖动时间（从500ms减少到300ms）
    
    // 连续声音帧计数
    private var continuousVoiceFrames = 0
    private val minContinuousFrames = 1 // 最小连续帧（降到最低）

    // 信心等级 - 极大降低要求，更快触发
    private var confidenceLevel = 0.0
    private val maxConfidenceLevel = 1 // 只需要一次信心值
    private val confidenceDecayTimeMs = 10000L // 极大增加信心值保持时间（从5000ms增加到10000ms）
    private var lastConfidenceIncreaseTime = 0L

    // 模式匹配要求 - 只要有一丝符合特征就可能触发
    private var patternMatchCount = 0
    private val requiredPatternMatches = 1 // 只需一次匹配

    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Default)

    // 回调处理
    private var detectionCallback: ((WakewordDetector.DetectionResult) -> Unit)? = null
    private var lastReportedResult = WakewordDetector.DetectionResult.NO_DETECTION

    // 音频特征提取器
    private val featureExtractor = AudioFeatureExtractor()

    // 特征阈值 - 极大放宽特征阈值要求
    private val highEnergyThreshold = 150.0  // 进一步降低能量阈值（从200降到150）
    private val zcrLowThreshold = 0.0       // 降低过零率下限至0
    private val zcrHighThreshold = 1.0      // 提高过零率上限至最大
    private val spectralFlatnessThreshold = 0.9 // 进一步提高平坦度阈值
    private val highLowFreqRatioThreshold = 10.0 // 提高高低频比阈值
    
    // 连续检测历史 - 消除连续检测要求
    private var consecutiveDetections = 0
    private val requiredConsecutiveDetections = 0 // 不需要连续检测，立即触发
    
    // 保存最近的音频帧用于模式匹配
    private val recentFrames = Array(3) { ShortArray(0) }
    private var frameIndex = 0

    /**
     * 初始化检测器
     */
    override fun initialize(resourcePath: String, modelPath: String, sensitivity: Float): Boolean {
        // 始终使用最高灵敏度，忽略传入的参数
        this.sensitivity = 1.0f

        _state.value = WakewordDetector.DetectorState.INITIALIZING

        // 使用最高灵敏度初始化原始检测器
        val success = originalDetector.initialize(resourcePath, modelPath, 1.0f)

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
            
            println("[INFO] 增强型唤醒词检测器已初始化，灵敏度设为最高")
        } else {
            _state.value = WakewordDetector.DetectorState.ERROR
        }

        return success
    }

    /**
     * 处理原始检测器结果
     */
    private fun processOriginalDetectorResult(result: WakewordDetector.DetectionResult) {
        // 如果原始检测器检测到唤醒词，直接确认
        if (result == WakewordDetector.DetectionResult.WAKEWORD_DETECTED) {
            println("[INFO] 原始检测器报告检测到唤醒词")
            val confirmationResult = confirmDetection()
            
            if (confirmationResult == WakewordDetector.DetectionResult.WAKEWORD_DETECTED &&
                lastReportedResult != WakewordDetector.DetectionResult.WAKEWORD_DETECTED) {
                
                lastReportedResult = WakewordDetector.DetectionResult.WAKEWORD_DETECTED
                _state.value = WakewordDetector.DetectorState.DETECTED
                
                // 触发回调
                detectionCallback?.invoke(confirmationResult)
            }
        } else {
            // 重置上次报告的结果，以便下次检测
            lastReportedResult = result
        }
    }

    /**
     * 确认唤醒词检测 - 大幅放宽确认条件
     */
    private fun confirmDetection(): WakewordDetector.DetectionResult {
        val currentTime = Clock.System.now().toEpochMilliseconds()

        // 防抖动检查
        val isDebouncePeriodOver = (currentTime - lastDetectionTime) > debouncePeriodMs

        // 极大放宽确认条件，几乎总是返回检测成功
        // 在防抖动期内，只要有最低信心值即可触发
        if (!isDebouncePeriodOver) {
            if (confidenceLevel > 0) {
                println("[确认] 在防抖动期内检测到可能的唤醒词，提高灵敏度立即确认")
                lastDetectionTime = currentTime
                confidenceLevel = 0.0
                patternMatchCount = 0
                return WakewordDetector.DetectionResult.WAKEWORD_DETECTED
            }
            return WakewordDetector.DetectionResult.NO_DETECTION
        }

        // 延长信心值保持时间
        if (currentTime - lastConfidenceIncreaseTime > confidenceDecayTimeMs) {
            confidenceLevel = 0.0
            patternMatchCount = 0
        }

        // 只要过了防抖动期，就直接确认
        println("[确认] 过了防抖动期，立即确认唤醒词检测")
        lastDetectionTime = currentTime
        confidenceLevel = 0.0
        patternMatchCount = 0
        return WakewordDetector.DetectionResult.WAKEWORD_DETECTED
    }

    /**
     * 检测唤醒词 - 极大提高触发率
     */
    override fun detect(audioData: ShortArray, frameCount: Int): WakewordDetector.DetectionResult {
        if (!isInitialized) {
            return WakewordDetector.DetectionResult.ERROR
        }

        if (_state.value != WakewordDetector.DetectorState.LISTENING) {
            _state.value = WakewordDetector.DetectorState.LISTENING
        }

        // 保存最近的音频帧
        recentFrames[frameIndex] = audioData.copyOf()
        frameIndex = (frameIndex + 1) % recentFrames.size

        // 音频预处理和分析
        val processedAudio = audioAnalyzer.applyNoiseGate(audioData)
        // 使用更低的阈值检测语音活动
        val hasValidVoice = audioAnalyzer.containsValidVoice(processedAudio, 0.3f) // 降低阈值（从0.4降到0.3）

        // 使用特征提取器获取更详细的特征
        val features = featureExtractor.extractFeatures(processedAudio)

        // 提取关键特征
        val rmsEnergy = features["rms_energy"] ?: 0.0
        val zeroCrossingRate = features["zero_crossing_rate"] ?: 0.0
        val spectralFlatness = features["spectral_flatness"] ?: 1.0
        val highLowFreqRatio = features["high_low_freq_ratio"] ?: 1.0

        // 更新连续语音帧计数
        if (rmsEnergy > 100.0) {
            continuousVoiceFrames++
            // 定期打印连续帧信息，辅助调试
            if (continuousVoiceFrames % 2 == 0) {
                println("[DEBUG] 检测到语音活动，能量: $rmsEnergy, 连续帧: $continuousVoiceFrames")
            }
        } else {
            // 缓慢减少连续帧计数，保持灵敏度
            if (continuousVoiceFrames > 0) continuousVoiceFrames--
        }

        // 使用原始检测器进行基础检测
        val baseResult = originalDetector.detect(processedAudio, frameCount)
        
        // 极大放宽特征匹配条件，几乎任何语音都可能匹配
        val matchesWakewordFeatures = (
                rmsEnergy > highEnergyThreshold || // 任何能量高于阈值的信号
                (zeroCrossingRate in zcrLowThreshold..zcrHighThreshold && rmsEnergy > 80.0) || // 任何有一定能量的信号
                (continuousVoiceFrames > minContinuousFrames && rmsEnergy > 80.0) // 连续检测到声音
        )
        
        // 只要有声音，就打印详细特征信息，帮助调试
        if (rmsEnergy > 80.0) {
            println("[DEBUG] 声音特征分析: 能量=${rmsEnergy.toInt()}, ZCR=${zeroCrossingRate.format(2)}, 平坦度=${spectralFlatness.format(2)}, 高低频比=${highLowFreqRatio.format(2)}")
        }
        
        // 基于特征提取结果打印详细信息
        if (baseResult == WakewordDetector.DetectionResult.WAKEWORD_DETECTED) {
            println(
                "[INFO] 唤醒词被检测到! 能量:${rmsEnergy.toInt()}, ZCR:${zeroCrossingRate.format(2)}, " +
                        "平坦度:${spectralFlatness.format(2)}, 高/低频比:${highLowFreqRatio.format(2)}"
            )
        }

        // 处理检测结果
        val currentTime = Clock.System.now().toEpochMilliseconds()
        
        // 情况1: 原始检测器检测到唤醒词
        if (baseResult == WakewordDetector.DetectionResult.WAKEWORD_DETECTED) {
            // 增加连续检测计数
            consecutiveDetections++
            
            // 增加信心等级到最大
            confidenceLevel = maxConfidenceLevel.toDouble()
            patternMatchCount = requiredPatternMatches
            lastConfidenceIncreaseTime = currentTime
            
            // 不需要等待连续检测，立即触发
            println("[WORKFLOW] 唤醒词检测成功！时间戳: $currentTime")
            // 状态更新和回调
            _state.value = WakewordDetector.DetectorState.DETECTED
            detectionCallback?.invoke(WakewordDetector.DetectionResult.WAKEWORD_DETECTED)
            return WakewordDetector.DetectionResult.WAKEWORD_DETECTED
        }
        // 情况2: 原始检测器未检测到，但特征匹配且有语音活动，也视为可能的唤醒词
        else if (matchesWakewordFeatures && hasValidVoice) {
            // 增加信心等级
            confidenceLevel++
            lastConfidenceIncreaseTime = currentTime
            
            // 防抖动检查
            if ((currentTime - lastDetectionTime) > debouncePeriodMs) {
                // 超过防抖动期，能量满足要求，视为检测到唤醒词
                if (rmsEnergy > highEnergyThreshold || continuousVoiceFrames > minContinuousFrames) {
                    println("[INFO] 基于连续语音活动检测到唤醒词，能量: $rmsEnergy")
                    confidenceLevel = 0.0
                    lastDetectionTime = currentTime
                    _state.value = WakewordDetector.DetectorState.DETECTED
                    detectionCallback?.invoke(WakewordDetector.DetectionResult.WAKEWORD_DETECTED)
                    return WakewordDetector.DetectionResult.WAKEWORD_DETECTED
                }
            }
        } 
        // 情况3: 有较强的语音活动但不完全匹配特征
        else if (hasValidVoice || rmsEnergy > 200) {
            // 对于高能量的语音信号，增加一点信心值
            if (confidenceLevel < maxConfidenceLevel) {
                confidenceLevel += 0.5
                lastConfidenceIncreaseTime = currentTime
            }
        }
        // 情况4: 无语音活动或不符合特征，重置状态
        else {
            // 连续检测计数缓慢减少
            if (consecutiveDetections > 0) {
                consecutiveDetections--
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
        confidenceLevel = 0.0
        patternMatchCount = 0
        consecutiveDetections = 0
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