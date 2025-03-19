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
     * 获取机器人版本信息
     *
     * @param type 版本类型
     * @return 版本信息
     */
    fun getRobotVersionInfo(type: String): Map<String, Any> {
        try {
            memScoped {
                val pyType = PyUnicodeObject(type.cstr.ptr.rawValue)
                val result = get_robot_version_info(pyType.reinterpret<PyObject>().ptr,0)
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
     * 获取机器人模式
     *
     * @return 机器人模式
     */
    fun getRobotMode(): Int {
        try {
            val result = get_robot_mode(0)
            if (result != null) {
                return PyLong_AsLong(result).toInt()
            }
            return -1
        } catch (e: Exception) {
            return -1
        }
    }

    /**
     * 获取机器人音量
     *
     * @return 音量值，范围0-100
     */
    fun getRobotVolume(): Int {
        try {
            val result = get_robot_volume(0)
            if (result != null) {
                return PyLong_AsLong(result).toInt()
            }
            return -1
        } catch (e: Exception) {
            return -1
        }
    }
    
    /**
     * 设置机器人音量
     *
     * @param volume 音量值，范围0-100
     * @return 操作是否成功
     */
    fun setRobotVolume(volume: Int): Boolean {
        try {
            memScoped {
                val pyVolume = PyLong_FromLong(volume.toLong())
                val result = set_robot_volume(pyVolume,0)
                return result != null && PyObject_IsTrue(result) == 1
            }
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 获取机器人语言
     *
     * @return 语言代码
     */
    fun getRobotLanguage(): Int {
        try {
            val result = get_robot_language(0)
            if (result != null) {
                return PyLong_AsLong(result).toInt()
            }
            return -1
        } catch (e: Exception) {
            return -1
        }
    }
    
    /**
     * 设置机器人语言
     *
     * @param language 语言代码
     * @return 操作是否成功
     */
    fun setRobotLanguage(language: Int): Boolean {
        try {
            memScoped {
                val pyLanguage = PyLong_FromLong(language.toLong())
                val result = set_robot_language(pyLanguage, 0)
                return result != null && PyObject_IsTrue(result) == 1
            }
        } catch (e: Exception) {
            return false
        }
    }

    // 注意：get_hardware_info函数在YanAPI.h中不存在，已移除相关方法
}