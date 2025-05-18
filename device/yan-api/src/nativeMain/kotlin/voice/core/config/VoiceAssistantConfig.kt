package voice.core.config

/**
 * 语音助手配置类
 * 保存各种配置参数
 * 已针对Microsemi DAC做了特别优化
 */
data class VoiceAssistantConfig(
    // 基本参数 - 使用立体声模式(2通道)以兼容Microsemi DAC
    val sampleRate: Int = 16000,
    val channels: Int = 2,  // 立体声模式，必须为2以支持Microsemi DAC
    val bufferSize: Int = 1024, // 增大缓冲区提高稳定性
    
    // 检测器参数
    val sensitivity: Float = 0.65f, // 提高灵敏度
    val commandTimeout: Long = 8000, // 增加超时时间
    
    // 模型路径
    val voskModelPath: String = "/usr/local/share/yanshee-model/vosk/vosk-model-small-cn-0.22",
    val piperModelPath: String = "/usr/local/share/yanshee-model/piper/zh_CN-huayan-medium.onnx",
    val piperConfigPath: String = "/usr/local/share/yanshee-model/piper/zh_CN-huayan-medium.onnx.json",
    val piperESpeakDataPath: String = "/usr/local/share/yanshee-model/piper/espeak-ng-data"
)
