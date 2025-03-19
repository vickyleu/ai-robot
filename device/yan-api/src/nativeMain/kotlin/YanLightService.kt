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
     * @param mode 灯光模式
     * @return 操作是否成功
     */
    fun setLight(position: String, color: Int, mode: Int = 0): Boolean {
        try {
            memScoped {
                val pyPosition = PyUnicodeObject(position.cstr.ptr.rawValue)
                val pyColor = PyLong_FromLong(color.toLong())
                val pyMode = PyLong_FromLong(mode.toLong())
                val result = set_robot_led(pyPosition.reinterpret<PyObject>().ptr, pyColor, pyMode,0)
                return result != null && PyObject_IsTrue(result) == 1
            }
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 关闭灯光
     * 
     * 注意：使用set_robot_led函数实现，将颜色设置为0（黑色）来关闭灯光
     *
     * @param position 灯光位置，如"head", "chest", "arm"等
     * @return 操作是否成功
     */
    fun turnOff(position: String): Boolean {
        try {
            memScoped {
                val pyPosition = PyUnicodeObject(position.cstr.ptr.rawValue)
                val pyColor = PyLong_FromLong(0) // 颜色设为0（黑色）表示关闭灯光
                val pyMode = PyLong_FromLong(0)
                val result = set_robot_led(pyPosition.reinterpret<PyObject>().ptr, pyColor, pyMode,0)
                return result != null && PyObject_IsTrue(result) == 1
            }
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 获取眼睛LED颜色值
     *
     * @return 眼睛LED颜色值
     */
    fun getEyeLedColorValue(): Int {
        try {
            val result = get_eye_led_color_value(0)
            if (result != null) {
                return PyLong_AsLong(result).toInt()
            }
            return 0
        } catch (e: Exception) {
            return 0
        }
    }
    
    /**
     * 获取眼睛LED模式值
     *
     * @return 眼睛LED模式值
     */
    fun getEyeLedModeValue(): Int {
        try {
            val result = get_eye_led_mode_value(0)
            if (result != null) {
                return PyLong_AsLong(result).toInt()
            }
            return 0
        } catch (e: Exception) {
            return 0
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
     * 获取按钮LED颜色值
     *
     * @return 按钮LED颜色值
     */
    fun getButtonLedColorValue(): Int {
        try {
            val result = get_button_led_color_value(0)
            if (result != null) {
                return PyLong_AsLong(result).toInt()
            }
            return 0
        } catch (e: Exception) {
            return 0
        }
    }
    
    /**
     * 获取按钮LED模式值
     *
     * @return 按钮LED模式值
     */
    fun getButtonLedModeValue(): Int {
        try {
            val result = get_button_led_mode_value(0)
            if (result != null) {
                return PyLong_AsLong(result).toInt()
            }
            return 0
        } catch (e: Exception) {
            return 0
        }
    }

    /**
     * 获取所有灯光状态
     *
     * @return 所有灯光状态信息
     */
    fun getAllLightsStatus(): Map<String, Any> {
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
     * 同步设置灯光
     *
     * @param position 灯光位置，如"head", "chest", "arm"等
     * @param color RGB颜色值
     * @param mode 灯光模式
     * @return 操作是否成功
     */
    fun syncSetLight(position: String, color: Int, mode: Int = 0): Boolean {
        try {
            memScoped {
                val pyPosition = PyUnicodeObject(position.cstr.ptr.rawValue)
                val pyColor = PyLong_FromLong(color.toLong())
                val pyMode = PyLong_FromLong(mode.toLong())
                val result = sync_set_led(pyPosition.reinterpret<PyObject>().ptr, pyColor, pyMode,0)
                return result != null && PyObject_IsTrue(result) == 1
            }
        } catch (e: Exception) {
            return false
        }
    }
}