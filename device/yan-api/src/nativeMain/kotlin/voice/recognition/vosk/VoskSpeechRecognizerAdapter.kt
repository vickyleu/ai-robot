package voice.recognition.vosk

import voice.api.recognition.ISpeechRecognizer
import voice.audio.recognition.VoskSpeechRecognizer
import voice.util.LogManager

/**
 * 适配器类，将VoskSpeechRecognizer包装为ISpeechRecognizer接口
 * 用于解决接口重构过程中的兼容性问题
 */
class VoskSpeechRecognizerAdapter : ISpeechRecognizer {
    private val logger = LogManager.getLogger("VoskSpeechRecognizerAdapter")
    private val recognizer = VoskSpeechRecognizer()
    
    /**
     * 初始化语音识别器
     * @param modelPath 模型路径
     * @return 初始化是否成功
     */
    override fun initialize(modelPath: String): Boolean {
        logger.info("通过适配器初始化Vosk识别器")
        return recognizer.initialize(modelPath)
    }
    
    /**
     * 识别音频
     * @param audio 音频数据
     * @param length 数据长度
     * @param timestamp 时间戳
     * @return 识别结果
     */
    override fun recognize(audio: ByteArray, length: Int, timestamp: Long): ISpeechRecognizer.RecognitionResult {
        // 调用实际的识别器
        val result = recognizer.recognize(audio, length)
        
        // 转换结果格式
        return ISpeechRecognizer.RecognitionResult(
            success = result.success,
            text = result.text,
            isPartial = result.isPartial,
            confidence = result.metrics.confidenceScore,
            errorCode = result.metrics.errorCode,
            errorMessage = result.metrics.errorMessage
        )
    }
    
    /**
     * 更新关键词
     * @param keywords 关键词列表，逗号分隔
     * @return 更新是否成功
     */
    override fun updateKeywords(keywords: String): Boolean {
        return recognizer.updateKeywords(keywords)
    }
    
    /**
     * 重置识别器状态
     */
    override fun reset() {
        recognizer.reset()
    }
    
    /**
     * 释放资源
     */
    override fun release() {
        recognizer.release()
    }
} 