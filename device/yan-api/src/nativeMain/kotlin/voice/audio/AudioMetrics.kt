package voice.audio

/**
 * 音频指标类
 * 用于保存音频处理过程中的各种指标数据
 */
data class AudioMetrics(
    val rms: Double,                  // 均方根值 (Root Mean Square)
    val maxAmplitude: Int,            // 最大振幅
    val zeroCrossingRate: Int,        // 过零率
    val nonZeroRatio: Double,         // 非零样本比例
    val clippingRatio: Double         // 削波比例
)