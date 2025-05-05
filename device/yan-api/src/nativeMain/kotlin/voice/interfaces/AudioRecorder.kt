package com.airobot.device.yanapi.voice.interfaces

import kotlinx.coroutines.flow.Flow

/**
 * 音频录制器接口
 * 负责音频输入设备的初始化和录制
 */
interface AudioRecorder {
    /**
     * 录制器状态
     */
    enum class RecorderState {
        IDLE,        // 空闲状态
        INITIALIZING,// 初始化中
        RECORDING,   // 录制中
        PAUSED,      // 暂停
        ERROR        // 错误
    }
    
    /**
     * 当前录制器状态
     */
    val state: Flow<RecorderState>
    
    /**
     * 初始化录制器
     * @param sampleRate 采样率
     * @param channels 通道数
     * @param deviceId 设备ID，默认为系统默认设备
     * @return 初始化是否成功
     */
    fun initialize(sampleRate: Int, channels: Int): Boolean
    
    /**
     * 开始录制
     * @return 是否成功开始录制
     */
    fun startRecording(): Boolean
    
    /**
     * 停止录制
     */
    fun stopRecording()
    
    /**
     * 暂停录制
     */
    fun pauseRecording()
    
    /**
     * 恢复录制
     */
    fun resumeRecording()
    
    /**
     * 设置音频处理回调
     * @param callback 回调函数，接收录制的音频数据
     */
    fun setAudioCallback(callback: (ShortArray, Int) -> Unit)
    
    /**
     * 释放资源
     */
    fun release()
} 