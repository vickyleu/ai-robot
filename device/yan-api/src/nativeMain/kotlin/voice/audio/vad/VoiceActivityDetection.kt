package voice.audio.vad

import voice.audio.VADMetrics

/**
 * 语音活动检测接口
 * 负责检测音频流中的语音活动
 */
interface VoiceActivityDetection {
    /**
     * 检测音频数据中是否包含语音
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
     * 设置检测灵敏度
     * @param sensitivity 灵敏度 (0.0-1.0)
     */
    fun setSensitivity(sensitivity: Float)
    
    /**
     * 检测结果数据类
     */
    data class DetectionResult(
        val hasSpeech: Boolean,         // 是否检测到语音
        val confidence: Float,          // 置信度 (0.0-1.0)
        val metrics: VADMetrics         // VAD指标
    )
} 