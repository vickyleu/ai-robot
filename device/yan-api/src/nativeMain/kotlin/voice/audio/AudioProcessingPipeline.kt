package voice.audio

import voice.audio.AudioMetrics
import kotlin.time.ExperimentalTime

/**
 * 音频处理流水线接口
 * 统一定义音频处理各个环节的接口、数据结构和诊断功能
 */
@OptIn(ExperimentalTime::class)
interface AudioProcessingPipeline {
    /**
     * 初始化处理流水线
     */
    fun initialize(): Boolean
    
    /**
     * 开始处理
     */
    suspend fun start() : Boolean
    
    /**
     * 停止处理
     */
    fun stop()
    
    /**
     * 处理音频数据
     * @param rawAudio 原始音频数据
     * @param length 数据长度
     * @return 处理结果
     */
    fun process(rawAudio: ByteArray, length: Int): ProcessResult
    
    /**
     * 设置关键词检测回调
     */
    fun setKeywordDetectedCallback(callback: (String) -> Unit)
    
    /**
     * 更新关键词列表
     */
    fun updateKeywords(keywords: List<String>)
    
    /**
     * 获取处理统计信息
     */
    fun getStats(): ProcessingStats
    
    /**
     * 生成诊断报告
     */
    fun generateDiagnosticReport(): String
    
    /**
     * 释放资源
     */
    fun release()
    
    /**
     * 处理统计信息
     */
    data class ProcessingStats(
        val frameCount: Int,          // 处理总帧数
        val speechFrameCount: Int,    // 语音帧数
        val recognitionCallCount: Int, // 识别调用次数
        val lastFrameTime: Long       // 最后帧时间
    )
    
    /**
     * 处理结果
     */
    data class ProcessResult(
        val processedAudio: ByteArray,  // 处理后的音频数据
        val processedLength: Int,       // 处理后的数据长度
        val metrics: AudioMetrics,      // 音频指标
        val shouldContinue: Boolean     // 是否应继续处理
    )
    
    /**
     * 诊断接口
     */
    interface Diagnostics {
        /**
         * 记录音频采集指标
         */
        fun recordAcquisitionMetrics(deviceInfo: String, bufferSize: Int, timestamp: Long)
        
        /**
         * 记录预处理指标
         */
        fun recordPreprocessingMetrics(metrics: AudioMetrics, timestamp: Long)
        
        /**
         * 记录VAD指标
         */
        fun recordVADMetrics(metrics: VADMetrics, timestamp: Long)
        
        /**
         * 记录识别指标
         */
        fun recordRecognitionMetrics(metrics: RecognitionMetrics, timestamp: Long)
        
        /**
         * 生成诊断报告
         */
        fun generateReport(): String
    }
} 