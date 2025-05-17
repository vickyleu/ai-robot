package voice.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.datetime.Clock.System
import platform.posix.fflush
import platform.posix.fprintf
import platform.posix.stderr
import platform.posix.stdout
import kotlin.time.ExperimentalTime
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * 日志管理器
 * 提供统一的日志记录接口
 */
object LogManager {
    /**
     * 日志级别
     */
    enum class LogLevel {
        DEBUG, INFO, WARN, ERROR
    }
    
    // 默认日志级别
    private var defaultLogLevel = LogLevel.INFO
    
    /**
     * 设置默认日志级别
     */
    fun setLogLevel(level: LogLevel) {
        defaultLogLevel = level
    }
    
    /**
     * 获取日志记录器
     * @param tag 日志标签
     * @return 日志记录器
     */
    fun getLogger(tag: String): Logger {
        return Logger(tag)
    }
    
    /**
     * 日志记录器
     * @param tag 日志标签
     */
    class Logger(private val tag: String) {
        /**
         * 记录调试日志
         */
        fun debug(message: String) {
            if (defaultLogLevel.ordinal <= LogLevel.DEBUG.ordinal) {
                println("[DEBUG][$tag] $message")
            }
        }
        
        /**
         * 记录信息日志
         */
        fun info(message: String) {
            if (defaultLogLevel.ordinal <= LogLevel.INFO.ordinal) {
                println("[INFO][$tag] $message")
            }
        }
        
        /**
         * 记录警告日志
         */
        fun warn(message: String) {
            if (defaultLogLevel.ordinal <= LogLevel.WARN.ordinal) {
                println("[WARN][$tag] $message")
            }
        }
        
        /**
         * 记录错误日志
         */
        fun error(message: String) {
            if (defaultLogLevel.ordinal <= LogLevel.ERROR.ordinal) {
                println("[ERROR][$tag] $message")
            }
        }
    }
    
    /**
     * 日志诊断信息
     * 用于收集一段时间内的日志记录，并生成诊断报告
     */
    object Diagnostics {
        private val logBuffer = mutableListOf<LogEntry>()
        private const val MAX_BUFFER_SIZE = 1000
        
        private data class LogEntry(
            val timestamp: Long,
            val level: LogLevel,
            val tag: String,
            val message: String
        )
        
        /**
         * 添加日志条目
         */
        fun addLogEntry(timestamp: Long, level: LogLevel, tag: String, message: String) {
            // 在Kotlin/Native中不能使用synchronized，使用原子操作替代
            val currentBuffer = logBuffer.toMutableList()
            currentBuffer.add(LogEntry(timestamp, level, tag, message))
            if (currentBuffer.size > MAX_BUFFER_SIZE) {
                currentBuffer.removeAt(0)
            }
            logBuffer.clear()
            logBuffer.addAll(currentBuffer)
        }
        
        /**
         * 生成诊断报告
         */
        fun generateReport(): String {
            val report = StringBuilder()
            report.appendLine("=== 音频处理诊断报告 ===")
            report.appendLine("记录时间: ${TimeSource.Monotonic.markNow().toEpochMilliseconds()}")
            report.appendLine("日志条目数: ${logBuffer.size}")
            
            val errorCount = logBuffer.count { it.level == LogLevel.ERROR }
            val warnCount = logBuffer.count { it.level == LogLevel.WARN }
            
            report.appendLine("错误数: $errorCount")
            report.appendLine("警告数: $warnCount")
            report.appendLine()
            
            if (errorCount > 0) {
                report.appendLine("== 最近错误日志 ==")
                logBuffer.filter { it.level == LogLevel.ERROR }
                    .takeLast(10)
                    .forEach { entry ->
                        report.appendLine("[${entry.timestamp}] [${entry.tag}] ${entry.message}")
                    }
                report.appendLine()
            }
            
            report.appendLine("== 最近日志摘要 ==")
            logBuffer.takeLast(50).forEach { entry ->
                report.appendLine("[${entry.timestamp}] [${entry.level}] [${entry.tag}] ${entry.message}")
            }
            
            return report.toString()
        }
        
        /**
         * 清除日志缓冲区
         */
        fun clearBuffer() {
            // 在Kotlin/Native中不能使用synchronized，直接清除
            logBuffer.clear()
        }
    }
    
    /**
     * 获取当前时间的毫秒值
     */
    fun getCurrentTimeMillis(): Long {
        return TimeSource.Monotonic.markNow().toEpochMilliseconds()
    }
}

/**
 * 扩展函数：将TimeSource.Monotonic.TimeMark转换为纪元毫秒
 */
@OptIn(ExperimentalTime::class)
fun TimeMark.toEpochMilliseconds(): Long {
    return System.now().toEpochMilliseconds() + (elapsedNow().inWholeMilliseconds)
} 