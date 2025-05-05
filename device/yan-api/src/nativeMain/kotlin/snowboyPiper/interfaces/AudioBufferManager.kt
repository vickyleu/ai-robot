package com.airobot.device.yanapi.snowboyPiper.interfaces


interface AudioBufferManager {
    /**
     * 累积的音频数据大小
     */
    val size: Int

    /**
     * 添加音频数据
     * @param audioData 音频数据
     */
    fun addAudio(audioData: ShortArray)

    /**
     * 添加音频数据
     * @param audioData 音频数据列表
     */
    fun addAudio(audioData: List<Short>)

    /**
     * 获取累积的音频数据
     * @return 音频数据数组
     */
    fun getAccumulatedAudio(): ShortArray

    /**
     * 清空缓冲区
     */
    fun clear()

    /**
     * 保留部分数据用于连续检测
     * @param overlapSize 要保留的数据大小
     */
    fun retainOverlap(overlapSize: Int)
}