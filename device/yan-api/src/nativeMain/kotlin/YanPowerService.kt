@file:OptIn(ExperimentalForeignApi::class)
@file:Suppress("UNCHECKED_CAST")
package com.airobot.device.yanapi

import com.airobot.device.yanapi.pojo.BatteryInfo
import com.airobot.device.yanapi.pojo.YansheeResp
import com.airobot.device.yanapi.python.PyDict_Check
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
            return result
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
        memScoped {
            val gilState =PyGILState_Ensure()
            try {
                // 安全地调用get_robot_battery_info函数，添加异常捕获
                val batteryInfo = try {
                    get_robot_battery_info(0)
                } catch (e: Exception) {
                    println("Critical error calling get_robot_battery_info: ${e.message}")
                    null
                }
                // 安全检查：确保batteryInfo不为null
                if (batteryInfo == null) {
                    return false
                }
                // 安全地获取数据，避免直接调用toLong()可能导致的崩溃
                val dataMap = PyObjectToKoltinMap(batteryInfo)
                val info = dataMap.toJson<YansheeResp<BatteryInfo>>()?.data ?: return false
                println("[DEBUG] 电量信息: ${info.capacity}")
                println("[DEBUG] 是否充电中: ${info.charging}")
                println("[DEBUG] 电量百分比: ${info.percent}")
                return info.charging
            } catch (e: Exception) {
                println("Error getting charging status: ${e.message}")
                return false
            }finally {
                PyGILState_Release(gilState)
            }
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
                return PyObjectToKoltinMap(result)
            }
            return emptyMap()
        } catch (e: Exception) {
            return emptyMap()
        }
    }
}