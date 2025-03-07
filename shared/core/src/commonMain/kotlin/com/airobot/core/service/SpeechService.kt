package com.airobot.core.service

import kotlinx.coroutines.flow.StateFlow

/**
 * 语音服务接口
 * 
 * 定义机器人语音相关功能，包括文本转语音、语音识别等
 */
interface SpeechService : RobotService {
    /**
     * 语音识别状态
     */
    enum class RecognitionStatus {
        IDLE,       // 空闲状态
        LISTENING,  // 正在监听
        PROCESSING, // 正在处理
        COMPLETED,  // 完成识别
        ERROR       // 错误状态
    }
    
    /**
     * 语音合成状态
     */
    enum class SynthesisStatus {
        IDLE,       // 空闲状态
        SPEAKING,   // 正在播放
        COMPLETED,  // 完成播放
        ERROR       // 错误状态
    }
    
    /**
     * 当前语音识别状态
     */
    val recognitionStatus: StateFlow<RecognitionStatus>
    
    /**
     * 当前语音合成状态
     */
    val synthesisStatus: StateFlow<SynthesisStatus>
    
    /**
     * 文本转语音
     * 
     * @param text 要转换的文本
     * @param interrupt 是否中断当前正在播放的语音
     * @return 操作是否成功
     */
    suspend fun textToSpeech(text: String, interrupt: Boolean = true): Boolean
    
    /**
     * 获取当前语音语言
     * 
     * @return 当前语言代码，如"zh-CN"，失败返回null
     */
    suspend fun getLanguage(): String?
    
    /**
     * 设置语音语言
     * 
     * @param languageCode 语言代码，如"zh-CN"、"en-US"等
     * @return 操作是否成功
     */
    suspend fun setLanguage(languageCode: String): Boolean
    
    /**
     * 获取当前音量
     * 
     * @return 当前音量值(0-100)，失败返回-1
     */
    suspend fun getVolume(): Int
    
    /**
     * 设置音量
     * 
     * @param volume 音量值，范围0-100
     * @return 操作是否成功
     */
    suspend fun setVolume(volume: Int): Boolean
    
    /**
     * 开始语音识别
     * 
     * @param timeout 超时时间(毫秒)，0表示不超时
     * @return 操作是否成功
     */
    suspend fun startSpeechRecognition(timeout: Long = 0): Boolean
    
    /**
     * 停止语音识别
     * 
     * @return 操作是否成功
     */
    suspend fun stopSpeechRecognition(): Boolean
    
    /**
     * 获取语音识别结果
     * 
     * @return 识别到的文本内容，失败返回null
     */
    suspend fun getSpeechRecognitionResult(): String?
    
    /**
     * 停止语音合成
     * 
     * @return 操作是否成功
     */
    suspend fun stopSpeechSynthesis(): Boolean
}