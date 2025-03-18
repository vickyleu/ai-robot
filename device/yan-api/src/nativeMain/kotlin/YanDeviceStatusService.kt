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
        try {
            val result = get_device_status()
            if (result != null) {
                val statusData = mutableMapOf<String, Any>()
                
                // 获取字典的键列表
                val keys = PyDict_Keys(result)
                if (keys != null) {
                    val size = PyList_Size(keys)
                    
                    for (i in 0 until size) {
                        val key = PyList_GetItem(keys, i)
                        val value = PyDict_GetItem(result, key)
                        
                        if (key != null && value != null) {
                            val keyStr = PyUnicode_AsUTF8(key)?.toKString() ?: continue
                            
                            // 根据值的类型进行相应的转换
                            when {
                                PyFloat_Check(value) != 0 -> {
                                    statusData[keyStr] = PyFloat_AsDouble(value)
                                }
                                PyLong_Check(value) != 0 -> {
                                    statusData[keyStr] = PyLong_AsLong(value)
                                }
                                PyUnicode_Check(value) != 0 -> {
                                    val valueStr = PyUnicode_AsUTF8(value)?.toKString() ?: continue
                                    statusData[keyStr] = valueStr
                                }
                                PyBool_Check(value) != 0 -> {
                                    statusData[keyStr] = PyObject_IsTrue(value) == 1
                                }
                            }
                        }
                    }
                }
                
                return statusData
            }
            return emptyMap()
        } catch (e: Exception) {
            return emptyMap()
        }
    }
    
    /**
     * 获取电池电量
     *
     * @return 电池电量百分比，范围0-100，失败返回-1
     */
    fun getBatteryLevel(): Int {
        try {
            val result = get_battery_level()
            if (result != null) {
                return PyLong_AsLong(result).toInt()
            }
            return -1
        } catch (e: Exception) {
            return -1
        }
    }
    
    /**
     * 获取网络状态
     *
     * @return 网络状态信息
     */
    fun getNetworkStatus(): Map<String, Any> {
        try {
            val result = get_network_status()
            if (result != null) {
                val networkStatus = mutableMapOf<String, Any>()
                
                // 获取字典的键列表
                val keys = PyDict_Keys(result)
                if (keys != null) {
                    val size = PyList_Size(keys)
                    
                    for (i in 0 until size) {
                        val key = PyList_GetItem(keys, i)
                        val value = PyDict_GetItem(result, key)
                        
                        if (key != null && value != null) {
                            val keyStr = PyUnicode_AsUTF8(key)?.toKString() ?: continue
                            
                            // 根据值的类型进行相应的转换
                            when {
                                PyBool_Check(value) != 0 -> {
                                    networkStatus[keyStr] = PyObject_IsTrue(value) == 1
                                }
                                PyUnicode_Check(value) != 0 -> {
                                    val valueStr = PyUnicode_AsUTF8(value)?.toKString() ?: continue
                                    networkStatus[keyStr] = valueStr
                                }
                                PyLong_Check(value) != 0 -> {
                                    networkStatus[keyStr] = PyLong_AsLong(value)
                                }
                            }
                        }
                    }
                }
                
                return networkStatus
            }
            return emptyMap()
        } catch (e: Exception) {
            return emptyMap()
        }
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
                val pyMode = PyUnicodeObject(mode.cstr.ptr.rawValue)
                val pyColor = PyUnicodeObject(color.cstr.ptr.rawValue)
                val pySpeed = PyUnicodeObject(speed.cstr.ptr.rawValue)
                val result = set_robot_led(
                    pyMode.reinterpret<PyObject>().ptr,
                    pyColor.reinterpret<PyObject>().ptr,
                    pySpeed.reinterpret<PyObject>().ptr,
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
                val pyMode = PyUnicodeObject(mode.cstr.ptr.rawValue)
                val pyColor = PyUnicodeObject(color.cstr.ptr.rawValue)
                val pySpeed = PyUnicodeObject(speed.cstr.ptr.rawValue)
                val result = sync_set_led(
                    pyMode.reinterpret<PyObject>().ptr,
                    pyColor.reinterpret<PyObject>().ptr,
                    pySpeed.reinterpret<PyObject>().ptr,
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
                val pyType = PyUnicodeObject(type.cstr.ptr.rawValue)
                val result = get_robot_version_info_value(pyType.reinterpret<PyObject>().ptr, 0)
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
                val pyType = PyUnicodeObject(type.cstr.ptr.rawValue)
                val result = get_robot_version_info(pyType.reinterpret<PyObject>().ptr, 0)
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