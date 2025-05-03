package com.airobot.device.yanapi.snowboyPiper.config

data class VoiceAssistantConfig(
    // 音频设置
    val sampleRate: Int = 48000,
    val channels: Int = 1,
    val bufferSize: Int = 16384,

    // 模型路径
    val resourcePath: String = "/usr/local/share/yanshee-model/snowboy/common.res",
    val modelPath: String = "/usr/local/share/yanshee-model/snowboy/models/xiaodu.pmdl",
    val piperModelPath: String = "/usr/local/share/yanshee-model/piper/zh_CN-huayan-x_low.onnx",
    val piperConfigPath: String = "/usr/local/share/yanshee-model/piper/zh_CN-huayan-x_low.onnx.json",
    val voskModelPath: String = "/usr/local/share/yanshee-model/vosk/vosk-model-small-cn-0.22",
    val piperESpeakDataPath: String = "/usr/local/share/yanshee-model/piper/espeak-ng-data",

    // 语音处理参数
    val speechRecognitionTimeoutMs: Long = 8000L,
    val accumulationThreshold: Int = 16000 * 4, // 约4秒的16kHz音频
    val overlapSize: Int = 16000, // 保留约1秒的音频用于连续检测

    // VAD参数
    val energyThreshold: Double = 300.0,// 能量阈值，使检测更敏感
    val noiseGateThreshold: Double = 100.0,
    val validVoiceRmsThreshold: Double = 150.0,// 有效语音RMS阈值
    val validVoiceZcrThreshold: Double = 0.5,// 有效语音RMS阈值
    val silenceFramesThreshold: Int = 45,

    // 时间控制
    val mainLoopDelayMs: Long = 100L,
    val keywordDetectionIntervalMs: Long = 3000L,
    val commandProcessingIntervalMs: Long = 1000L,
    val postSilenceWaitTimeMs: Long = 1500L,
    val errorLogIntervalMs: Long = 5000L
)