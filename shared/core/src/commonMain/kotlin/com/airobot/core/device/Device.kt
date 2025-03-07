package com.airobot.core.device

import kotlinx.coroutines.flow.StateFlow

/**
 * 设备状态
 */
enum class DeviceStatus {
    DISCONNECTED,   // 未连接
    CONNECTING,     // 连接中
    CONNECTED,      // 已连接
    ERROR          // 错误状态
}

/**
 * 设备控制接口
 */
interface Device {
    /**
     * 设备ID
     */
    val deviceId: String
    
    /**
     * 设备名称
     */
    val name: String
    
    /**
     * 设备类型
     */
    val type: DeviceType
    
    /**
     * 设备状态
     */
    val status: StateFlow<DeviceStatus>
    
    /**
     * 连接设备
     */
    suspend fun connect()
    
    /**
     * 断开连接
     */
    suspend fun disconnect()
    
    /**
     * 发送指令
     */
    suspend fun sendCommand(command: DeviceCommand)
}

/**
 * 设备类型
 */
enum class DeviceType {
    YAN,      // 树莓派YAN设备
    CRUZR,    // CRUZR机器人
    CUSTOM    // 自定义设备
}

/**
 * 设备指令接口
 */
interface DeviceCommand {
    val type: String
    val params: Map<String, Any>
}