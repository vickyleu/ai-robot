@file:OptIn(ExperimentalTime::class)

package voice.core.config

import kotlinx.datetime.Clock
import voice.util.AudioDefaults
import kotlin.time.ExperimentalTime

/**
 * 语音助手配置类
 * 保存各种配置参数
 * 已针对Microsemi DAC做了特别优化
 * 
 * 🎯 语音识别触发策略：
 * - 说话时：累积音频数据
 * - 说完话（VAD检测到静音）时：如果累积≥800ms，触发识别
 * - 没说话：绝对不识别
 * - 安全保护：累积达到10秒时强制触发（防止内存溢出）
 */
data class VoiceAssistantConfig(
    // 关键词设置
    val keywords: List<String> = listOf("小度", "你好","在吗"),
    val keywordSensitivity: Float = 0.8f,  // 提高关键词敏感度到0.8
    
    // 音频配置 - 使用AudioDefaults预定义格式
    val inputFormat: AudioDefaults.AudioFormat = AudioDefaults.Formats.INPUT_DEVICE,
    val outputFormat: AudioDefaults.AudioFormat = AudioDefaults.Formats.OUTPUT_DEVICE,
    
    // 向后兼容的属性
    val sampleRate: Int = inputFormat.sampleRate,
    val channels: Int = inputFormat.channels,
    val outputSampleRate: Int = outputFormat.sampleRate,
    val outChannels: Int = outputFormat.channels,

    // 超时设置（毫秒）
    val keywordTimeout: Long = 10000L,
    val speechTimeout: Long = 5000L,
    
    // 各种阈值设置
    val minSpeechConfidence: Float = 0.6f,
    
    // 基本参数 - 使用立体声模式(2通道)以兼容Microsemi DAC
    val bufferSize: Int = AudioDefaults.AUDIO_BUFFER_SIZE, // 增大缓冲区提高稳定性，减少回调频率
    
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
    val debugMode: Boolean = false,

    // 回声消除设置 - 默认禁用以避免BlockFramer崩溃
    val enableEchoCancellation: Boolean = !AudioDefaults.ENABLE_ECHO_CANCELLATION_SAFE_MODE,  // 安全模式下禁用回声消除

    // 初始关键词
    val initialKeywords: List<String> = listOf("小度", "你好", "嗨", "在吗", "小兔子"),

    // 语音合成设置
    val piperModelPath2: String = "/usr/local/share/yanshee-model/piper/zh_CN-huayan-medium.onnx",
    val piperConfigPath2: String = "/usr/local/share/yanshee-model/piper/zh_CN-huayan-medium.onnx.json",
    val piperESpeakDataPath2: String = "/usr/local/share/yanshee-model/piper/espeak-ng-data",

    // 语音交互设置
    val useInternalResponse2: Boolean = true                     // 是否使用内部响应(否则调用回调)
)
