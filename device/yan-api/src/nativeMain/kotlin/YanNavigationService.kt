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
     * 注意：此功能已被移除，因为底层API不支持
     *
     * @param x X坐标
     * @param y Y坐标
     * @return 始终返回false
     */
    fun navigateTo(x: Double, y: Double): Boolean {
        return false
    }

    /**
     * 获取当前位置
     *
     * 注意：此功能已被移除，因为底层API不支持
     *
     * @return 始终返回null
     */
    fun getCurrentPosition(): Pair<Double, Double>? {
        return null
    }

    /**
     * 停止导航
     *
     * 注意：此功能已被移除，因为底层API不支持
     *
     * @return 始终返回false
     */
    fun stopNavigation(): Boolean {
        return false
    }

    /**
     * 设置导航速度
     *
     * 注意：此功能已被移除，因为底层API不支持
     *
     * @param speed 速度值，范围0-100
     * @return 始终返回false
     */
    fun setNavigationSpeed(speed: Int): Boolean {
        return false
    }
}