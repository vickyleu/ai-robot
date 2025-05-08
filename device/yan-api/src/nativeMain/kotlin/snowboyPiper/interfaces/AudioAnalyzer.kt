package com.airobot.device.yanapi.snowboyPiper.interfaces

/**
 * 音频分析器接口
 * 负责音频数据的分析和处理
 */
interface AudioAnalyzer {
    /**
     * 检测音频数据中是否含有语音活动
     * @param audioData 音频数据
     * @return 是否检测到语音活动
     */
    fun hasVoiceActivity(audioData: ShortArray): Boolean

    /**
     * 应用噪声门限，去除无用噪音
     * @param audioData 原始音频数据
     * @return 降噪后的音频数据
     */
    fun applyNoiseGate(audioData: ShortArray): ShortArray

    /**
     * 检查音频是否包含有效的语音信号
     * @param audioData 音频数据
     * @return 是否包含有效语音
     */
    fun containsValidVoice(audioData: ShortArray): Boolean

    /**
     * 通知分析器刚刚播放了音频，用于回声消除
     * @param audioData 播放的音频数据
     */
    fun notifyAudioPlayback(audioData: ShortArray)

    /**
     * 重置分析器状态
     */
    fun reset()
}