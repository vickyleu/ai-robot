package snowboyPiper.impl

import com.airobot.device.yanapi.snowboyPiper.interfaces.AudioAnalyzer
import com.airobot.device.yanapi.snowboyPiper.interfaces.VoiceStateManager
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import snowboyPiper.interfaces.KeywordDetector

/**
 * 关键词检测器工厂类
 * 用于创建不同的关键词检测器实现
 */
object KeywordDetectorFactory {
    
    /**
     * 关键词检测器类型
     */
    enum class DetectorType {
        SNOWBOY,  // Snowboy关键词检测器
        VOSK      // Vosk关键词检测器
    }
    
    // 音频设备选择器
    private val deviceSelector = LinuxAudioDeviceSelector()
    
    /**
     * 创建关键词检测器
     * @param type 检测器类型
     * @param audioAnalyzer 音频分析器
     * @param voiceStateManager 语音状态管理器
     * @return 关键词检测器实例
     */
    fun createDetector(
        type: DetectorType,
        recognizer: VoskSpeechRecognizer,
        audioAnalyzer: AudioAnalyzer,
        voiceStateManager: VoiceStateManager
    ): KeywordDetector {
        // 预处理：检查是否为树莓派并修复ALSA配置
        if (deviceSelector.isRaspberryPi()) {
            println("[INFO] 检测到树莓派环境，准备修复ALSA配置")
            val fixed = deviceSelector.fixAlsaConfig()
            if (fixed) {
                println("[INFO] ALSA配置修复成功")
            } else {
                println("[WARN] ALSA配置修复失败，可能会影响音频质量")
            }
        }
        
        return when (type) {
            DetectorType.SNOWBOY -> {
                println("[INFO] 创建Snowboy关键词检测器")
                SnowboyKeywordDetector(audioAnalyzer, voiceStateManager)
            }
            DetectorType.VOSK -> {
                println("[INFO] 创建Vosk关键词检测器")
                
                // 创建Vosk关键词检测器
                val detector = VoskKeywordDetector(recognizer, audioAnalyzer, voiceStateManager)
                
                // 预添加一些常用关键词
                detector.addKeyword("嘿，机器人")
                detector.addKeyword("你好，机器人")
                detector.addKeyword("机器人")
                detector.addKeyword("小艾")
                detector.addKeyword("小爱")
                detector.addKeyword("小微")
                detector.addKeyword("智能助手")
                
                // 根据系统语言可以添加英文关键词
                detector.addKeyword("hey robot")
                detector.addKeyword("hello robot")
                detector.addKeyword("robot")
                detector.addKeyword("assistant")
                
                // 运行初始诊断
                println("[INFO] 运行初始系统诊断")
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.delay(2000) // 延迟2秒，确保系统初始化完成
                    detector.runDiagnostics()
                }
                
                // 创建诊断监控协程
                GlobalScope.launch {
                    while (true) {
                        kotlinx.coroutines.delay(300000) // 每5分钟运行一次诊断，而不是每分钟
                        println("[INFO] 运行周期性诊断检查")
                        detector.runDiagnostics()
                    }
                }
                
                detector
            }
        }
    }
    
    /**
     * 获取检测器类型名称
     * @param type 检测器类型
     * @return 类型名称
     */
    fun getTypeName(type: DetectorType): String {
        return when (type) {
            DetectorType.SNOWBOY -> "Snowboy"
            DetectorType.VOSK -> "Vosk"
        }
    }
    
    /**
     * 获取检测器类型的默认模型路径
     * @param type 检测器类型
     * @return 默认模型路径
     */
    fun getDefaultModelPath(type: DetectorType): String {
        return when (type) {
            DetectorType.SNOWBOY -> "/usr/local/share/snowboy/resources/models/snowboy.umdl"
            DetectorType.VOSK -> "/usr/local/share/vosk/model"
        }
    }
    
    /**
     * 获取推荐的检测器类型
     * 如果运行在树莓派上，推荐使用Vosk；否则使用Snowboy
     * @return 推荐的检测器类型
     */
    fun getRecommendedDetectorType(): DetectorType {
        return if (deviceSelector.isRaspberryPi()) {
            println("[INFO] 检测到树莓派环境，推荐使用Vosk关键词检测器")
            DetectorType.VOSK
        } else {
            println("[INFO] 非树莓派环境，使用Snowboy关键词检测器")
            DetectorType.SNOWBOY
        }
    }
} 