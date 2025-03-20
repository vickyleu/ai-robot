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
            val result = get_robot_battery_value(0)
            if (result != null) {
                return PyLong_AsLong(result).toInt().coerceAtLeast(-1)
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
        // 注意：is_charging函数在YanAPI.h中不存在
        // 可以通过get_robot_battery_info获取充电状态
        try {
            val batteryInfo = get_robot_battery_info(0)
            if (batteryInfo != null) {
                val data = PyObjectToMap(batteryInfo)["data"] as? Map<String, Any>
                return data?.get("charging") as? Int == 1
            }
            return false
        } catch (e: Exception) {
            return false
        }
    }

    // 注意：以下函数在YanAPI.h中不存在，已移除相关方法
    // - get_battery_temperature
    // - get_battery_health
    // - get_remaining_time

    /**
     * 获取电源状态详情
     *
     * @return 电源状态详情，包含电量、充电状态、温度等信息
     */
    fun getPowerStatus(): Map<String, Any> {
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
}