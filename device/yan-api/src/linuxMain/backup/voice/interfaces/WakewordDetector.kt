package com.airobot.device.yanapi.voice.interfaces

import kotlinx.coroutines.flow.Flow

/**
 * 唤醒词检测器接口
 * 负责检测音频流中的唤醒词
 */
interface WakewordDetector {
    /**
     * 检测器状态
     */
    enum class DetectorState {
        IDLE,        // 空闲状态
        INITIALIZING,// 初始化中
        LISTENING,   // 监听中
        DETECTED,    // 已检测到唤醒词
        ERROR        // 错误
    }
    
    /**
     * 检测结果
     */
    enum class DetectionResult(val value: Int) {
        SILENCE(-2),         // 静音
        ERROR(-1),           // 错误
        NO_DETECTION(0),     // 未检测到
        WAKEWORD_DETECTED(1) // 检测到唤醒词
    }
    
    /**
     * 当前检测器状态
     */
    val state: Flow<DetectorState>
    
    /**
     * 初始化检测器
     * @param resourcePath 资源文件路径
     * @param modelPath 模型文件路径
     * @param sensitivity 灵敏度，范围0-1
     * @return 初始化是否成功
     */
    fun initialize(resourcePath: String, modelPath: String, sensitivity: Float): Boolean
    
    /**
     * 检测唤醒词
     * @param audioData 音频数据
     * @param frameCount 帧数
     * @return 检测结果
     */
    fun detect(audioData: ShortArray, frameCount: Int): DetectionResult
    
    /**
     * 停止检测
     */
    fun stopDetection()
    
    /**
     * 设置检测回调
     * @param callback 回调函数，在检测到唤醒词时调用
     */
    fun setDetectionCallback(callback: (DetectionResult) -> Unit)
    
    /**
     * 释放资源
     */
    fun release()
} 