package voice.audio

/**
 * 语音识别指标类
 * 用于保存语音识别过程中的各种性能和诊断数据
 */
data class RecognitionMetrics(
    val processingTimeMs: Long,     // 处理时间（毫秒）
    val confidenceScore: Float,     // 置信分数
    val errorCode: Int,             // 错误代码（0表示无错误）
    val errorMessage: String,        // 错误消息
    val timestamp: Long
)