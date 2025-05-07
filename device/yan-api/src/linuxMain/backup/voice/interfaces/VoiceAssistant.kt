package com.airobot.device.yanapi.voice.interfaces

import com.airobot.portaudiointerop.PaDeviceInfo
import kotlinx.coroutines.flow.Flow

/**
 * 语音助手接口
 * 顶层接口，整合唤醒、识别、合成等功能
 */
interface VoiceAssistant {
    /**
     * 助手状态
     */
    enum class AssistantState {
        IDLE,        // 空闲状态
        INITIALIZING,// 初始化中
        LISTENING,   // 监听唤醒词中
        ACTIVE,      // 已激活，等待或处理命令
        THINKING,    // 处理命令中
        RESPONDING,  // 正在响应中
        ERROR        // 错误状态
    }
    
    /**
     * 当前助手状态
     */
    val state: Flow<AssistantState>
    
    /**
     * 初始化语音助手
     * @param wakewordModel 唤醒词模型路径
     * @param recognizerModel 识别器模型路径
     * @param synthesizerModel 合成器模型路径
     * @return 初始化是否成功
     */
    fun initialize(
        wakewordResource: String,
        wakewordModel: String,
        recognizerModel: String,
        synthesizerModel: String,
        synthesizerConfig: String,
        synthesizerESpeakDataPath: String,
    ): Boolean
    
    /**
     * 启动语音助手
     * @return 是否成功启动
     */
    fun start(): Boolean
    
    /**
     * 停止语音助手
     */
    fun stop()
    
    /**
     * 手动激活语音助手
     * 跳过唤醒词检测
     */
    fun activate()
    
    /**
     * 提交文本命令
     * 直接处理文本命令，绕过语音识别
     * @param command 命令文本
     */
    suspend fun submitTextCommand(command: String)
    
    /**
     * 设置命令处理器
     * @param handler 命令处理函数，接收命令文本并返回响应文本
     */
    fun setCommandHandler(handler: suspend (String) -> String)
    
    /**
     * 播放文本
     * @param text 要播放的文本
     */
    suspend fun speak(text: String): Boolean
    
    /**
     * 释放资源
     */
    fun release()
} 