package com.airobot.device.yanapi

import com.airobot.pythoninterop.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.*

/**
 * YAN设备电源服务
 *
 * 提供机器人的电源管理功能，如电池电量查询、充电状态监控等
 */
@OptIn(ExperimentalForeignApi::class)
class YanPowerService {
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
     * 检查是否正在充电
     *
     * @return 是否正在充电
     */
    fun isCharging(): Boolean {
        try {
            val result = is_charging()
            return result != null && PyObject_IsTrue(result) == 1
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 获取电池温度
     *
     * @return 电池温度（摄氏度），失败返回-1
     */
    fun getBatteryTemperature(): Float {
        try {
            val result = get_battery_temperature()
            if (result != null) {
                return PyFloat_AsDouble(result).toFloat()
            }
            return -1f
        } catch (e: Exception) {
            return -1f
        }
    }

    /**
     * 获取电池健康状态
     *
     * @return 电池健康状态百分比，范围0-100，失败返回-1
     */
    fun getBatteryHealth(): Int {
        try {
            val result = get_battery_health()
            if (result != null) {
                return PyLong_AsLong(result).toInt()
            }
            return -1
        } catch (e: Exception) {
            return -1
        }
    }

    /**
     * 获取预计剩余使用时间
     *
     * @return 预计剩余使用时间（分钟），失败返回-1
     */
    fun getRemainingTime(): Int {
        try {
            val result = get_remaining_time()
            if (result != null) {
                return PyLong_AsLong(result).toInt()
            }
            return -1
        } catch (e: Exception) {
            return -1
        }
    }

    /**
     * 获取电源状态详情
     *
     * @return 电源状态详情，包含电量、充电状态、温度等信息
     */
    fun getPowerStatus(): Map<String, Any> {
        try {
            val result = get_power_status()
            if (result != null) {
                return PyObjectToMap(result)
            }
            return emptyMap()
        } catch (e: Exception) {
            return emptyMap()
        }
    }
}