package voice.util

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fprintf
import voice.audio.AudioMetrics
import voice.audio.AudioPipeline
import voice.audio.RecognitionMetrics
import voice.audio.VADMetrics
import kotlin.time.ExperimentalTime

/**
 * 诊断收集器
 * 负责从各个组件收集诊断信息，并提供详细报告
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
class DiagnosticsCollector : AudioPipeline.Diagnostics {

    private val logger = LogManager.getLogger("DiagnosticsCollector")

    // 诊断数据存储的最大历史记录数量
    private val MAX_HISTORY_SIZE = 1000

    // 自动报告间隔（毫秒）
    private val AUTO_REPORT_INTERVAL_MS = 300000L // 5分钟

    // 各组件的诊断数据
    private data class AcquisitionData(
        val deviceInfo: String,
        val bufferSize: Int,
        val timestamp: Long
    )

    private val acquisitionData = mutableListOf<AcquisitionData>()
    private val preprocessingData = mutableListOf<Pair<AudioMetrics, Long>>()
    private val vadData = mutableListOf<Pair<VADMetrics, Long>>()
    private val recognitionData = mutableListOf<Pair<RecognitionMetrics, Long>>()

    // 组件状态统计
    private var acquisitionCallCount = 0
    private var preprocessingCallCount = 0
    private var preprocessingFilteredCount = 0
    private var vadCallCount = 0
    private var vadSpeechDetectedCount = 0
    private var recognitionCallCount = 0
    private var recognitionSuccessCount = 0
    private var recognitionFailureCount = 0

    // 错误统计
    private val errorCounts = mutableMapOf<String, Int>()
    private var lastReportTime = 0L

    /**
     * 记录音频采集指标
     */
    override fun recordAcquisitionMetrics(deviceInfo: String, bufferSize: Int, timestamp: Long) {
        acquisitionCallCount++
        if (acquisitionData.size < MAX_HISTORY_SIZE) {
            acquisitionData.add(AcquisitionData(deviceInfo, bufferSize, timestamp))
        }
    }

    /**
     * 记录音频预处理指标
     */
    override fun recordPreprocessingMetrics(metrics: AudioMetrics, timestamp: Long) {
        preprocessingCallCount++
        if (metrics.rms < 100 || metrics.maxAmplitude < 500) {
            preprocessingFilteredCount++
        }

        if (preprocessingData.size < MAX_HISTORY_SIZE) {
            preprocessingData.add(Pair(metrics, timestamp))
        }
    }

    /**
     * 记录VAD指标
     */
    override fun recordVADMetrics(metrics: VADMetrics, timestamp: Long) {
        vadCallCount++
        if (metrics.speechProbability > 0.6f) {
            vadSpeechDetectedCount++
            logger.debug("VAD检测到语音! 语音概率: ${metrics.speechProbability}, 能量: ${metrics.energyLevel}")
        }

        // 每10次VAD调用记录一次详细调试信息
        if (vadCallCount % 10 == 0) {
            logger.debug("VAD状态: 调用次数=${vadCallCount}, 检测到语音=${vadSpeechDetectedCount}, " +
                    "当前语音概率=${metrics.speechProbability}, 能量=${metrics.energyLevel}, 噪声=${metrics.noiseLevel}")
        }

        if (vadData.size < MAX_HISTORY_SIZE) {
            vadData.add(Pair(metrics, timestamp))
        }
    }

    /**
     * 记录识别指标
     */
    override fun recordRecognitionMetrics(metrics: RecognitionMetrics, timestamp: Long) {
        recognitionCallCount++

        if (metrics.errorCode == 0) {
            recognitionSuccessCount++
            logger.debug("识别成功: 处理时间=${metrics.processingTimeMs}ms, 置信度=${metrics.confidenceScore}")
        } else {
            recognitionFailureCount++
            val errorKey = "错误码${metrics.errorCode}: ${metrics.errorMessage}"
            errorCounts[errorKey] = (errorCounts[errorKey] ?: 0) + 1
            logger.warn("识别失败: $errorKey")
        }

        // 每10次识别记录一次详细状态
        if (recognitionCallCount % 10 == 0) {
            logger.debug("识别状态: 总调用=${recognitionCallCount}, 成功=${recognitionSuccessCount}, " +
                    "失败=${recognitionFailureCount}, 成功率=${(recognitionSuccessCount.toFloat() / recognitionCallCount.toFloat() * 100f).toInt()}%")
        }

        if (recognitionData.size < MAX_HISTORY_SIZE) {
            recognitionData.add(Pair(metrics, timestamp))
        }

        // 定期自动生成报告
        val now = LogManager.getCurrentTimeMillis()
        if (now - lastReportTime > AUTO_REPORT_INTERVAL_MS) {
            lastReportTime = now

            // 如果出现大量错误，生成报告
            if (recognitionFailureCount > 0 &&
                (recognitionFailureCount.toFloat() / recognitionCallCount.toFloat()) > 0.3f) {
                val report = generateReport()
                logger.info("自动生成诊断报告:\n$report")
                saveReportToFile(report)
            }
        }
    }

    /**
     * 生成诊断报告
     */
    override fun generateReport(): String {
        val report = StringBuilder()

        report.appendLine("=== 音频处理诊断报告 ===")
        report.appendLine("生成时间: ${LogManager.getCurrentTimeMillis()}")
        report.appendLine()

        // 总体统计
        report.appendLine("== 总体处理统计 ==")
        report.appendLine("音频采集调用次数: $acquisitionCallCount")
        report.appendLine("预处理调用次数: $preprocessingCallCount")
        report.appendLine("预处理过滤帧数: $preprocessingFilteredCount (${percentString(preprocessingFilteredCount, preprocessingCallCount)})")
        report.appendLine("VAD调用次数: $vadCallCount")
        report.appendLine("VAD检测到语音次数: $vadSpeechDetectedCount (${percentString(vadSpeechDetectedCount, vadCallCount)})")
        report.appendLine("识别调用次数: $recognitionCallCount")
        report.appendLine("识别成功次数: $recognitionSuccessCount (${percentString(recognitionSuccessCount, recognitionCallCount)})")
        report.appendLine("识别失败次数: $recognitionFailureCount (${percentString(recognitionFailureCount, recognitionCallCount)})")
        report.appendLine()

        // 处理管道流量分析
        report.appendLine("== 处理管道流量分析 ==")
        val acquisitionToPreprocessingLoss = preprocessingCallCount.toFloat() / maxOf(1, acquisitionCallCount).toFloat()
        val preprocessingToVADLoss = vadCallCount.toFloat() / maxOf(1, preprocessingCallCount).toFloat()
        val vadToRecognitionLoss = recognitionCallCount.toFloat() / maxOf(1, vadCallCount).toFloat()

        report.appendLine("采集->预处理 传递率: ${(acquisitionToPreprocessingLoss * 100).toInt()}%")
        report.appendLine("预处理->VAD 传递率: ${(preprocessingToVADLoss * 100).toInt()}%")
        report.appendLine("VAD->识别 传递率: ${(vadToRecognitionLoss * 100).toInt()}%")
        report.appendLine()

        // 错误分析
        if (errorCounts.isNotEmpty()) {
            report.appendLine("== 错误分析 ==")
            errorCounts.entries.sortedByDescending { it.value }.forEach { (error, count) ->
                report.appendLine("$error: $count 次 (${percentString(count, recognitionFailureCount)})")
            }
            report.appendLine()
        }

        // 最近的预处理指标
        if (preprocessingData.isNotEmpty()) {
            report.appendLine("== 最近预处理指标分析 ==")
            val recentMetrics = preprocessingData.takeLast(10)

            val avgRms = recentMetrics.map { it.first.rms }.average()
            val avgMaxAmp = recentMetrics.map { it.first.maxAmplitude }.average()
            val avgNonZeroRatio = recentMetrics.map { it.first.nonZeroRatio }.average()

            report.appendLine("平均RMS: $avgRms")
            report.appendLine("平均最大振幅: $avgMaxAmp")
            report.appendLine("平均非零比例: $avgNonZeroRatio")
            report.appendLine()
        }

        // 最近的VAD指标
        if (vadData.isNotEmpty()) {
            report.appendLine("== 最近VAD指标分析 ==")
            val recentMetrics = vadData.takeLast(10)

            val avgEnergy = recentMetrics.map { it.first.energyLevel }.average()
            val avgSpeechProb = recentMetrics.map { it.first.speechProbability }.average()
            val avgNoiseLevel = recentMetrics.map { it.first.noiseLevel }.average()

            report.appendLine("平均能量: $avgEnergy")
            report.appendLine("平均语音概率: $avgSpeechProb")
            report.appendLine("平均噪声级别: $avgNoiseLevel")
            report.appendLine()
        }

        // 最近的识别错误
        if (recognitionData.isNotEmpty()) {
            val failedRecognitions = recognitionData.filter { it.first.errorCode != 0 }.takeLast(5)

            if (failedRecognitions.isNotEmpty()) {
                report.appendLine("== 最近识别错误详情 ==")
                failedRecognitions.forEach { (metrics, timestamp) ->
                    report.appendLine("时间: $timestamp, 错误码: ${metrics.errorCode}, 消息: ${metrics.errorMessage}")
                }
                report.appendLine()
            }
        }

        // 建议
        report.appendLine("== 问题分析和建议 ==")

        if (preprocessingFilteredCount > preprocessingCallCount * 0.5) {
            report.appendLine("* 问题: 超过50%的音频帧被预处理过滤，可能表明麦克风输入信号质量低或环境噪声大")
            report.appendLine("  建议: 检查麦克风设置和环境噪声水平")
        }

        if (recognitionFailureCount > recognitionCallCount * 0.3) {
            report.appendLine("* 问题: 超过30%的识别调用失败")

            val commonErrors = errorCounts.entries.sortedByDescending { it.value }.take(2)
            commonErrors.forEach { (error, count) ->
                report.appendLine("  常见错误: $error (${count}次)")
            }

            report.appendLine("  建议: 检查Vosk识别器配置和输入音频质量")
        }

        if (vadSpeechDetectedCount < vadCallCount * 0.1) {
            report.appendLine("* 问题: VAD很少检测到语音，可能阈值设置过高或输入音频质量低")
            report.appendLine("  建议: 调整VAD参数，降低语音检测阈值")
        }

        return report.toString()
    }

    /**
     * 将报告保存到文件
     */
    private fun saveReportToFile(report: String) {
        val timestamp = LogManager.getCurrentTimeMillis()
        val filename = "audio_diagnostics_$timestamp.log"

        val file = fopen(filename, "w") ?: return
        fprintf(file, "%s", report)
        fclose(file)

        logger.info("诊断报告已保存到: $filename")
    }

    /**
     * 计算百分比字符串
     */
    private fun percentString(part: Int, total: Int): String {
        if (total == 0) return "0%"
        val percentage = (part.toFloat() / total.toFloat() * 100).toInt()
        return "$percentage%"
    }

    /**
     * 清除收集的数据
     */
    fun clear() {
        acquisitionData.clear()
        preprocessingData.clear()
        vadData.clear()
        recognitionData.clear()
        errorCounts.clear()

        acquisitionCallCount = 0
        preprocessingCallCount = 0
        preprocessingFilteredCount = 0
        vadCallCount = 0
        vadSpeechDetectedCount = 0
        recognitionCallCount = 0
        recognitionSuccessCount = 0
        recognitionFailureCount = 0
    }
}