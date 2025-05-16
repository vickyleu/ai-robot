@file:OptIn(ExperimentalForeignApi::class)

package snowboyPiper.interfaces

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.StateFlow
import snowboyPiper.config.VoiceAssistantConfig

/**
 * 关键词检测器接口
 * 负责初始化和运行关键词检测
 */
interface KeywordDetector {
    /**
     * 检测状态
     */
    enum class DetectionState {
        IDLE,           // 空闲状态
        INITIALIZING,   // 初始化中
        LISTENING,      // 监听关键词中
        DETECTED,       // 检测到关键词
        ERROR           // 错误状态
    }

    enum class DetectorState(val value: Int) {
        Silence(-2),
        ERROR(-1),
        NoEvent(0),
        Hotword1Triggered(1),
        Hotword2Triggered(2),
        Hotword3Triggered(3);

        companion object {
            fun fromValue(value: Int): DetectorState {
                return entries.firstOrNull { it.value == value } ?: ERROR
            }
        }
    }

    /**
     * 当前检测状态
     */
    val detectionState: StateFlow<DetectionState>

    /**
     * 初始化检测器
     * @param resourcePath 资源文件路径
     * @param modelPath 模型文件路径
     * @param sensitivity 灵敏度，范围0-1
     * @return 初始化是否成功
     */
    fun initialize(
        resourcePath: String,
        modelPath: String,
        sensitivity: Float = 0.9f
    ): Boolean

    /**
     * 检测关键词
     * @param buffer 音频数据缓冲区
     * @param frameCount 帧数
     * @return 检测结果，大于0表示检测到关键词，0表示未检测到，负值表示错误
     */
    fun detect(
        player: AudioPlayer,
        buffer: ShortArray,
        frameCount: Int,
        sampleRate: Int,
        channels: Int
    ): DetectorState

    /**
     * 释放资源
     */
    fun release()
}