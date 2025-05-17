package voice.audio

/**
 * 语音活动检测（VAD）指标类
 * 用于保存VAD过程中的各种性能和诊断数据
 */
data class VADMetrics(
    val energyLevel: Double,        // 能量水平
    val speechProbability: Float,   // 语音概率
    val noiseLevel: Double          // 噪声基准水平
) 