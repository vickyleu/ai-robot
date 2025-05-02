@file:OptIn(ExperimentalForeignApi::class)

package snowboyPiper.interfaces

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ShortVar
import kotlinx.coroutines.flow.StateFlow

/**
 * 音频设备接口
 * 负责音频设备的初始化、列举、打开和关闭等操作
 */
interface AudioDevice {
    /**
     * 音频设备状态
     */
    enum class AudioDeviceState {
        IDLE,           // 空闲状态
        INITIALIZING,   // 初始化中
        READY,          // 就绪状态
        ACTIVE,         // 活动状态
        ERROR           // 错误状态
    }
    
    /**
     * 当前音频设备状态
     */
    val deviceState: StateFlow<AudioDeviceState>
    
    /**
     * 初始化音频设备
     * @return 初始化是否成功
     */
    suspend fun initialize(): Boolean

    fun isInitialized(): Boolean
    
    /**
     * 列举可用的音频设备
     * @return 输入设备索引和输出设备索引的对
     */
    fun listAudioDevices(): Pair<Int, Int>
    
    /**
     * 打开音频输入流
     * @param deviceIndex 设备索引，-1表示默认设备
     * @param sampleRate 采样率
     * @param channels 通道数
     * @return 是否成功打开
     */
    suspend fun openInputStream(deviceIndex: Int, sampleRate: Int, channels: Int): Boolean
    
    /**
     * 打开音频输出流
     * @param deviceIndex 设备索引，-1表示默认设备
     * @param sampleRate 采样率
     * @param channels 通道数
     * @return 是否成功打开
     */
    suspend fun openOutputStream(deviceIndex: Int, sampleRate: Int, channels: Int): Boolean
    
    /**
     * 读取音频数据
     * @param buffer 数据缓冲区
     * @param frameCount 帧数
     * @return 读取的帧数，负值表示错误
     */
    suspend fun readAudio(buffer: CPointer<ShortVar>, frameCount: Int): Int
    

    /**
     * 关闭音频流
     */
    suspend fun closeStreams()
    
    /**
     * 释放资源
     */
    suspend fun release()
}