package voice.core.config

/**
 * 语音助手配置类
 * 保存各种配置参数
 * 已针对Microsemi DAC做了特别优化
 */
data class VoiceAssistantConfig(
    // 关键词设置
    val keywords: List<String> = listOf("小度", "你好", "嗨", "在吗", "小兔子"),
    val keywordSensitivity: Float = 0.7f,
    
    // 语音识别设置
    val sampleRate: Int = 16000,
    val channels: Int = 1,
    
    // 超时设置（毫秒）
    val keywordTimeout: Long = 10000L,
    val speechTimeout: Long = 5000L,
    
    // 各种阈值设置
    val minSpeechConfidence: Float = 0.6f,
    
    // 基本参数 - 使用立体声模式(2通道)以兼容Microsemi DAC
    val bufferSize: Int = 1024, // 增大缓冲区提高稳定性
    
    // 检测器参数
    val sensitivity: Float = 0.65f, // 提高灵敏度
    val commandTimeout: Long = 8000, // 增加超时时间
    
    // 模型路径
    val voskModelPath: String = "/usr/local/share/yanshee-model/vosk/vosk-model-small-cn-0.22",
    val porcupineModelPath: String = "/usr/local/share/yanshee-model/porcupine", // Porcupine 模型路径
    val piperModelPath: String = "/usr/local/share/yanshee-model/piper/zh_CN-huayan-medium.onnx",
    val piperConfigPath: String = "/usr/local/share/yanshee-model/piper/zh_CN-huayan-medium.onnx.json",
    val piperESpeakDataPath: String = "/usr/local/share/yanshee-model/piper/espeak-ng-data",
    val useInternalResponse: Boolean = false,
    
    // 提示音文件路径
    val soundsPath: String = "/usr/local/share/yanshee-model/sounds",
    
    // 调试模式
    val debugMode: Boolean = false
)
