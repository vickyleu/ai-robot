package com.airobot.device.yanapi.snowboyPiper.interfaces


interface VoiceStateManager {
    /**
     * 当前是否在说话
     */
    val isSpeaking: Boolean

    /**
     * 语音是否已经开始
     */
    val speechStarted: Boolean

    /**
     * 语音缓冲是否已开始
     */
    val speechBufferStarted: Boolean

    /**
     * 连续静音帧计数
     */
    val silenceFrames: Int

    /**
     * 上次检测到语音的时间
     */
    val lastSpeechDetectedTime: Long

    /**
     * 处理检测到的语音活动
     * @param hasVoice 是否检测到语音
     * @param isCommandState 是否处于命令监听状态
     * @return 状态是否发生变化
     */
    fun processVoiceActivity(hasVoice: Boolean, isCommandState: Boolean): Boolean

    /**
     * 检查是否达到静音阈值
     * @param silenceThreshold 静音帧阈值
     * @return 是否达到阈值
     */
    fun isSilenceThresholdReached(silenceThreshold: Int): Boolean

    /**
     * 标记语音停止
     */
    fun markSpeechStopped()

    /**
     * 重置状态
     */
    fun reset()
}