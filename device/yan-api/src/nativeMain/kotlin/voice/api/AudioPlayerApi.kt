package voice.api

/**
 * 音频播放器接口
 * 负责播放音频数据
 */
interface AudioPlayerApi {
    /**
     * 初始化播放器
     * @param sampleRate 采样率
     * @param channels 通道数
     * @return 初始化是否成功
     */
    fun initialize(sampleRate: Int = 16000, channels: Int = 1): Boolean
    
    /**
     * 播放音频数据
     * @param audioData 音频数据
     * @param length 数据长度
     * @return 播放是否成功
     */
    fun play(audioData: ByteArray, length: Int): Boolean
    
    /**
     * 异步播放音频数据
     * @param audioData 音频数据
     * @param length 数据长度
     * @param onComplete 播放完成回调
     * @return 播放是否成功启动
     */
    fun playAsync(audioData: ByteArray, length: Int, onComplete: () -> Unit = {}): Boolean
    
    /**
     * 停止播放
     */
    fun stop()
    
    /**
     * 是否正在播放
     */
    fun isPlaying(): Boolean
    
    /**
     * 暂停播放
     */
    fun pause()
    
    /**
     * 恢复播放
     */
    fun resume()
    
    /**
     * 释放资源
     */
    fun release()
} 