package voice.api

/**
 * 语音识别器接口
 * 负责将音频转换为文本
 */
interface SpeechRecognizerApi {
    /**
     * 识别结果
     */
    data class RecognitionResult(
        val success: Boolean,        // 是否成功
        val text: String,            // 识别文本
        val isPartial: Boolean,      // 是否为部分结果
        val confidence: Float,       // 置信度
        val errorCode: Int = 0,      // 错误码
        val errorMessage: String = "" // 错误信息
    )
    
    /**
     * 初始化语音识别器
     * @param modelPath 模型路径
     * @return 初始化是否成功
     */
    fun initialize(modelPath: String): Boolean
    
    /**
     * 识别音频
     * @param audio 音频数据
     * @param length 数据长度
     * @param timestamp 时间戳
     * @return 识别结果
     */
    fun recognize(audio: ByteArray, length: Int, timestamp: Long = 0): RecognitionResult
    
    /**
     * 更新关键词
     * @param keywords 关键词列表，逗号分隔
     * @return 更新是否成功
     */
    fun updateKeywords(keywords: String): Boolean
    
    /**
     * 重置识别器状态
     */
    fun reset()
    
    /**
     * 释放资源
     */
    fun release()
} 