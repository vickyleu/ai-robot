@file:OptIn(ExperimentalForeignApi::class)

package snowboyPiper.interfaces

import kotlinx.cinterop.CArrayPointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ShortVar

interface AudioBufferManager {
    /**
     * 累积的音频数据大小
     */
    val size: Int

    /**
     * 添加音频样本
     * @param sample 音频样本
     */
    fun add(sample: Short)

    /**
     * 获取累积的音频数据
     * @return 音频数据数组
     */
    fun get(): ShortArray

    /**
     * 清空累积的音频数据
     */
    fun clear()

    /**
     * 保留重叠区域
     * @param overlapSize 重叠区域大小
     */
    fun retainOverlap(overlapSize: Int)

    /**
     * 获取短整型缓冲区
     * @param size 缓冲区大小
     * @return 短整型缓冲区
     */
    fun getShortBuffer(size: Int): CArrayPointer<ShortVar>

    /**
     * 获取浮点型缓冲区
     * @param size 缓冲区大小
     * @return 浮点型缓冲区
     */
    fun getFloatBuffer(size: Int): CArrayPointer<FloatVar>

    /**
     * 获取整型缓冲区
     * @param size 缓冲区大小
     * @return 整型缓冲区
     */
    fun getIntBuffer(size: Int): CPointer<IntVar>

    /**
     * 获取短整型数组
     * @param size 数组大小
     * @return 短整型数组
     */
    fun getShortArray(size: Int): ShortArray

    /**
     * 释放所有资源
     */
    fun release()
}