package snowboyPiper.config

data class VoiceAssistantConfig(
    // 音频设置
    val sampleRate: Int = 48000,
    val channels: Int = 1,
    val bufferSize: Int = 16384,

    // 模型路径
    val resourcePath: String = "/usr/local/share/yanshee-model/snowboy/common.res",
//    val modelPath: String = "/usr/local/share/yanshee-model/snowboy/models/xiaodu.pmdl",
    val modelPath: String = "/usr/local/share/yanshee-model/snowboy/models/yiliduo.pmdl",
//    val modelPath: String = "/usr/local/share/yanshee-model/snowboy/models/snowboy.umdl",
    val piperModelPath: String = "/usr/local/share/yanshee-model/piper/zh_CN-huayan-medium.onnx",
    val piperConfigPath: String = "/usr/local/share/yanshee-model/piper/zh_CN-huayan-medium.onnx.json",

    val voskModelPath: String = "/usr/local/share/yanshee-model/vosk/vosk-model-small-cn-0.22",
    val piperESpeakDataPath: String = "/usr/local/share/yanshee-model/piper/espeak-ng-data",

    // 语音处理参数
    val speechRecognitionTimeoutMs: Long = 8000L,
    val accumulationThreshold: Int = 16000 * 2, // 从3秒降低到2秒的16kHz音频，加快响应
    val overlapSize: Int = 8000, // 从16000降低到8000，减小缓冲区大小
    val snowboySensitivity: Float = 0.9f, // 进一步提高灵敏度，从0.75f提高到0.9f
    // VAD参数 - 进一步降低阈值提高对语音的敏感性
    val energyThreshold: Double = 300.0, // 从500.0降低到300.0
    val noiseGateThreshold: Double = 200.0, // 从300.0降低到200.0
    val validVoiceRmsThreshold: Double = 250.0, // 从450.0降低到250.0
    val validVoiceZcrThreshold: Double = 0.2, // 从0.25降低到0.2

    val silenceFramesThreshold: Int = 40, // 从60降低到40

    // 时间控制
    val mainLoopDelayMs: Long = 50L, // 从100L降低到50L，提高检测频率
    val keywordDetectionIntervalMs: Long = 1000L, // 从1500L降低到1000L，更快地尝试检测
    val commandProcessingIntervalMs: Long = 800L, // 从1000L降低到800L
    val postSilenceWaitTimeMs: Long = 1200L, // 从1500L降低到1200L
    val errorLogIntervalMs: Long = 5000L
){
    companion object{
        // 默认配置
        val DEFAULT = VoiceAssistantConfig()
    }
}
