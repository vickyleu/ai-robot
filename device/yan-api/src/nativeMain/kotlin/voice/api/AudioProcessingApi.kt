package voice.api

/**
 * 音频处理管理器接口
 * 负责协调音频采集、预处理、VAD和语音识别的流程
 */
interface AudioProcessingApi {
    /**
     * 初始化音频处理管理器
     * @return 初始化是否成功
     */
    fun initialize(): Boolean
    
    /**
     * 设置关键词检测回调
     * @param callback 检测到关键词时的回调函数
     */
    fun setKeywordDetectedCallback(callback: (String) -> Unit)
    
    /**
     * 更新关键词列表
     * @param keywords 关键词列表
     */
    fun updateKeywords(keywords: List<String>)
    
    /**
     * 开始音频处理
     */
    fun start()
    
    /**
     * 停止音频处理
     */
    fun stop()
    
    /**
     * 生成诊断报告
     * @return 诊断信息文本
     */
    fun generateDiagnosticReport(): String
    
    /**
     * 释放资源
     */
    fun release()
    
    /**
     * 获取处理统计信息
     */
    fun getStats(): ProcessingStats
    
    /**
     * 处理统计信息
     */
    data class ProcessingStats(
        val frameCount: Int,          // 处理总帧数
        val speechFrameCount: Int,    // 语音帧数
        val recognitionCallCount: Int, // 识别调用次数
        val lastFrameTime: Long       // 最后帧时间
    )
} 