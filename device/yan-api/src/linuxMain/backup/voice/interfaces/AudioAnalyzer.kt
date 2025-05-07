package com.airobot.device.yanapi.voice.interfaces

/**
 * 音频分析器接口
 * 负责分析音频特征和识别声音模式
 */
interface AudioAnalyzer {
    /**
     * 分析音频数据的特征
     * @param audioData 音频数据
     * @return 音频特征Map，包含能量、ZCR等特征值
     */
    fun analyzeFeatures(audioData: ShortArray): Map<String, Double>
    
    /**
     * 检测音频数据中是否有人声活动
     * @param audioData 音频数据
     * @return 是否检测到人声活动
     */
    fun hasVoiceActivity(audioData: ShortArray): Boolean
    
    /**
     * 应用噪声门限处理
     * 降低低能量噪声，保留有效声音信号
     * @param audioData 原始音频数据
     * @return 处理后的音频数据
     */
    fun applyNoiseGate(audioData: ShortArray): ShortArray
    
    /**
     * 检测音频数据是否包含有效人声
     * @param audioData 音频数据
     * @return 是否包含有效人声
     */
    fun containsValidVoice(audioData: ShortArray, thresholdFactor: Float=1f): Boolean
    
    /**
     * 通知分析器外部音频播放事件
     * 用于回声抑制等功能
     */
    fun notifyAudioPlayback()
    
    /**
     * 重置分析器状态
     */
    fun reset()
} 