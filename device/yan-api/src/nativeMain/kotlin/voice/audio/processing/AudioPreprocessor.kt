package voice.audio.processing

import voice.audio.AudioMetrics
import voice.audio.AudioProcessingPipeline
import voice.util.LogManager
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

/**
 * 音频预处理器
 * 负责将音频转换为标准格式，并进行基本处理
 */
@OptIn(ExperimentalTime::class)
class AudioPreprocessor : AudioProcessingPipeline {
    private val logger = LogManager.getLogger("AudioPreprocessor")
    
    // 标准目标格式
    private val targetSampleRate = 16000
    private val targetChannelCount = 1
    
    // 状态标志
    private var isInitialized = false
    
    // 实现AudioProcessingPipeline接口的必需方法
    private var keywordDetectedCallback: ((String) -> Unit)? = null
    private val keywords = mutableListOf<String>()

    /**
     * 初始化预处理器
     * 使用默认参数
     * @return 初始化是否成功
     */
    override fun initialize(): Boolean {
        logger.info("初始化音频预处理器")
        isInitialized = true
        return true
    }
    
    /**
     * 设置关键词检测回调
     * 预处理器不直接处理关键词，但需要实现此方法以符合接口
     */
    override fun setKeywordDetectedCallback(callback: (String) -> Unit) {
        keywordDetectedCallback = callback
        logger.info("预处理器设置关键词回调（仅作为接口实现）")
    }
    
    /**
     * 更新关键词列表
     * 预处理器不直接处理关键词，但需要实现此方法以符合接口
     */
    override fun updateKeywords(keywords: List<String>) {
        this.keywords.clear()
        this.keywords.addAll(keywords)
        logger.info("预处理器更新关键词列表（仅作为接口实现）")
    }
    
    /**
     * 处理音频数据
     * @param rawAudio 输入音频数据
     * @param length 输入长度
     * @return 处理结果
     */
    override fun process(rawAudio: ByteArray, length: Int): AudioProcessingPipeline.ProcessResult {
        if (!isInitialized) {
            logger.warn("预处理器未初始化")
            return AudioProcessingPipeline.ProcessResult(
                processedAudio = ByteArray(0),
                processedLength = 0,
                metrics = createDefaultAudioMetrics(),
                shouldContinue = false
            )
        }
        
        if (rawAudio.isEmpty() || length <= 0) {
            logger.warn("输入音频数据为空")
            return AudioProcessingPipeline.ProcessResult(
                processedAudio = ByteArray(0),
                processedLength = 0,
                metrics = createDefaultAudioMetrics(),
                shouldContinue = false
            )
        }
        
        val startTime = TimeSource.Monotonic.markNow()
        
        try {
            // 检查音频数据是否为有效的PCM数据
            if (length % 2 != 0) {
                logger.warn("无效的音频长度，不是2的倍数: $length")
                return AudioProcessingPipeline.ProcessResult(
                    processedAudio = ByteArray(0),
                    processedLength = 0,
                    metrics = createDefaultAudioMetrics(),
                    shouldContinue = false
                )
            }
            
            // 处理音频数据 - 这里可执行各种预处理
            // 如果输入已经是标准格式，直接返回
            val processedData = rawAudio.copyOf(length)
            val processedLength = length
            
            // 创建基本的音频指标（这里可以添加实际计算）
            val metrics = createDefaultAudioMetrics()
            
            logger.debug("预处理完成: 输出长度=$processedLength")
            
            return AudioProcessingPipeline.ProcessResult(
                processedAudio = processedData,
                processedLength = processedLength,
                metrics = metrics,
                shouldContinue = true
            )
        } catch (e: Exception) {
            logger.error("处理音频数据时出错: ${e.message}")
            return AudioProcessingPipeline.ProcessResult(
                processedAudio = ByteArray(0),
                processedLength = 0,
                metrics = createDefaultAudioMetrics(),
                shouldContinue = false
            )
        } finally {
            val elapsed = startTime.elapsedNow().inWholeMilliseconds
            if (elapsed > 20) {  // 记录处理时间超过20ms的情况
                logger.warn("音频预处理耗时较长: ${elapsed}ms")
            }
        }
    }

    /**
     * 创建默认的音频指标
     */
    private fun createDefaultAudioMetrics(): AudioMetrics {
        return AudioMetrics(
            rms = 0.0,
            maxAmplitude = 0,
            zeroCrossingRate = 0,
            nonZeroRatio = 0.0,
            clippingRatio = 0.0
        )
    }

    /**
     * 启动预处理器
     * @return 启动是否成功
     */
    override suspend fun start(): Boolean {
        if (!isInitialized) {
            logger.warn("预处理器未初始化")
            return false
        }
        
        logger.info("音频预处理器已启动")
        return true
    }
    
    /**
     * 停止预处理器
     */
    override fun stop() {
        logger.info("音频预处理器已停止")
    }
    
    /**
     * 释放资源
     */
    override fun release() {
        if (isInitialized) {
            logger.info("释放音频预处理器资源")
            isInitialized = false
        }
    }
    
    /**
     * 获取处理统计信息
     */
    override fun getStats(): AudioProcessingPipeline.ProcessingStats {
        return AudioProcessingPipeline.ProcessingStats(0, 0, 0, 0)
    }
    
    /**
     * 生成诊断报告
     */
    override fun generateDiagnosticReport(): String {
        return "音频预处理器状态: ${if (isInitialized) "已初始化" else "未初始化"}"
    }
} 