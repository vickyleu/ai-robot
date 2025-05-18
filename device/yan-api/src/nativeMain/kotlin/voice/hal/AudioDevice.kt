@file:OptIn(ExperimentalForeignApi::class)

package voice.hal

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ShortVar
import kotlinx.coroutines.flow.StateFlow

/**
 * 音频设备接口
 * 定义音频设备的基本操作
 */
interface AudioDevice {

    /**
     * 音频设备状态枚举
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
     * @param deviceName 设备名称
     * @param sampleRate 采样率
     * @return 初始化是否成功
     */
    fun initialize(deviceName: String = "", sampleRate: Int = 16000): Boolean
    
    /**
     * 开始音频流
     * @return 是否成功启动
     */
    fun start(): Boolean
    
    /**
     * 停止音频流
     */
    fun stop()
    
    /**
     * 设置采样率
     * @param sampleRate 新的采样率
     * @return 是否成功设置
     */
    fun setSampleRate(sampleRate: Int): Boolean
    
    /**
     * 获取采样率
     * @return 当前采样率
     */
    fun getSampleRate(): Int
    
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
    fun release()
    
    /**
     * 停止当前播放
     */
    fun stopPlayback()
    
    /**
     * 获取设备信息
     * @return 设备信息的字符串表示
     */
    fun getDeviceInfo(): String
}