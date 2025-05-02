@file:OptIn(ExperimentalForeignApi::class)

package snowboyPiper.interfaces

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ShortVar
import kotlinx.coroutines.flow.StateFlow
import snowboyPiper.impl.PortAudioDevice

/**
 * 音频播放器接口
 * 负责播放音频文件
 */
interface AudioPlayer {
    /**
     * 播放状态
     */
    enum class PlaybackState {
        IDLE,           // 空闲状态
        INITIALIZING,   // 初始化中
        PLAYING,        // 播放中
        ERROR           // 错误状态
    }
    
    /**
     * 当前播放状态
     */
    val playbackState: StateFlow<PlaybackState>
    
    /**
     * 初始化音频播放器
     * @param deviceName 设备名称
     * @param sampleRate 采样率
     * @return 初始化是否成功
     */
    fun initialize(audioRecordDevice: AudioDevice,deviceName: String = "default", sampleRate: Int = 48000): Boolean
    
    /**
     * 播放音频文件
     * @param filePath 音频文件路径
     * @return 是否成功开始播放
     */
    fun playAudio(filePath: String): Boolean
    /**
     * 播放音频数据
     * @param buffer 数据缓冲区
     * @param frameCount 帧数
     * @return 播放的帧数，负值表示错误
     */
    fun playAudio(buffer: CPointer<ShortVar>, frameCount: Int): Int
    /**
     * 停止播放
     */
    fun stopPlayback()
    
    /**
     * 释放资源
     */
    fun release()
}