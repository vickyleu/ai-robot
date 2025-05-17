package voice.api.assistant

import kotlinx.coroutines.flow.StateFlow

/**
 * 语音助手接口
 * 提供语音交互能力
 */
interface IVoiceAssistant {
    /**
     * 助手状态
     */
    enum class AssistantState {
        IDLE,               // 空闲状态
        INITIALIZING,       // 初始化中
        LISTENING_KEYWORD,  // 正在监听唤醒词
        LISTENING_COMMAND,  // 正在监听命令
        PROCESSING_COMMAND, // 正在处理命令
        SPEAKING,           // 正在回复
        ERROR               // 错误状态
    }
    
    /**
     * 当前助手状态
     */
    val assistantState: StateFlow<AssistantState>
    
    /**
     * 最近识别到的文本
     */
    val recognizedText: StateFlow<String?>
    
    /**
     * 初始化语音助手
     * @return 初始化是否成功
     */
    suspend fun initialize(): Boolean
    
    /**
     * 启动语音助手
     * @return 启动是否成功
     */
    suspend fun start(): Boolean
    
    /**
     * 停止语音助手
     */
    suspend fun stop()
    
    /**
     * 提交文本命令
     * @param text 文本命令
     * @return 回复内容
     */
    suspend fun submitTextCommand(text: String): String
    
    /**
     * 朗读文本
     * @param text 要朗读的文本
     * @return 播放是否成功
     */
    suspend fun speak(text: String): Boolean
    
    /**
     * 释放资源
     */
    suspend fun release()
} 