package com.airobot.device.yanapi.voice.core.config

// 在配置中添加性能相关参数
data class PerformanceConfig(
    val enableAdaptiveQuality: Boolean = true,
    val maxProcessingLatency: Long = 50, // ms
    val enableAudioCaching: Boolean = true,
    val cacheSize: Int = 5, // 缓存最近5个音频片段
    val enableParallelProcessing: Boolean = false, // 树莓派上建议关闭
    val cpuSaverMode: Boolean = true // CPU节能模式
)