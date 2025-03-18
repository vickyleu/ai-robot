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
                val pyServoId = PyLong_FromLong(servoId.toLong())
                val pyAngle = PyLong_FromLong(angle.toLong())
                val result = set_servo_angle(pyServoId, pyAngle)
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
                val result = get_servo_angle(pyServoId)
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
     * 设置舵机速度
     *
     * @param servoId 舵机ID
     * @param speed 速度值，范围0-100
     * @return 操作是否成功
     */
    fun setServoSpeed(servoId: Int, speed: Int): Boolean {
        try {
            memScoped {
                val pyServoId = PyLong_FromLong(servoId.toLong())
                val pySpeed = PyLong_FromLong(speed.toLong())
                val result = set_servo_speed(pyServoId, pySpeed)
                return result != null && PyObject_IsTrue(result) == 1
            }
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 获取舵机速度
     *
     * @param servoId 舵机ID
     * @return 当前速度值，失败返回-1
     */
    fun getServoSpeed(servoId: Int): Int {
        try {
            memScoped {
                val pyServoId = PyLong_FromLong(servoId.toLong())
                val result = get_servo_speed(pyServoId)
                if (result != null) {
                    return PyLong_AsLong(result).toInt()
                }
                return -1
            }
        } catch (e: Exception) {
            return -1
        }
    }
}