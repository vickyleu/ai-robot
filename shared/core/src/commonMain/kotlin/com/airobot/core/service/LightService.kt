package com.airobot.core.service

/**
 * 灯光服务接口
 * 
 * 定义机器人灯光相关功能，包括设置灯光颜色、模式等
 */
interface LightService : RobotService {
    /**
     * 灯光类型
     */
    enum class LightType {
        EYE,      // 眼睛灯
        BUTTON,   // 按钮灯
        CHEST,    // 胸部灯
        HEAD,     // 头部灯
        CUSTOM    // 自定义灯
    }
    
    /**
     * 灯光颜色
     */
    enum class LightColor {
        RED,      // 红色
        GREEN,    // 绿色
        BLUE,     // 蓝色
        YELLOW,   // 黄色
        PURPLE,   // 紫色
        CYAN,     // 青色
        WHITE,    // 白色
        CUSTOM    // 自定义颜色
    }
    
    /**
     * 灯光模式
     */
    enum class LightMode {
        ON,        // 常亮
        OFF,       // 关闭
        BLINK,     // 闪烁
        BREATH,    // 呼吸
        PULSE,     // 脉冲
        CUSTOM     // 自定义模式
    }
    
    /**
     * 设置灯光
     * 
     * @param type 灯光类型
     * @param color 灯光颜色
     * @param mode 灯光模式
     * @return 操作是否成功
     */
    suspend fun setLight(type: LightType, color: LightColor, mode: LightMode = LightMode.ON): Boolean
    
    /**
     * 关闭灯光
     * 
     * @param type 灯光类型，null表示关闭所有灯光
     * @return 操作是否成功
     */
    suspend fun turnOff(type: LightType? = null): Boolean
    
    /**
     * 获取灯光状态
     * 
     * @param type 灯光类型
     * @return 灯光状态，包含颜色和模式信息，失败返回null
     */
    suspend fun getLightStatus(type: LightType): Pair<LightColor, LightMode>?
    
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
    suspend fun setCustomColor(type: LightType, red: Int, green: Int, blue: Int, mode: LightMode = LightMode.ON): Boolean
}