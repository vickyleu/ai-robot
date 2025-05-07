package com.airobot.device.yanapi.voice.interfaces

import kotlinx.coroutines.flow.Flow

/**
 * 音频播放器接口
 * 负责音频输出设备的初始化和播放
 */
interface AudioPlayer {
    /**
     * 播放器状态
     */
    enum class PlayerState {
        IDLE,       // 空闲状态
        INITIALIZING,// 初始化中
        PLAYING,    // 播放中
        PAUSED,     // 暂停
        STOPPED,    // 停止
        ERROR       // 错误
    }
    
    /**
     * 当前播放器状态
     */
    val state: Flow<PlayerState>
    
    /**
     * 初始化播放器
     * @param sampleRate 采样率
     * @param channels 通道数
     * @param deviceId 设备ID，默认为系统默认设备
     * @return 初始化是否成功
     */
    fun initialize(sampleRate: Int, channels: Int): Boolean
    
    /**
     * 播放音频数据
     * @param buffer 音频数据缓冲区
     * @param frameCount 帧数
     * @return 是否成功开始播放
     */
    fun playAudio(buffer: ShortArray, frameCount: Int): Boolean
    
    /**
     * 播放音频文件
     * @param filePath 音频文件路径
     * @return 是否成功开始播放
     */
    fun playAudioFile(filePath: String): Boolean
    
    /**
     * 停止播放
     */
    fun stopPlayback()
    
    /**
     * 暂停播放
     */
    fun pausePlayback()
    
    /**
     * 恢复播放
     */
    fun resumePlayback()
    
    /**
     * 设置音频播放完成回调
     * @param callback 回调函数，在播放完成时调用
     */
    fun setPlaybackCompletedCallback(callback: () -> Unit)
    
    /**
     * 释放资源
     */
    fun release()
} 