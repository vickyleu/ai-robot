package com.airobot.device.yanapi

import com.airobot.pythoninterop.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.*

/**
 * YAN设备灯光服务
 *
 * 提供机器人的灯光控制功能，如设置灯光颜色、开关灯等
 */
@OptIn(ExperimentalForeignApi::class)
class YanLightService {
    /**
     * 设置灯光
     *
     * @param position 灯光位置，如"head", "chest", "arm"等
     * @param color RGB颜色值
     * @return 操作是否成功
     */
    fun setLight(position: String, color: Int): Boolean {
        try {
            memScoped {
                val pyPosition = PyUnicodeObject(position.cstr.ptr.rawValue)
                val pyColor = PyLong_FromLong(color.toLong())
                val result = set_light(pyPosition.reinterpret<PyObject>().ptr, pyColor)
                return result != null && PyObject_IsTrue(result) == 1
            }
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 关闭灯光
     *
     * @param position 灯光位置，如"head", "chest", "arm"等
     * @return 操作是否成功
     */
    fun turnOff(position: String): Boolean {
        try {
            memScoped {
                val pyPosition = PyUnicodeObject(position.cstr.ptr.rawValue)
                val result = turn_off_light(pyPosition.reinterpret<PyObject>().ptr)
                return result != null && PyObject_IsTrue(result) == 1
            }
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 设置灯光亮度
     *
     * @param position 灯光位置
     * @param brightness 亮度值，范围0-100
     * @return 操作是否成功
     */
    fun setBrightness(position: String, brightness: Int): Boolean {
        try {
            memScoped {
                val pyPosition = PyUnicodeObject(position.cstr.ptr.rawValue)
                val pyBrightness = PyLong_FromLong(brightness.toLong())
                val result = set_light_brightness(pyPosition.reinterpret<PyObject>().ptr, pyBrightness)
                return result != null && PyObject_IsTrue(result) == 1
            }
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 获取灯光状态
     *
     * @param position 灯光位置
     * @return 灯光状态信息，包含颜色、亮度等
     */
    fun getLightStatus(position: String): Map<String, Any> {
        try {
            memScoped {
                val pyPosition = PyUnicodeObject(position.cstr.ptr.rawValue)
                val result = get_light_status(pyPosition.reinterpret<PyObject>().ptr)
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
     * 获取所有灯光状态
     *
     * @return 所有灯光状态信息
     */
    fun getAllLightsStatus(): Map<String, Any> {
        try {
            val result = get_all_lights_status()
            if (result != null) {
                return PyObjectToMap(result)
            }
            return emptyMap()
        } catch (e: Exception) {
            return emptyMap()
        }
    }
}