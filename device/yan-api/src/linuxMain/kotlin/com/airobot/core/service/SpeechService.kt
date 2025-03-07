package com.airobot.core.service

import com.airobot.device.yanapi.YanSpeechService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * 语音服务接口的YAN机器人实现
 * 
 * 将YanSpeechService适配到SpeechService接口
 */
class SpeechServiceImpl : SpeechService {
    private val yanSpeechService = YanSpeechService()
    private var isInitialized = false
    
    // 状态流
    private val _recognitionStatus = MutableStateFlow(SpeechService.RecognitionStatus.IDLE)
    override val recognitionStatus: StateFlow<SpeechService.RecognitionStatus> = _recognitionStatus
    
    private val _synthesisStatus = MutableStateFlow(SpeechService.SynthesisStatus.IDLE)
    override val synthesisStatus: StateFlow<SpeechService.SynthesisStatus> = _synthesisStatus
    
    /**
     * 初始化服务
     * 
     * @return 初始化是否成功
     */
    override suspend fun initialize(): Boolean {
        isInitialized = true
        return true
    }
    
    /**
     * 关闭服务
     * 
     * @return 关闭是否成功
     */
    override suspend fun shutdown(): Boolean {
        isInitialized = false
        return true
    }
    
    /**
     * 获取服务名称
     * 
     * @return 服务名称
     */
    override fun getServiceName(): String {
        return "YAN语音服务"
    }
    
    /**
     * 检查服务是否可用
     * 
     * @return 服务是否可用
     */
    override fun isAvailable(): Boolean {
        return isInitialized
    }
    
    /**
     * 文本转语音
     * 
     * @param text 要转换的文本
     * @param interrupt 是否中断当前正在播放的语音
     * @return 操作是否成功
     */
    override suspend fun textToSpeech(text: String, interrupt: Boolean): Boolean {
        return withContext(Dispatchers.Default) {
            try {
                _synthesisStatus.value = SpeechService.SynthesisStatus.SPEAKING
                val result = yanSpeechService.textToSpeech(text, interrupt)
                _synthesisStatus.value = if (result) {
                    SpeechService.SynthesisStatus.COMPLETED
                } else {
                    SpeechService.SynthesisStatus.ERROR
                }
                result
            } catch (e: Exception) {
                _synthesisStatus.value = SpeechService.SynthesisStatus.ERROR
                false
            }
        }
    }
    
    /**
     * 停止语音合成
     * 
     * @return 操作是否成功
     */
    override suspend fun stopSpeechSynthesis(): Boolean {
        return withContext(Dispatchers.Default) {
            try {
                val result = yanSpeechService.stopVoiceTts()
                if (result) {
                    _synthesisStatus.value = SpeechService.SynthesisStatus.IDLE
                }
                result
            } catch (e: Exception) {
                false
            }
        }
    }
    
    /**
     * 开始语音识别
     * 
     * @param timeout 超时时间(毫秒)，0表示不超时
     * @return 操作是否成功
     */
    override suspend fun startSpeechRecognition(timeout: Long): Boolean {
        return withContext(Dispatchers.Default) {
            try {
                _recognitionStatus.value = SpeechService.RecognitionStatus.LISTENING
                true
            } catch (e: Exception) {
                _recognitionStatus.value = SpeechService.RecognitionStatus.ERROR
                false
            }
        }
    }
    
    /**
     * 停止语音识别
     * 
     * @return 操作是否成功
     */
    override suspend fun stopSpeechRecognition(): Boolean {
        return withContext(Dispatchers.Default) {
            try {
                _recognitionStatus.value = SpeechService.RecognitionStatus.PROCESSING
                true
            } catch (e: Exception) {
                _recognitionStatus.value = SpeechService.RecognitionStatus.ERROR
                false
            }
        }
    }
    
    /**
     * 获取语音识别结果
     * 
     * @return 识别到的文本内容，失败返回null
     */
    override suspend fun getSpeechRecognitionResult(): String? {
        return withContext(Dispatchers.Default) {
            try {
                val result = yanSpeechService.syncDoVoiceIatValue()
                _recognitionStatus.value = if (result != null) {
                    SpeechService.RecognitionStatus.COMPLETED
                } else {
                    SpeechService.RecognitionStatus.ERROR
                }
                result
            } catch (e: Exception) {
                _recognitionStatus.value = SpeechService.RecognitionStatus.ERROR
                null
            }
        }
    }
    
    /**
     * 获取当前语音语言
     * 
     * @return 当前语言代码，如"zh-CN"，失败返回null
     */
    override suspend fun getLanguage(): String? {
        return withContext(Dispatchers.Default) {
            yanSpeechService.getLanguage()
        }
    }
    
    /**
     * 设置语音语言
     * 
     * @param languageCode 语言代码，如"zh-CN"、"en-US"等
     * @return 操作是否成功
     */
    override suspend fun setLanguage(languageCode: String): Boolean {
        return withContext(Dispatchers.Default) {
            yanSpeechService.setLanguage(languageCode)
        }
    }
    
    /**
     * 获取当前音量
     * 
     * @return 当前音量值(0-100)，失败返回-1
     */
    override suspend fun getVolume(): Int {
        return withContext(Dispatchers.Default) {
            yanSpeechService.getVolume()
        }
    }
    
    /**
     * 设置音量
     * 
     * @param volume 音量值，范围0-100
     * @return 操作是否成功
     */
    override suspend fun setVolume(volume: Int): Boolean {
        return withContext(Dispatchers.Default) {
            yanSpeechService.setVolume(volume)
        }
    }
}