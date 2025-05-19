package voice.audio

/**
 * 语音活动检测（VAD）指标类
 * 用于保存VAD过程中的各种性能和诊断数据
 */
data class VADMetrics(
    val energy: Float,               // 音频能量
    val noiseFloor: Float,           // 噪声基准值
    val signalToNoiseRatio: Float,   // 信噪比
    val speechProbability: Float,    // 语音概率
    val hasVoice: Boolean            // 是否检测到人声
)