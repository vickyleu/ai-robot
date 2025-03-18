package com.airobot.device.yanapi

import com.airobot.pythoninterop.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.*

/**
 * YAN设备运动控制服务
 *
 * 提供机器人的运动控制功能，如行走、转向等
 */
@OptIn(ExperimentalForeignApi::class)
class YanLocomotionService {
    /**
     * 控制机器人前进
     *
     * @param speed 速度，范围0-100
     * @return 操作是否成功
     */
    fun moveForward(speed: Int): Boolean {
        try {
            val result = move_robot_forward(speed)
            return result != null && PyObject_IsTrue(result) == 1
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 控制机器人后退
     *
     * @param speed 速度，范围0-100
     * @return 操作是否成功
     */
    fun moveBackward(speed: Int): Boolean {
        try {
            val result = move_robot_backward(speed)
            return result != null && PyObject_IsTrue(result) == 1
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 控制机器人左转
     *
     * @param angle 角度，范围0-360
     * @return 操作是否成功
     */
    fun turnLeft(angle: Int): Boolean {
        try {
            val result = turn_robot_left(angle)
            return result != null && PyObject_IsTrue(result) == 1
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 控制机器人右转
     *
     * @param angle 角度，范围0-360
     * @return 操作是否成功
     */
    fun turnRight(angle: Int): Boolean {
        try {
            val result = turn_robot_right(angle)
            return result != null && PyObject_IsTrue(result) == 1
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 停止机器人运动
     *
     * @return 操作是否成功
     */
    fun stop(): Boolean {
        try {
            val result = stop_robot_motion()
            return result != null && PyObject_IsTrue(result) == 1
        } catch (e: Exception) {
            return false
        }
    }
}