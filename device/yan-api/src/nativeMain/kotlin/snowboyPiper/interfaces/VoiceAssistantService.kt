@file:OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)

package snowboyPiper.interfaces

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.ExperimentalTime

/**
 * 语音助手服务接口
 * 整合关键词检测、语音识别、语音合成等功能
 */
interface VoiceAssistantService {
    /**
     * 助手状态
     */
    enum class AssistantState {
        IDLE,               // 空闲状态
        INITIALIZING,       // 初始化中
        LISTENING_KEYWORD,  // 监听关键词中
        LISTENING_COMMAND,  // 监听命令中
        PROCESSING,         // 处理中
        RESPONDING,         // 响应中
        ERROR               // 错误状态
    }

    /**
     * 当前助手状态
     */
    val assistantState: StateFlow<AssistantState>

    /**
     * 当前识别的文本
     */
    val recognizedText: StateFlow<String?>

    /**
     * 初始化语音助手
     * @param resourcePath Snowboy资源文件路径
     * @param modelPath Snowboy模型文件路径
     * @param piperModelPath Piper模型文件路径
     * @param piperConfigPath Piper配置文件路径
     * @param voskModelPath vosk模型文件路径
     * @param piperESpeakDataPath Piper espeak数据路径
     * @return 初始化是否成功
     */
    suspend   fun initialize( ): Boolean

    /**
     * 启动语音助手
     * @return 是否成功启动
     */
    suspend fun start(): Boolean

    /**
     * 停止语音助手
     */
    suspend fun stop()

    /**
     * 播放文本
     * @param text 要播放的文本
     * @return 是否成功播放
     */
    suspend fun speak(text: String): Boolean

    /**
     * 释放资源
     */
    suspend fun release()
}