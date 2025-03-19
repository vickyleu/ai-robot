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
     * 注意：使用control_motion_gait函数实现，speed_v参数为正值表示前进
     *
     * @param speed 速度，范围0-5
     * @return 操作是否成功
     */
    fun moveForward(speed: Int): Boolean {
        try {
            // 将用户输入的速度范围(0-100)映射到API接受的范围(0-5)
            val mappedSpeed = (speed.coerceIn(0, 100) * 5) / 100
            val result = memScoped {
                // 使用control_motion_gait函数，speed_v为正值表示前进
                val pySpeedV = PyLong_FromLong(mappedSpeed.toLong())
                val pySpeedH = PyLong_FromLong(0)
                val pySteps = PyLong_FromLong(0) // 0表示持续运动
                val pyPeriod = PyLong_FromLong(1)
                val pyWave = if (false)  my_Py_True() else  my_Py_False()
                
                control_motion_gait_impl(pySpeedV, pySpeedH, pySteps, pyPeriod, pyWave,0)
            }
            return result != null && PyObject_IsTrue(result) == 1
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 控制机器人后退
     *
     * 注意：使用control_motion_gait函数实现，speed_v参数为负值表示后退
     *
     * @param speed 速度，范围0-5
     * @return 操作是否成功
     */
    fun moveBackward(speed: Int): Boolean {
        try {
            // 将用户输入的速度范围(0-100)映射到API接受的范围(0-5)，并取负值表示后退
            val mappedSpeed = -((speed.coerceIn(0, 100) * 5) / 100)
            val result = memScoped {
                // 使用control_motion_gait函数，speed_v为负值表示后退
                val pySpeedV = PyLong_FromLong(mappedSpeed.toLong())
                val pySpeedH = PyLong_FromLong(0)
                val pySteps = PyLong_FromLong(0) // 0表示持续运动
                val pyPeriod = PyLong_FromLong(1)
                val pyWave = if (false)  my_Py_True() else  my_Py_False()
                
                control_motion_gait_impl(pySpeedV, pySpeedH, pySteps, pyPeriod, pyWave,0)
            }
            return result != null && PyObject_IsTrue(result) == 1
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 控制机器人左转
     *
     * 注意：使用control_motion_gait函数实现，speed_h参数为负值表示左转
     *
     * @param angle 角度，范围0-360
     * @return 操作是否成功
     */
    fun turnLeft(angle: Int): Boolean {
        try {
            // 将角度转换为适当的水平速度值，负值表示左转
            // 角度越大，转弯速度越快，最大为-5
            val turnSpeed = -((angle.coerceIn(0, 360) * 5) / 360).coerceAtMost(5)
            val result = memScoped {
                // 使用control_motion_gait函数，speed_h为负值表示左转
                val pySpeedV = PyLong_FromLong(0) // 垂直速度为0，表示原地转弯
                val pySpeedH = PyLong_FromLong(turnSpeed.toLong())
                val pySteps = PyLong_FromLong(angle.toLong()) // 步数与角度相关
                val pyPeriod = PyLong_FromLong(1)
                val pyWave = if (false)  my_Py_True() else  my_Py_False()
                
                control_motion_gait_impl(pySpeedV, pySpeedH, pySteps, pyPeriod, pyWave,0)
            }
            return result != null && PyObject_IsTrue(result) == 1
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 控制机器人右转
     *
     * 注意：使用control_motion_gait函数实现，speed_h参数为正值表示右转
     *
     * @param angle 角度，范围0-360
     * @return 操作是否成功
     */
    fun turnRight(angle: Int): Boolean {
        try {
            // 将角度转换为适当的水平速度值，正值表示右转
            // 角度越大，转弯速度越快，最大为5
            val turnSpeed = ((angle.coerceIn(0, 360) * 5) / 360).coerceAtMost(5)
            val result = memScoped {
                // 使用control_motion_gait函数，speed_h为正值表示右转
                val pySpeedV = PyLong_FromLong(0) // 垂直速度为0，表示原地转弯
                val pySpeedH = PyLong_FromLong(turnSpeed.toLong())
                val pySteps = PyLong_FromLong(angle.toLong()) // 步数与角度相关
                val pyPeriod = PyLong_FromLong(1)
                val pyWave = if (false)  my_Py_True() else  my_Py_False()
                
                control_motion_gait_impl(pySpeedV, pySpeedH, pySteps, pyPeriod, pyWave,0)
            }
            return result != null && PyObject_IsTrue(result) == 1
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 停止机器人运动
     *
     * 注意：使用control_motion_gait函数实现，period参数为0表示停止步态
     *
     * @return 操作是否成功
     */
    fun stop(): Boolean {
        try {
            val result = memScoped {
                // 使用control_motion_gait函数，period为0表示停止步态
                val pySpeedV = PyLong_FromLong(0)
                val pySpeedH = PyLong_FromLong(0)
                val pySteps = PyLong_FromLong(0)
                val pyPeriod = PyLong_FromLong(0) // 0表示停止步态
                val pyWave = if (false)  my_Py_True() else  my_Py_False()
                
                control_motion_gait_impl(pySpeedV, pySpeedH, pySteps, pyPeriod, pyWave,0)
            }
            return result != null && PyObject_IsTrue(result) == 1
        } catch (e: Exception) {
            return false
        }
    }
}