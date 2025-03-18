package com.airobot.device.yanapi

import com.airobot.pythoninterop.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.*

/**
 * YAN设备诊断服务
 *
 * 提供机器人的自我诊断功能，如硬件检测、系统状态检查等
 */
@OptIn(ExperimentalForeignApi::class)
class YanDiagnosisService {
    /**
     * 执行系统自检
     *
     * @return 自检结果，包含各个子系统的状态
     */
    fun runSystemCheck(): Map<String, Any> {
        try {
            val result = run_system_check()
            if (result != null) {
                return PyObjectToMap(result)
            }
            return emptyMap()
        } catch (e: Exception) {
            return emptyMap()
        }
    }

    /**
     * 检查特定子系统
     *
     * @param subsystem 子系统名称，如"motor", "sensor", "battery"等
     * @return 检查结果
     */
    fun checkSubsystem(subsystem: String): Map<String, Any> {
        try {
            memScoped {
                val pySubsystem = PyUnicodeObject(subsystem.cstr.ptr.rawValue)
                val result = check_subsystem(pySubsystem.reinterpret<PyObject>().ptr)
                if (result != null) {
                    return PyObjectToMap(result)
                }
                return emptyMap()
            }
        } catch (e: Exception) {
            return emptyMap()
        }
    }

    /**
     * 获取系统日志
     *
     * @param logType 日志类型，如"error", "warning", "info"等
     * @param count 日志条数
     * @return 日志内容
     */
    fun getSystemLogs(logType: String, count: Int): List<String> {
        try {
            memScoped {
                val pyLogType = PyUnicodeObject(logType.cstr.ptr.rawValue)
                val pyCount = PyLong_FromLong(count.toLong())
                val result = get_system_logs(pyLogType.reinterpret<PyObject>().ptr, pyCount)
                
                if (result != null) {
                    val logs = mutableListOf<String>()
                    val size = PyList_Size(result)
                    
                    for (i in 0 until size) {
                        val item = PyList_GetItem(result, i)
                        if (item != null) {
                            val pyStr = PyUnicode_AsUTF8(item)
                            pyStr?.toKString()?.let { logs.add(it) }
                        }
                    }
                    
                    return logs
                }
                return emptyList()
            }
        } catch (e: Exception) {
            return emptyList()
        }
    }

    /**
     * 获取系统状态
     *
     * @return 系统状态信息，包含CPU使用率、内存使用率等
     */
    fun getSystemStatus(): Map<String, Any> {
        try {
            val result = get_system_status()
            if (result != null) {
                return PyObjectToMap(result)
            }
            return emptyMap()
        } catch (e: Exception) {
            return emptyMap()
        }
    }

    /**
     * 获取硬件信息
     *
     * @return 硬件信息，包含设备型号、序列号、固件版本等
     */
    fun getHardwareInfo(): Map<String, Any> {
        try {
            val result = get_hardware_info()
            if (result != null) {
                return PyObjectToMap(result)
            }
            return emptyMap()
        } catch (e: Exception) {
            return emptyMap()
        }
    }
}