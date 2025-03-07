package com.airobot.core.service

import kotlinx.coroutines.flow.StateFlow

/**
 * 传感器服务接口
 * 
 * 定义机器人传感器相关功能，包括获取各类传感器数据
 */
interface SensorService : RobotService {
    /**
     * 传感器类型
     */
    enum class SensorType {
        TEMPERATURE,  // 温度传感器
        HUMIDITY,     // 湿度传感器
        PRESSURE,     // 压力传感器
        ULTRASONIC,   // 超声波传感器
        INFRARED,     // 红外传感器
        TOUCH,        // 触摸传感器
        GYROSCOPE,    // 陀螺仪
        ACCELEROMETER, // 加速度计
        LIGHT,        // 光线传感器
        CUSTOM        // 自定义传感器
    }
    
    /**
     * 传感器数据类
     */
    data class SensorData(
        val type: SensorType,
        val value: Double,
        val timestamp: Long,
        val unit: String = "",
        val extraData: Map<String, Any> = emptyMap()
    )
    
    /**
     * 获取所有可用传感器类型
     * 
     * @return 可用传感器类型列表
     */
    suspend fun getAvailableSensors(): List<SensorType>
    
    /**
     * 获取传感器数据
     * 
     * @param type 传感器类型
     * @return 传感器数据，失败返回null
     */
    suspend fun getSensorData(type: SensorType): SensorData?
    
    /**
     * 获取传感器数据流
     * 
     * @param type 传感器类型
     * @param interval 数据更新间隔(毫秒)
     * @return 传感器数据流
     */
    fun getSensorDataStream(type: SensorType, interval: Long = 100): StateFlow<SensorData?>
    
    /**
     * 校准传感器
     * 
     * @param type 传感器类型
     * @return 操作是否成功
     */
    suspend fun calibrateSensor(type: SensorType): Boolean
    
    /**
     * 设置传感器阈值
     * 
     * @param type 传感器类型
     * @param min 最小阈值
     * @param max 最大阈值
     * @return 操作是否成功
     */
    suspend fun setSensorThreshold(type: SensorType, min: Double, max: Double): Boolean
}