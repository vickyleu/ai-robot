package voice.util

import com.airobot.core.utils.format
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fprintf
import voice.audio.AudioMetrics
import voice.audio.AudioProcessingPipeline
import voice.audio.RecognitionMetrics
import voice.audio.VADMetrics
import kotlin.time.ExperimentalTime

/**
 * 诊断收集器
 * 负责从各个组件收集诊断信息，并提供详细报告
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
class DiagnosticsCollector : AudioProcessingPipeline.Diagnostics {

    private val logger = LogManager.getLogger("DiagnosticsCollector")

    // 诊断数据存储的最大历史记录数量
    private val MAX_HISTORY_SIZE = 1000

    // 自动报告间隔（毫秒）
    private val AUTO_REPORT_INTERVAL_MS = 300000L // 5分钟

    // 采集指标
    private data class InternalAcquisitionMetrics(
        val deviceInfo: String,
        val bufferSize: Int,
        val timestamp: Long
    )

    private val acquisitionMetricsList = mutableListOf<InternalAcquisitionMetrics>()

    // 预处理指标
    private data class InternalPreprocessingEntry(
        val metrics: AudioMetrics,
        val timestamp: Long
    )

    private val preprocessingMetricsList = mutableListOf<InternalPreprocessingEntry>()

    // VAD指标
    private data class InternalVADEntry(
        val metrics: VADMetrics,
        val timestamp: Long
    )

    private val vadMetricsList = mutableListOf<InternalVADEntry>()

    // 识别指标
    private data class InternalRecognitionEntry(
        val metrics: RecognitionMetrics,
        val timestamp: Long
    )

    private val recognitionMetricsList = mutableListOf<InternalRecognitionEntry>()

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
        if (acquisitionMetricsList.size >= MAX_HISTORY_SIZE) {
            acquisitionMetricsList.removeAt(0) // 保持列表大小
        }
        acquisitionMetricsList.add(InternalAcquisitionMetrics(deviceInfo, bufferSize, timestamp))
    }

    /**
     * 记录音频预处理指标
     */
    override fun recordPreprocessingMetrics(metrics: AudioMetrics, timestamp: Long) {
        preprocessingCallCount++
        if (metrics.rms < 100 || metrics.maxAmplitude < 500) { // 假设这些是过滤条件
            preprocessingFilteredCount++
        }
        if (preprocessingMetricsList.size >= MAX_HISTORY_SIZE) {
            preprocessingMetricsList.removeAt(0)
        }
        preprocessingMetricsList.add(InternalPreprocessingEntry(metrics, timestamp))
    }

    /**
     * 记录VAD指标
     */
    override fun recordVADMetrics(metrics: VADMetrics, timestamp: Long) {
        vadCallCount++
        if (metrics.speechProbability > 0.6f) { // 假设语音概率大于0.6表示检测到语音
            vadSpeechDetectedCount++
            // logger.debug("VAD检测到语音! 语音概率: ${metrics.speechProbability}, 能量: ${metrics.energy}")
        }
        if (vadMetricsList.size >= MAX_HISTORY_SIZE) {
            vadMetricsList.removeAt(0)
        }
        vadMetricsList.add(InternalVADEntry(metrics, timestamp))
        // Log VAD status periodically or on significant events if needed
    }

    /**
     * 记录识别指标
     */
    override fun recordRecognitionMetrics(metrics: RecognitionMetrics, timestamp: Long) {
        recognitionCallCount++

        if (metrics.errorCode == 0) {
            recognitionSuccessCount++
            // logger.debug("识别成功: 处理时间=${metrics.processingTimeMs}ms, 置信度=${metrics.confidenceScore}")
        } else {
            recognitionFailureCount++
            val errorKey = "错误码${metrics.errorCode}: ${metrics.errorMessage}"
            errorCounts[errorKey] = (errorCounts[errorKey] ?: 0) + 1
            // logger.warn("识别失败: $errorKey")
        }
        if (recognitionMetricsList.size >= MAX_HISTORY_SIZE) {
            recognitionMetricsList.removeAt(0)
        }
        recognitionMetricsList.add(InternalRecognitionEntry(metrics, timestamp))

        val now = LogManager.getCurrentTimeMillis()
        if (now - lastReportTime > AUTO_REPORT_INTERVAL_MS) {
            lastReportTime = now
            if (recognitionFailureCount > 0 && (recognitionFailureCount.toFloat() / recognitionCallCount.toFloat()) > 0.3f) {
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

        report.appendLine("== 总体处理统计 ==")
        report.appendLine("音频采集调用次数: $acquisitionCallCount")
        report.appendLine("预处理调用次数: $preprocessingCallCount")
        report.appendLine(
            "预处理过滤帧数: $preprocessingFilteredCount (${
                percentString(
                    preprocessingFilteredCount,
                    preprocessingCallCount
                )
            })"
        )
        report.appendLine("VAD调用次数: $vadCallCount")
        report.appendLine(
            "VAD检测到语音次数: $vadSpeechDetectedCount (${
                percentString(
                    vadSpeechDetectedCount,
                    vadCallCount
                )
            })"
        )
        report.appendLine("识别调用次数: $recognitionCallCount")
        report.appendLine(
            "识别成功次数: $recognitionSuccessCount (${
                percentString(
                    recognitionSuccessCount,
                    recognitionCallCount
                )
            })"
        )
        report.appendLine(
            "识别失败次数: $recognitionFailureCount (${
                percentString(
                    recognitionFailureCount,
                    recognitionCallCount
                )
            })"
        )
        report.appendLine()

        // 错误分析
        if (errorCounts.isNotEmpty()) {
            report.appendLine("== 错误分析 ==")
            errorCounts.entries.sortedByDescending { it.value }.forEach { (error, count) ->
                report.appendLine(
                    "$error: $count 次 (${
                        percentString(
                            count,
                            recognitionFailureCount
                        )
                    })"
                )
            }
            report.appendLine()
        }

        // 最近的采集指标
        if (acquisitionMetricsList.isNotEmpty()) {
            report.appendLine("== 最近采集指标分析 (最后10条) ==")
            acquisitionMetricsList.takeLast(10).forEachIndexed { idx, metric ->
                report.appendLine("  #${idx + 1}: 设备=\"${metric.deviceInfo}\", Buffer=${metric.bufferSize}, Time=${metric.timestamp}")
            }
            report.appendLine()
        }

        // 最近的预处理指标
        if (preprocessingMetricsList.isNotEmpty()) {
            report.appendLine("== 最近预处理指标分析 (平均值/最后10条) ==")
            val recentMetrics = preprocessingMetricsList.takeLast(10)
            val avgRms = recentMetrics.map { it.metrics.rms }.filterNotNull().average()
            val avgMaxAmp = recentMetrics.map { it.metrics.maxAmplitude }.filterNotNull().average()
            report.appendLine("  平均RMS: ${"%.2f".format(avgRms)}")
            report.appendLine("  平均最大振幅: ${"%.2f".format(avgMaxAmp)}")
            report.appendLine()
        }

        // 最近的VAD指标
        if (vadMetricsList.isNotEmpty()) {
            report.appendLine("== 最近VAD指标分析 (平均值/最后10条) ==")
            val recentMetrics = vadMetricsList.takeLast(10)
            val avgEnergy = recentMetrics.map { it.metrics.energy }.filterNotNull().average()
            val avgSpeechProb =
                recentMetrics.map { it.metrics.speechProbability }.filterNotNull().average()
            val avgNoiseLevel =
                recentMetrics.map { it.metrics.noiseFloor }.filterNotNull().average()
            report.appendLine("  平均能量: ${"%.2f".format(avgEnergy)}")
            report.appendLine("  平均语音概率: ${"%.2f".format(avgSpeechProb)}")
            report.appendLine("  平均噪声级别: ${"%.2f".format(avgNoiseLevel)}")
            report.appendLine()
        }

        // 最近的识别指标
        if (recognitionMetricsList.isNotEmpty()) {
            report.appendLine("== 最近识别指标分析 (平均值/最后10条) ==")
            val recentMetrics = recognitionMetricsList.takeLast(10)
            val avgConfidence =
                recentMetrics.map { it.metrics.confidenceScore }.filterNotNull().average()
            val avgProcessingTime =
                recentMetrics.map { it.metrics.processingTimeMs }.filterNotNull().average()
            report.appendLine("  平均置信度: ${"%.2f".format(avgConfidence)}")
            report.appendLine("  平均处理时间: ${"%.2f".format(avgProcessingTime)}ms")
            report.appendLine("  最近错误 (最多5条):")
            recognitionMetricsList.filter { it.metrics.errorCode != 0 }.takeLast(5).forEach { rec ->
                report.appendLine("    Time=${rec.timestamp}, Code=${rec.metrics.errorCode}, Msg=\"${rec.metrics.errorMessage}\"")
            }
            report.appendLine()
        }

        return report.toString()
    }

    private fun saveReportToFile(report: String) {
        val timestamp = LogManager.getCurrentTimeMillis()
        val filename = "audio_diagnostics_$timestamp.log"
        try {
            val file = fopen(filename, "w")
            if (file != null) {
                fprintf(file, "%s", report)
                fclose(file)
                logger.info("诊断报告已保存到: $filename")
            } else {
                logger.error("无法打开文件以保存诊断报告: $filename")
            }
        } catch (e: Exception) {
            logger.error("保存诊断报告到文件时发生错误: ${e.message}")
        }
    }

    private fun percentString(part: Int, total: Int): String {
        if (total == 0) return "0% (N/A)"
        val percentage = (part.toFloat() / total.toFloat() * 100)
        return "${"%.1f".format(percentage)}%"
    }

    fun clear() {
        acquisitionMetricsList.clear()
        preprocessingMetricsList.clear()
        vadMetricsList.clear()
        recognitionMetricsList.clear()
        errorCounts.clear()
        acquisitionCallCount = 0
        preprocessingCallCount = 0
        preprocessingFilteredCount = 0
        vadCallCount = 0
        vadSpeechDetectedCount = 0
        recognitionCallCount = 0
        recognitionSuccessCount = 0
        recognitionFailureCount = 0
        lastReportTime = 0L
        logger.info("诊断数据已清除")
    }
}