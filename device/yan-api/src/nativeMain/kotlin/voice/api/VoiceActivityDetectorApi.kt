package voice.api

import voice.audio.VADMetrics

/**
 * 语音活动检测器接口
 * 负责检测音频中是否包含人声
 */
interface VoiceActivityDetectorApi {
    /**
     * 检测结果
     */
    data class DetectionResult(
        val hasSpeech: Boolean,       // 是否检测到语音
        val confidence: Float,        // 置信度
        val metrics: VADMetrics       // 详细指标
    )
    
    /**
     * 检测音频是否包含语音
     * @param audio 音频数据
     * @param length 数据长度
     * @return 检测结果
     */
    fun detect(audio: ByteArray, length: Int): DetectionResult
    
    /**
     * 重置检测器状态
     */
    fun reset()
    
    /**
     * 设置敏感度
     * @param sensitivity 敏感度 (0.0-1.0)
     */
    fun setSensitivity(sensitivity: Float)
} 