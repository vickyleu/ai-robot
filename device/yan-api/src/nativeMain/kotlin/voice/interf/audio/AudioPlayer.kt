package voice.interf.audio

import kotlinx.coroutines.flow.StateFlow
import voice.hal.AudioDevice

/**
 * 音频播放器接口
 * 负责音频播放
 */
interface AudioPlayer {
    /**
     * 播放状态
     */
    enum class PlaybackState {
        IDLE,       // 空闲状态
        LOADING,    // 加载中
        PLAYING,    // 播放中
        PAUSED,     // 暂停
        ERROR       // 错误
    }
    
    /**
     * 当前播放状态
     */
    val playbackState: StateFlow<PlaybackState>
    
    /**
     * 初始化播放器
     * @param audioDevice 音频设备
     * @param deviceName 设备名称
     * @param sampleRate 采样率
     * @return 初始化是否成功
     */
    fun initialize(audioDevice: AudioDevice, deviceName: String = "default", sampleRate: Int = 16000): Boolean
    
    /**
     * 播放音频文件
     * @param filePath 文件路径
     * @return 是否成功开始播放
     */
    fun playAudio(filePath: String): Boolean
    
    /**
     * 播放音频缓冲区
     * @param buffer 音频数据
     * @return 是否成功开始播放
     */
    fun playAudio(buffer: ShortArray): Boolean
    
    /**
     * 停止播放
     */
    fun stopPlayback()
    
    /**
     * 释放资源
     */
    fun releasePlayer()
} 