package com.airobot.core.service

import com.airobot.core.service.LightService.LightColor
import com.airobot.core.service.LightService.LightMode
import com.airobot.core.service.LightService.LightType
import com.airobot.device.yanapi.LightColor as YanLightColor
import com.airobot.device.yanapi.LightMode as YanLightMode
import com.airobot.device.yanapi.LightType as YanLightType
import com.airobot.device.yanapi.YanLightService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 灯光服务接口的YAN机器人实现
 * 
 * 将YanLightService适配到LightService接口
 */
class LightServiceImpl : LightService {
    private val yanLightService = YanLightService()
    private var isInitialized = false
    
    /**
     * 初始化服务
     * 
     * @return 初始化是否成功
     */
    override suspend fun initialize(): Boolean {
        isInitialized = true
        return true
    }
    
    /**
     * 关闭服务
     * 
     * @return 关闭是否成功
     */
    override suspend fun shutdown(): Boolean {
        isInitialized = false
        return true
    }
    
    /**
     * 获取服务名称
     * 
     * @return 服务名称
     */
    override fun getServiceName(): String {
        return "YAN灯光服务"
    }
    
    /**
     * 检查服务是否可用
     * 
     * @return 服务是否可用
     */
    override fun isAvailable(): Boolean {
        return isInitialized
    }
    
    /**
     * 将core模块的LightType转换为YAN的LightType
     */
    private fun mapLightType(type: LightService.LightType): YanLightType {
        return when (type) {
            LightService.LightType.EYE -> YanLightType.CAMERA
            else -> YanLightType.BUTTON
        }
    }
    
    /**
     * 将core模块的LightColor转换为YAN的LightColor
     */
    private fun mapLightColor(color: LightService.LightColor): YanLightColor {
        return when (color) {
            LightService.LightColor.RED -> YanLightColor.red
            LightService.LightColor.GREEN -> YanLightColor.green
            LightService.LightColor.BLUE -> YanLightColor.blue
            LightService.LightColor.YELLOW -> YanLightColor.yellow
            LightService.LightColor.PURPLE -> YanLightColor.purple
            LightService.LightColor.CYAN -> YanLightColor.cyan
            LightService.LightColor.WHITE -> YanLightColor.white
            LightService.LightColor.CUSTOM -> YanLightColor.white // 默认使用白色
        }
    }
    
    /**
     * 将core模块的LightMode转换为YAN的LightMode
     */
    private fun mapLightMode(mode: LightService.LightMode): YanLightMode {
        return when (mode) {
            LightService.LightMode.ON -> YanLightMode.on
            LightService.LightMode.OFF -> YanLightMode.off
            LightService.LightMode.BLINK -> YanLightMode.blink
            LightService.LightMode.BREATH -> YanLightMode.breath
            LightService.LightMode.PULSE, 
            LightService.LightMode.CUSTOM -> YanLightMode.on // 默认使用常亮模式
        }
    }
    
    /**
     * 将YAN的LightColor转换为core模块的LightColor
     */
    private fun mapYanLightColor(color: YanLightColor?): LightService.LightColor {
        return when (color) {
            YanLightColor.red -> LightService.LightColor.RED
            YanLightColor.green -> LightService.LightColor.GREEN
            YanLightColor.blue -> LightService.LightColor.BLUE
            YanLightColor.yellow -> LightService.LightColor.YELLOW
            YanLightColor.purple -> LightService.LightColor.PURPLE
            YanLightColor.cyan -> LightService.LightColor.CYAN
            YanLightColor.white -> LightService.LightColor.WHITE
            null -> LightService.LightColor.WHITE // 默认使用白色
        }
    }
    
    /**
     * 将YAN的LightMode转换为core模块的LightMode
     */
    private fun mapYanLightMode(mode: YanLightMode?): LightService.LightMode {
        return when (mode) {
            YanLightMode.on -> LightService.LightMode.ON
            YanLightMode.off -> LightService.LightMode.OFF
            YanLightMode.blink -> LightService.LightMode.BLINK
            YanLightMode.breath -> LightService.LightMode.BREATH
            null -> LightService.LightMode.ON // 默认使用常亮模式
        }
    }
    
    /**
     * 设置灯光
     * 
     * @param type 灯光类型
     * @param color 灯光颜色
     * @param mode 灯光模式
     * @return 操作是否成功
     */
    override suspend fun setLight(type: LightService.LightType, color: LightService.LightColor, mode: LightService.LightMode): Boolean {
        return withContext(Dispatchers.Default) {
            yanLightService.setLight(
                mapLightType(type),
                mapLightColor(color),
                mapLightMode(mode)
            )
        }
    }

    // 这些方法已在下方实现

    /**
     * 关闭灯光
     * 
     * @param type 灯光类型，null表示关闭所有灯光
     * @return 操作是否成功
     */
    override suspend fun turnOff(type: LightType?): Boolean {
        return withContext(Dispatchers.Default) {
            if (type == null) {
                yanLightService.turnOff()
            } else {
                yanLightService.setLight(
                    mapLightType(type),
                    YanLightColor.white,
                    YanLightMode.off
                )
            }
        }
    }
    
    /**
     * 获取灯光状态
     * 
     * @param type 灯光类型
     * @return 灯光状态，包含颜色和模式信息，失败返回null
     */
    override suspend fun getLightStatus(type: LightType): Pair<LightColor, LightMode>? {
        return withContext(Dispatchers.Default) {
            when (type) {
                LightType.EYE -> {
                    val color = yanLightService.getEyeLedColorValue()
                    val mode = yanLightService.getEyeLedModeValue()
                    if (color != null && mode != null) {
                        Pair(mapYanLightColor(color), mapYanLightMode(mode))
                    } else {
                        null
                    }
                }
                LightType.BUTTON, LightType.CHEST, LightType.HEAD, LightType.CUSTOM -> {
                    val color = yanLightService.getButtonLedColorValue()
                    val mode = yanLightService.getButtonLedModeValue()
                    if (color != null && mode != null) {
                        Pair(mapYanLightColor(color), mapYanLightMode(mode))
                    } else {
                        null
                    }
                }
            }
        }
    }
    
    /**
     * 设置自定义颜色
     * 
     * @param type 灯光类型
     * @param red 红色分量(0-255)
     * @param green 绿色分量(0-255)
     * @param blue 蓝色分量(0-255)
     * @param mode 灯光模式
     * @return 操作是否成功
     */
    override suspend fun setCustomColor(type: LightType, red: Int, green: Int, blue: Int, mode: LightMode): Boolean {
        // YAN API不支持自定义RGB颜色，使用最接近的预定义颜色
        return withContext(Dispatchers.Default) {
            // 简单的颜色映射逻辑，根据RGB值选择最接近的预定义颜色
            val color = when {
                red > 200 && green < 100 && blue < 100 -> YanLightColor.red
                red < 100 && green > 200 && blue < 100 -> YanLightColor.green
                red < 100 && green < 100 && blue > 200 -> YanLightColor.blue
                red > 200 && green > 200 && blue < 100 -> YanLightColor.yellow
                red > 200 && green < 100 && blue > 200 -> YanLightColor.purple
                red < 100 && green > 200 && blue > 200 -> YanLightColor.cyan
                red > 200 && green > 200 && blue > 200 -> YanLightColor.white
                else -> YanLightColor.white
            }
            
            yanLightService.setLight(
                mapLightType(type),
                color,
                mapLightMode(mode)
            )
        }
    }
}