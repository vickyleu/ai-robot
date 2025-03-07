package com.airobot.core.command

import kotlinx.serialization.json.*

/**
 * 设备适配器接口
 * 用于连接CommandExecutor与实际的设备SDK
 */
interface DeviceAdapter {
    /**
     * 获取设备名称
     */
    val deviceName: String
    
    /**
     * 获取设备支持的动作列表
     */
    fun getSupportedActions(): List<String>
    
    /**
     * 初始化设备
     */
    suspend fun initialize()
    
    /**
     * 关闭设备连接
     */
    suspend fun shutdown()
    
    /**
     * 注册设备支持的所有动作到ActionRegistry
     */
    fun registerActions()
}

/**
 * 设备管理器
 * 用于管理多个设备适配器
 */
object DeviceManager {
    private val devices = mutableListOf<DeviceAdapter>()
    
    /**
     * 注册设备适配器
     */
    fun registerDevice(device: DeviceAdapter) {
        devices.add(device)
    }
    
    /**
     * 初始化所有设备
     */
    suspend fun initializeAll() {
        devices.forEach { device ->
            device.initialize()
            device.registerActions()
        }
    }
    
    /**
     * 关闭所有设备连接
     */
    suspend fun shutdownAll() {
        devices.forEach { it.shutdown() }
    }
    
    /**
     * 获取所有已注册设备
     */
    fun getRegisteredDevices(): List<DeviceAdapter> = devices.toList()
}