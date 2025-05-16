@file:OptIn(ExperimentalForeignApi::class)

package snowboyPiper.interfaces

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.StateFlow

/**
 * 语音识别接口
 * 负责将语音转换为文本
 */
interface SpeechRecognizer {
    /**
     * 识别状态
     */
    enum class RecognitionState {
        IDLE,           // 空闲状态
        INITIALIZING,   // 初始化中
        LISTENING,      // 监听中
        PROCESSING,     // 处理中
        ERROR           // 错误状态
    }

    /**
     * 当前识别状态
     */
    val recognitionState: StateFlow<RecognitionState>

    /**
     * 识别结果
     */
    val recognitionText: StateFlow<String?>

    /**
     * 初始化语音识别器
     * @param modelPath 模型文件路径
     * @param deviceName 设备名称
     * @param sampleRate 采样率
     * @param micVolume 麦克风音量
     * @return 初始化是否成功
     */
    fun initialize(
        audioRecordDevice: AudioDevice,
        modelPath: String,
        deviceName: String = "default",
        sampleRate: Int = 16000,
        micVolume: Int = 80
    ): Boolean

    /**
     * 开始识别
     * @param timeoutMs 超时时间（毫秒）
     * @return 是否成功开始识别
     */
    fun startRecognition(timeoutMs: Long = 5000): Boolean

    /**
     * 停止识别
     */
    fun stopRecognition()

    /**
     * 处理音频数据
     * @param audioData 音频数据
     * @return 是否成功处理
     */
    fun processAudio(audioData: ShortArray): Boolean

    /**
     * 释放资源
     */
    fun release()
}