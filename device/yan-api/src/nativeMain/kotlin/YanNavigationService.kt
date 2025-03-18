package com.airobot.device.yanapi

import com.airobot.pythoninterop.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.*

/**
 * YAN设备导航服务
 *
 * 提供机器人的导航功能，如路径规划、定位等
 */
@OptIn(ExperimentalForeignApi::class)
class YanNavigationService {
    /**
     * 导航到指定位置
     *
     * @param x X坐标
     * @param y Y坐标
     * @return 操作是否成功
     */
    fun navigateTo(x: Double, y: Double): Boolean {
        try {
            memScoped {
                val pyX = PyFloat_FromDouble(x)
                val pyY = PyFloat_FromDouble(y)
                val result = navigate_to_position(pyX, pyY)
                return result != null && PyObject_IsTrue(result) == 1
            }
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 获取当前位置
     *
     * @return 当前位置坐标，失败返回null
     */
    fun getCurrentPosition(): Pair<Double, Double>? {
        try {
            val result = get_current_position()
            if (result != null) {
                // 假设返回的是一个包含x,y坐标的元组
                val xObj = PyTuple_GetItem(result, 0)
                val yObj = PyTuple_GetItem(result, 1)
                
                if (xObj != null && yObj != null) {
                    val x = PyFloat_AsDouble(xObj)
                    val y = PyFloat_AsDouble(yObj)
                    return Pair(x, y)
                }
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 停止导航
     *
     * @return 操作是否成功
     */
    fun stopNavigation(): Boolean {
        try {
            val result = stop_navigation()
            return result != null && PyObject_IsTrue(result) == 1
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 设置导航速度
     *
     * @param speed 速度值，范围0-100
     * @return 操作是否成功
     */
    fun setNavigationSpeed(speed: Int): Boolean {
        try {
            memScoped {
                val pySpeed = PyLong_FromLong(speed.toLong())
                val result = set_navigation_speed(pySpeed)
                return result != null && PyObject_IsTrue(result) == 1
            }
        } catch (e: Exception) {
            return false
        }
    }
}