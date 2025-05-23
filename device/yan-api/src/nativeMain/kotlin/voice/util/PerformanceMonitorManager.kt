package voice.util

import com.airobot.device.yanapi.voice.util.PerformanceMonitor
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * 全局性能监控管理器
 */
object PerformanceMonitorManager {
    private val monitors = mutableMapOf<String, PerformanceMonitor>()
    private val lock = SynchronizedObject()

    fun getMonitor(name: String): PerformanceMonitor {
        return synchronized(lock) {
            monitors.getOrPut(name) { PerformanceMonitor(name) }
        }
    }

    fun generateGlobalReport(): String {
        return synchronized(lock) {
            buildString {
                appendLine("==== 全局性能报告 ====")
                monitors.forEach { (_, monitor) ->
                    appendLine(monitor.generateReport())
                    appendLine()
                }
            }
        }
    }
}