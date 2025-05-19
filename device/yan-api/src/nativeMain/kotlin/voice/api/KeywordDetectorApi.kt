package voice.api

import kotlinx.coroutines.flow.StateFlow

/**
 * 关键词检测器接口
 * 负责监听音频中的关键词
 */
interface KeywordDetectorApi {
    /**
     * 检测器状态
     */
    enum class DetectorState {
        IDLE,       // 空闲状态
        LISTENING,  // 监听中
        DETECTED,   // 已检测到关键词
        ERROR       // 错误状态
    }

    /**
     * 当前检测器状态
     */
    val detectorState: StateFlow<DetectorState>
    
    /**
     * 初始化检测器
     * @param modelPath 模型文件路径
     * @param sensitivity 灵敏度，0.0-1.0
     * @return 初始化是否成功
     */
    fun initialize(modelPath: String, sensitivity: Float = 0.5f): Boolean
    
    /**
     * 开始监听关键词
     * @return 是否成功启动
     */
    suspend fun startListening(): Boolean
    
    /**
     * 停止监听
     */
    fun stopListening()

    /**
     * 释放资源
     */
    fun release()

    /**
     * 处理音频帧以检测关键词
     * @param audioFrame 音频数据
     * @param frameSize 帧大小
     * @return 是否检测到关键词
     */
    fun processAudioFrame(audioFrame: ShortArray, frameSize: Int): Boolean
    
    /**
     * 设置灵敏度
     * @param sensitivity 灵敏度，0.0-1.0
     */
    fun setSensitivity(sensitivity: Float)
    
    /**
     * 获取灵敏度
     * @return 当前灵敏度
     */
    fun getSensitivity(): Float
    
    /**
     * 添加关键词
     * @param keyword 关键词
     */
    fun addKeyword(keyword: String)
} 