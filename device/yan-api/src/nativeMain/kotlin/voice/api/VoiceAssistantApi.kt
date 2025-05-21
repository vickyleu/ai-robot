package voice.api

import kotlinx.coroutines.flow.StateFlow

/**
 * 语音助手API接口
 * 提供完整的语音助手功能
 */
interface VoiceAssistantApi {
    /**
     * 助手状态
     */
    enum class AssistantState {
        IDLE,                   // 空闲状态
        INITIALIZING,           // 初始化中
        LISTENING_FOR_KEYWORD,  // 监听唤醒词中
        LISTENING_FOR_SPEECH,   // 监听语音命令中
        PROCESSING,             // 正在处理命令
        SPEAKING,               // 正在回复
        ERROR                   // 错误状态
    }
    
    /**
     * 当前助手状态
     */
    val assistantState: StateFlow<AssistantState>
    
    /**
     * 初始化语音助手
     * @param modelPath 模型路径
     * @return 初始化是否成功
     */
    suspend fun initialize(modelPath: String): Boolean
    
    /**
     * 开始监听唤醒词
     * @return 是否成功启动监听
     */
    suspend fun startListeningForKeyword(): Boolean
    
    /**
     * 停止语音助手
     */
    fun stop()
    
    /**
     * 设置语音识别结果回调
     * @param callback 回调函数，参数为识别到的文本
     */
    fun setSpeechRecognizedCallback(callback: (String) -> Unit)
    
    /**
     * 设置状态改变回调
     * @param callback 回调函数，参数为新状态
     */
    fun setStateChangeCallback(callback: (AssistantState) -> Unit)
    
    /**
     * 释放资源
     */
    fun release()
    
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
} 