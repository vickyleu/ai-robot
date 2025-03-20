@file:OptIn(ExperimentalForeignApi::class)

package com.airobot.device.yanapi

import com.airobot.pythoninterop.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.*

/**
 * YAN设备状态监控服务
 *
 * 提供机器人的设备状态监控功能，如电池电量、网络状态、运行状态等
 */
@OptIn(ExperimentalForeignApi::class)
class YanDeviceStatusService {
    /**
     * 初始化YAN API
     *
     * @return 操作是否成功
     */
    fun initApi(): Boolean {
        try {
            val result = yan_api_init(null)
            return result != null && PyObject_IsTrue(result) == 1
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 获取设备IP地址
     *
     * @return IP地址，失败返回null
     */
    fun getIpAddress(): String? {
        try {
            val result = get_ip_address(null)
            if (result != null) {
                val pyStr = PyUnicode_AsUTF8(result)
                return pyStr?.toKString()
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 获取电池详细信息
     *
     * @return 电池信息，包含电量、电压等参数
     */
    fun getBatteryInfo(): Map<String, Any> {
        try {
            val result = get_robot_battery_info(0)
            if (result != null) {
                return PyObjectToMap(result)
            }
            return emptyMap()
        } catch (e: Exception) {
            return emptyMap()
        }
    }

    /**
     * 获取电池电量值
     *
     * @return 电池电量值，失败返回-1
     */
    fun getBatteryValue(): Int {
        try {
            val result = get_robot_battery_value(0)
            if (result != null) {
                return PyLong_AsLong(result).toInt()
            }
            return -1
        } catch (e: Exception) {
            return -1
        }
    }

    /**
     * 获取设备状态
     *
     * @return 设备状态信息，包含各种状态参数
     */
    fun getDeviceStatus(): Map<String, Any> {
        // 组合多个API调用的结果来提供设备状态信息
        val statusData = mutableMapOf<String, Any>()
        
        // 添加电池信息
        try {
            val batteryInfo = getBatteryInfo()
            if (batteryInfo.isNotEmpty()) {
                statusData["battery"] = batteryInfo
            }
        } catch (e: Exception) {
            // 忽略错误
        }
        
        // 添加IP地址信息
        try {
            val ipAddress = getIpAddress()
            if (ipAddress != null) {
                statusData["ip_address"] = ipAddress
            }
        } catch (e: Exception) {
            // 忽略错误
        }
        
        // 添加LED状态信息
        try {
            val ledInfo = getRobotLed()
            if (ledInfo.isNotEmpty()) {
                statusData["led_status"] = ledInfo
            }
        } catch (e: Exception) {
            // 忽略错误
        }
        
        return statusData
    }
    
    /**
     * 获取电池电量
     *
     * @return 电池电量百分比，范围0-100，失败返回-1
     */
    fun getBatteryLevel(): Int {
        // 使用get_robot_battery_value函数获取电池电量
        return getBatteryValue()
    }
    
    /**
     * 获取网络状态
     *
     * @return 网络状态信息
     */
    fun getNetworkStatus(): Map<String, Any> {
        // 创建一个包含网络状态信息的Map
        val networkStatus = mutableMapOf<String, Any>()
        
        // 添加IP地址信息
        try {
            val ipAddress = getIpAddress()
            if (ipAddress != null) {
                networkStatus["ip_address"] = ipAddress
                networkStatus["connected"] = true
            } else {
                networkStatus["connected"] = false
            }
        } catch (e: Exception) {
            networkStatus["connected"] = false
            networkStatus["error"] = e.message ?: "Unknown error"
        }
        
        return networkStatus
    }

    /**
     * 获取按钮LED颜色值
     *
     * @return LED颜色值，失败返回-1
     */
    fun getButtonLedColorValue(): Int {
        try {
            val result = get_button_led_color_value(0)
            if (result != null) {
                return PyLong_AsLong(result).toInt()
            }
            return -1
        } catch (e: Exception) {
            return -1
        }
    }

    /**
     * 获取按钮LED模式值
     *
     * @return LED模式值，失败返回-1
     */
    fun getButtonLedModeValue(): Int {
        try {
            val result = get_button_led_mode_value(0)
            if (result != null) {
                return PyLong_AsLong(result).toInt()
            }
            return -1
        } catch (e: Exception) {
            return -1
        }
    }

    /**
     * 获取眼睛LED颜色值
     *
     * @return LED颜色值，失败返回-1
     */
    fun getEyeLedColorValue(): Int {
        try {
            val result = get_eye_led_color_value(0)
            if (result != null) {
                return PyLong_AsLong(result).toInt()
            }
            return -1
        } catch (e: Exception) {
            return -1
        }
    }

    /**
     * 获取眼睛LED模式值
     *
     * @return LED模式值，失败返回-1
     */
    fun getEyeLedModeValue(): Int {
        try {
            val result = get_eye_led_mode_value(0)
            if (result != null) {
                return PyLong_AsLong(result).toInt()
            }
            return -1
        } catch (e: Exception) {
            return -1
        }
    }

    /**
     * 获取机器人LED状态
     *
     * @return LED状态信息
     */
    fun getRobotLed(): Map<String, Any> {
        try {
            val result = get_robot_led(0)
            if (result != null) {
                return PyObjectToMap(result)
            }
            return emptyMap()
        } catch (e: Exception) {
            return emptyMap()
        }
    }

    /**
     * 设置机器人LED
     *
     * @param mode LED模式
     * @param color LED颜色
     * @param speed LED速度
     * @return 操作是否成功
     */
    fun setRobotLed(mode: String, color: String, speed: String): Boolean {
        try {
            memScoped {
                val pyMode = PyUnicode_FromString(mode)
                val pyColor = PyUnicode_FromString(color)
                val pySpeed = PyUnicode_FromString(speed)
                val result = set_robot_led(
                    pyMode,
                    pyColor,
                    pySpeed,
                    0
                )
                return result != null && PyObject_IsTrue(result) == 1
            }
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 同步设置LED
     *
     * @param mode LED模式
     * @param color LED颜色
     * @param speed LED速度
     * @return 操作是否成功
     */
    fun syncSetLed(mode: String, color: String, speed: String): Boolean {
        try {
            memScoped {
                val pyMode = PyUnicode_FromString(mode)
                val pyColor = PyUnicode_FromString(color)
                val pySpeed = PyUnicode_FromString(speed)
                val result = sync_set_led(
                    pyMode,
                    pyColor,
                    pySpeed,
                    0
                )
                return result != null && PyObject_IsTrue(result) == 1
            }
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 获取机器人版本信息值
     *
     * @param type 版本信息类型
     * @return 版本信息值
     */
    fun getRobotVersionInfoValue(type: String): String? {
        try {
            memScoped {
                val pyType = PyUnicode_FromString(type)
                val result = get_robot_version_info_value(pyType, 0)
                if (result != null) {
                    val pyStr = PyUnicode_AsUTF8(result)
                    return pyStr?.toKString()
                }
                return null
            }
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 获取机器人版本信息
     *
     * @param type 版本信息类型
     * @return 版本信息
     */
    fun getRobotVersionInfo(type: String): Map<String, Any> {
        try {
            memScoped {
                val pyType = PyUnicode_FromString(type)
                val result = get_robot_version_info(pyType, 0)
                if (result != null) {
                    return PyObjectToMap(result)
                }
                return emptyMap()
            }
        } catch (e: Exception) {
            return emptyMap()
        }
    }
}