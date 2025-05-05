package com.airobot.device.yanapi.voice.interfaces

import kotlinx.coroutines.flow.Flow

/**
 * 语音合成接口
 * 负责将文本转换为音频
 */
interface SpeechSynthesizer {
    /**
     * 合成器状态
     */
    enum class SynthesizerState {
        IDLE,        // 空闲状态
        INITIALIZING,// 初始化中
        SYNTHESIZING,// 合成中
        FINISHED,    // 合成完成
        ERROR        // 错误
    }
    
    /**
     * 当前合成器状态
     */
    val state: Flow<SynthesizerState>
    
    /**
     * 初始化合成器
     * @param modelPath 模型文件路径
     * @param configPath 配置文件路径
     * @param language 语言代码，如"zh-CN"
     * @return 初始化是否成功
     */
    fun initialize(modelPath: String, configPath: String, language: String = "zh-CN"): Boolean
    
    /**
     * 合成语音
     * @param text 要合成的文本
     * @param voice 声音ID或名称
     * @return 合成的音频数据，失败返回null
     */
    suspend fun synthesize(text: String, voice: String = "default"): ShortArray?
    
    /**
     * 合成并直接播放
     * @param text 要合成的文本
     * @param voice 声音ID或名称
     * @return 是否成功开始播放
     */
    suspend fun speak(text: String, voice: String = "default"): Boolean
    
    /**
     * 停止合成
     */
    fun stopSynthesis()
    
    /**
     * 设置语速
     * @param speed 语速，1.0为正常速度
     */
    fun setSpeed(speed: Float)
    
    /**
     * 设置音量
     * @param volume 音量，范围0.0-1.0
     */
    fun setVolume(volume: Float)
    
    /**
     * 设置音调
     * @param pitch 音调，1.0为正常音调
     */
    fun setPitch(pitch: Float)
    
    /**
     * 释放资源
     */
    fun release()
} 