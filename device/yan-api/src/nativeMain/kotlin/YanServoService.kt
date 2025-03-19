package com.airobot.device.yanapi

import com.airobot.pythoninterop.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.*

/**
 * YAN设备舵机服务
 *
 * 提供机器人的舵机控制功能，如关节控制、姿态调整等
 */
@OptIn(ExperimentalForeignApi::class)
class YanServoService {
    /**
     * 设置舵机角度
     *
     * @param servoId 舵机ID
     * @param angle 角度值，范围0-180
     * @return 操作是否成功
     */
    fun setServoAngle(servoId: Int, angle: Int): Boolean {
        try {
            memScoped {
                // 创建一个包含舵机ID和角度的字典
                val pyDict = PyDict_New()
                val pyServoId = PyLong_FromLong(servoId.toLong())
                val pyAngle = PyLong_FromLong(angle.toLong())
                PyDict_SetItem(pyDict, pyServoId, pyAngle)
                
                // 使用set_servos_angles_layers函数设置舵机角度
                val result = set_servos_angles_layers(pyDict, 0)
                return result != null && PyObject_IsTrue(result) == 1
            }
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 获取舵机角度
     *
     * @param servoId 舵机ID
     * @return 当前角度值，失败返回-1
     */
    fun getServoAngle(servoId: Int): Int {
        try {
            memScoped {
                val pyServoId = PyLong_FromLong(servoId.toLong())
                val result = get_servo_angle_value(pyServoId,0)
                if (result != null) {
                    return PyLong_AsLong(result).toInt()
                }
                return -1
            }
        } catch (e: Exception) {
            return -1
        }
    }

    /**
     * 获取舵机模式
     *
     * @param servoIds 舵机ID列表
     * @return 舵机模式信息
     */
    fun getServosMode(servoIds: List<Int>): Map<String, Any> {
        try {
            memScoped {
                // 创建一个包含舵机ID的列表
                val pyList = PyList_New(servoIds.size.toLong())
                for (i in servoIds.indices) {
                    val pyServoId = PyLong_FromLong(servoIds[i].toLong())
                    PyList_SetItem(pyList, i.toLong(), pyServoId)
                }
                
                // 使用get_servos_mode函数获取舵机模式
                val result = get_servos_mode(pyList, 0)
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
     * 设置舵机模式
     *
     * @param servoIds 舵机ID列表
     * @param mode 模式值
     * @return 操作是否成功
     */
    fun setServosMode(servoIds: List<Int>, mode: Int): Boolean {
        try {
            memScoped {
                // 创建一个包含舵机ID的列表
                val pyList = PyList_New(servoIds.size.toLong())
                for (i in servoIds.indices) {
                    val pyServoId = PyLong_FromLong(servoIds[i].toLong())
                    PyList_SetItem(pyList, i.toLong(), pyServoId)
                }
                
                val pyMode = PyLong_FromLong(mode.toLong())
                
                // 使用set_servos_mode函数设置舵机模式
                val result = set_servos_mode(pyList, pyMode, 0)
                return result != null && PyObject_IsTrue(result) == 1
            }
        } catch (e: Exception) {
            return false
        }
    }
}