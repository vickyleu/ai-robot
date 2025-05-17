package voice.audio

import kotlin.time.ExperimentalTime

/**
 * 音频处理流水线
 * 定义音频处理各个环节的接口和数据结构
 */
@OptIn(ExperimentalTime::class)
interface AudioPipeline {
    /**
     * 音频采集接口
     * 负责从各种音频源（麦克风、文件等）获取原始音频数据
     */
    interface Acquisition {
        /**
         * 初始化音频采集
         * @return 初始化是否成功
         */
        fun initialize(): Boolean
        
        /**
         * 开始采集音频
         * @param callback 采集到音频数据时的回调函数
         */
        fun startCapture(callback: (ByteArray, Int) -> Unit)
        
        /**
         * 停止采集音频
         */
        fun stopCapture()
        
        /**
         * 释放资源
         */
        fun release()
        
        /**
         * 音频采集配置
         */
        data class Config(
            val sampleRate: Int = 16000,    // 采样率
            val channels: Int = 1,          // 通道数
            val bitsPerSample: Int = 16     // 每样本位数
        )
    }
    
    /**
     * 音频预处理接口
     * 负责对原始音频进行降噪、增益控制等预处理
     */
    interface Preprocessing {
        /**
         * 处理音频数据
         * @param rawAudio 原始音频数据
         * @param length 数据长度
         * @return 处理结果
         */
        fun process(rawAudio: ByteArray, length: Int): ProcessResult
        
        /**
         * 处理结果数据类
         */
        data class ProcessResult(
            val processedAudio: ByteArray,  // 处理后的音频数据
            val processedLength: Int,       // 处理后的数据长度
            val metrics: AudioMetrics,      // 音频指标
            val shouldContinue: Boolean     // 是否应继续处理
        )
    }
    
    /**
     * 语音活动检测（VAD）接口
     * 负责检测音频中是否包含人声
     */
    interface VoiceActivityDetection {
        /**
         * 检测音频是否包含语音
         * @param audio 音频数据
         * @param length 数据长度
         * @return 检测结果
         */
        fun detect(audio: ByteArray, length: Int): DetectionResult
        
        /**
         * 检测结果数据类
         */
        data class DetectionResult(
            val hasSpeech: Boolean,         // 是否包含语音
            val confidence: Float,          // 置信度
            val metrics: VADMetrics         // VAD指标
        )
    }
    
    /**
     * 语音识别接口
     * 负责将音频转换为文本
     */
    interface SpeechRecognition {
        /**
         * 识别音频
         * @param audio 音频数据
         * @param length 数据长度
         * @return 识别结果
         */
        fun recognize(audio: ByteArray, length: Int): RecognitionResult
        
        /**
         * 识别结果数据类
         */
        data class RecognitionResult(
            val success: Boolean,           // 是否成功
            val text: String,               // 识别出的文本
            val isPartial: Boolean,         // 是否为部分结果
            val metrics: RecognitionMetrics // 识别指标
        )
    }
    
    /**
     * 诊断接口
     * 负责收集处理流水线各环节的诊断数据
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