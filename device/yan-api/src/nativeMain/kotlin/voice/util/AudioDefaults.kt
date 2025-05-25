package voice.util

/**
 * 全局统一的音频默认值，避免各模块各写各的。
 */
object AudioDefaults {

    /** 上层算法（VAD/ASR 等）期望的采样率 */
    const val TARGET_SAMPLE_RATE = 16_000

    /** 系统默认通道数（Microsemi 强制立体声） */
    const val CHANNELS = 1
} 