package com.airobot.device.yanapi.snowboyPiper.interfaces


interface AudioAnalyzer {
    /**
     * 检测是否有语音活动
     * @param audioData 音频数据
     * @return 是否有语音活动
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
}