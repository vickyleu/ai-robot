package com.airobot.device.yanapi.voice.interfaces

import kotlinx.coroutines.flow.Flow

/**
 * 语音识别接口
 * 负责将音频转换为文本
 */
interface SpeechRecognizer {
    /**
     * 识别器状态
     */
    enum class RecognizerState {
        IDLE,        // 空闲状态
        INITIALIZING,// 初始化中
        LISTENING,   // 监听中
        PROCESSING,  // 处理中
        FINISHED,    // 识别完成
        ERROR        // 错误
    }
    
    /**
     * 识别结果
     */
    data class RecognitionResult(
        val text: String,              // 识别文本
        val confidence: Float,         // 置信度
        val isPartial: Boolean = false // 是否为部分结果
    )
    
    /**
     * 当前识别器状态
     */
    val state: Flow<RecognizerState>
    
    /**
     * 识别结果流
     */
    val results: Flow<RecognitionResult>
    
    /**
     * 初始化识别器
     * @param modelPath 模型文件路径
     * @param language 语言代码，如"zh-CN"
     * @return 初始化是否成功
     */
    fun initialize(modelPath: String, language: String = "zh-CN"): Boolean
    
    /**
     * 开始识别
     * @return 是否成功开始识别
     */
    fun startRecognition(): Boolean
    
    /**
     * 处理音频数据
     * @param audioData 音频数据
     * @param frameCount 帧数
     */
    fun processAudio(audioData: ShortArray, frameCount: Int)
    
    /**
     * 停止识别
     * @return 最终识别结果
     */
    suspend fun stopRecognition(): RecognitionResult?
    
    /**
     * 取消识别
     */
    fun cancelRecognition()
    
    /**
     * 释放资源
     */
    fun release()
} 