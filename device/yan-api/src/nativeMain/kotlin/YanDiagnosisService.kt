@file:OptIn(ExperimentalForeignApi::class)
@file:Suppress("UNCHECKED_CAST")
package com.airobot.device.yanapi

import com.airobot.pythoninterop.PyLong_FromLong
import com.airobot.pythoninterop.PyUnicode_AsUTF8
import com.airobot.pythoninterop.PyUnicode_FromString
import com.airobot.pythoninterop.get_robot_language
import com.airobot.pythoninterop.get_robot_mode
import com.airobot.pythoninterop.get_robot_version_info
import com.airobot.pythoninterop.get_robot_volume
import com.airobot.pythoninterop.set_robot_language
import com.airobot.pythoninterop.set_robot_volume
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString

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
    fun getRobotVersionInfo(type: VersionType): String? {
        try {
            memScoped {
                val pyType = PyUnicode_FromString(type.value)
                val result = get_robot_version_info(pyType, 0)
                if (result != null) {
                    return PyUnicode_AsUTF8(result)?.toKString()
                }
                return null
            }
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 获取机器人模式
     *
     * @return 机器人模式
     */
    fun getRobotMode(): EnergyType? {
        try {
            val result = get_robot_mode(0)
            if (result != null) {
                return PyUnicode_AsUTF8(result)?.toKString()?.let {
                    when (it) {
                        EnergyType.ENERGY_SAVING_MODE.value -> EnergyType.ENERGY_SAVING_MODE
                        EnergyType.CALIBRATION_MODE.value -> EnergyType.CALIBRATION_MODE
                        else -> null
                    }
                }
            }
            return null
        } catch (e: Exception) {
            return null
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
                return ((PyObjectToMap(result)["data"]) as? Map<String, Any>)?.get("volume")
                    ?.toString()?.toIntOrNull() ?: -1
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
                val result = set_robot_volume(pyVolume, 0)
                return ((PyObjectToMap(result)["code"])?.toString()?.toIntOrNull() ?: -1) == 0
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
    fun getRobotLanguage(): String {
        try {
            val result = get_robot_language(0)
            if (result != null) {
                return ((PyObjectToMap(result)["data"]) as? Map<String, Any>)?.get("language")
                    ?.toString() ?: "zh"
            }
            return "zh"
        } catch (e: Exception) {
            return "zh"
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
                return ((PyObjectToMap(result)["code"])?.toString()?.toIntOrNull() ?: -1) == 0
            }
        } catch (e: Exception) {
            return false
        }
    }

    // 注意：get_hardware_info函数在YanAPI.h中不存在，已移除相关方法
}

enum class VersionType(val value: String) {
    CORE("core"),
    SERVO("servo"),
    SN("sn");
}

enum class EnergyType(val value: String) {
    ENERGY_SAVING_MODE("energy_saving_mode"),
    CALIBRATION_MODE("calibration_mode");
}