package com.airobot.device.yanapi.voice.interfaces

/**
 * 音频处理器接口
 * 定义基本的音频处理功能
 */
interface AudioProcessor {
    /**
     * 处理音频数据
     * @param audioData 原始音频数据
     * @return 处理后的音频数据
     */
    fun processAudio(audioData: ShortArray): ShortArray
    
    /**
     * 重置处理器状态
     */
    fun reset()
} 