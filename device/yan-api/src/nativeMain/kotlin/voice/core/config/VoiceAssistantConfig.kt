package voice.core.config

/**
 * 语音助手配置类
 * 保存各种配置参数
 */
data class VoiceAssistantConfig(
    // 基本参数
    val sampleRate: Int = 16000,
    val channels: Int = 1,
    val bufferSize: Int = 320,
    
    // 检测器参数
    val sensitivity: Float = 0.5f,
    val commandTimeout: Long = 5000,
    
    // 模型路径
    val voskModelPath: String = "/usr/local/share/yanshee-model/vosk/vosk-model-small-cn-0.22",
    val piperModelPath: String = "/usr/local/share/yanshee-model/piper/zh_CN-huayan-medium.onnx",
    val piperConfigPath: String = "/usr/local/share/yanshee-model/piper/zh_CN-huayan-medium.onnx.json",
    val piperESpeakDataPath: String = "/usr/local/share/yanshee-model/piper/espeak-ng-data"
)
