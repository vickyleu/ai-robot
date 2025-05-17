package voice.api.synthesis

/**
 * 语音合成器接口
 * 负责将文本转换为语音
 */
interface ISpeechSynthesizer {
    /**
     * 初始化语音合成器
     * @param modelPath 模型路径
     * @param configPath 配置文件路径
     * @param espeakDataPath espeak数据路径
     * @param speakerId 说话人ID
     * @return 初始化是否成功
     */
    fun initialize(
        modelPath: String,
        configPath: String,
        espeakDataPath: String,
        speakerId: Int = 0
    ): Boolean
    
    /**
     * 合成语音
     * @param text 要合成的文本
     * @param outputWav 是否输出wav格式(否则输出raw PCM)
     * @return 合成的音频数据，失败返回空数组
     */
    fun synthesize(text: String, outputWav: Boolean = false): ByteArray
    
    /**
     * 播放文本
     * @param text 要播放的文本
     * @return 播放是否成功
     */
    fun speak(text: String): Boolean
    
    /**
     * 停止播放
     */
    fun stopSpeaking()
    
    /**
     * 是否正在播放
     */
    fun isSpeaking(): Boolean
    
    /**
     * 释放资源
     */
    fun release()
} 