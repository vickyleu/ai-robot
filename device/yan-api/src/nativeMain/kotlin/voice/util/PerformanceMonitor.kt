package com.airobot.device.yanapi.voice.util

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.datetime.Clock
import voice.util.AudioDefaults
import voice.util.LogManager
import kotlin.math.roundToInt

/**
 * 性能监控器
 */
class PerformanceMonitor(private val name: String) {
    private val logger = LogManager.getLogger("PerformanceMonitor")

    private val lock = SynchronizedObject()
    private val processingTimes = mutableListOf<Long>()
    private val maxSamples = 100

    // 性能指标
    private var totalProcessed = 0L
    private var totalErrors = 0L
    private var lastReportTime = Clock.System.now().toEpochMilliseconds()

    // 自适应参数
    private var qualityLevel = QualityLevel.HIGH
    private var lastQualityAdjustTime = 0L
    private val qualityAdjustInterval = AudioDefaults.QUALITY_ADJUST_INTERVAL_MS // 5秒调整一次

    enum class QualityLevel {
        LOW,    // CPU占用高，降低质量
        MEDIUM, // 平衡模式
        HIGH    // 高质量模式
    }

    /**
     * 记录处理时间
     */
    fun recordProcessingTime(timeMs: Long) {
        synchronized(lock) {
            processingTimes.add(timeMs)
            if (processingTimes.size > maxSamples) {
                processingTimes.removeAt(0)
            }
            totalProcessed++

            // 自动调整质量
            checkAndAdjustQuality()
        }
    }

    /**
     * 记录错误
     */
    fun recordError() {
        synchronized(lock) {
            totalErrors++
        }
    }

    /**
     * 获取平均处理时间
     */
    fun getAverageProcessingTime(): Long {
        return synchronized(lock) {
            if (processingTimes.isEmpty()) 0L
            else processingTimes.average().toLong()
        }
    }

    /**
     * 获取最大处理时间
     */
    fun getMaxProcessingTime(): Long {
        return synchronized(lock) {
            processingTimes.maxOrNull() ?: 0L
        }
    }

    /**
     * 检查是否应该降低质量
     */
    fun shouldReduceQuality(): Boolean {
        return synchronized(lock) {
            qualityLevel == QualityLevel.LOW
        }
    }

    /**
     * 获取当前质量级别
     */
    fun getQualityLevel(): QualityLevel {
        return synchronized(lock) {
            qualityLevel
        }
    }

    /**
     * 手动设置质量级别
     */
    fun setQualityLevel(level: QualityLevel) {
        synchronized(lock) {
            if (qualityLevel != level) {
                logger.info("[$name] 质量级别调整: $qualityLevel -> $level")
                qualityLevel = level
            }
        }
    }

    /**
     * 检查并自动调整质量
     */
    private fun checkAndAdjustQuality() {
        val now = Clock.System.now().toEpochMilliseconds()
        if (now - lastQualityAdjustTime < qualityAdjustInterval) {
            return
        }

        lastQualityAdjustTime = now
        val avgTime = getAverageProcessingTime()
        val maxTime = getMaxProcessingTime()

        val newLevel = when {
            maxTime > 100 || avgTime > 50 -> QualityLevel.LOW
            maxTime > 50 || avgTime > 30 -> QualityLevel.MEDIUM
            else -> QualityLevel.HIGH
        }

        if (newLevel != qualityLevel) {
            logger.info("[$name] 自动调整质量: $qualityLevel -> $newLevel (avg=${avgTime}ms, max=${maxTime}ms)")
            qualityLevel = newLevel
        }
    }

    /**
     * 生成性能报告
     */
    fun generateReport(): String {
        return synchronized(lock) {
            val now = Clock.System.now().toEpochMilliseconds()
            val duration = (now - lastReportTime) / 1000.0
            val avgTime = getAverageProcessingTime()
            val maxTime = getMaxProcessingTime()
            val errorRate = if (totalProcessed > 0) {
                (totalErrors * 100.0 / totalProcessed).roundToInt() / 100.0
            } else 0.0

            buildString {
                appendLine("=== $name 性能报告 ===")
                appendLine("处理总数: $totalProcessed")
                appendLine("错误总数: $totalErrors (${errorRate}%)")
                appendLine("平均处理时间: ${avgTime}ms")
                appendLine("最大处理时间: ${maxTime}ms")
                appendLine("当前质量级别: $qualityLevel")
                appendLine("运行时长: ${duration}秒")
            }
        }
    }

    /**
     * 重置统计
     */
    fun reset() {
        synchronized(lock) {
            processingTimes.clear()
            totalProcessed = 0
            totalErrors = 0
            lastReportTime = Clock.System.now().toEpochMilliseconds()
            logger.info("[$name] 性能统计已重置")
        }
    }
}
