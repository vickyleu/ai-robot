package com.airobot.core.device

import kotlinx.coroutines.flow.StateFlow

/**
 * 设备管理器接口
 */
interface DeviceManager {
    /**
     * 当前已连接的设备列表
     */
    val connectedDevices: StateFlow<List<Device>>
    
    /**
     * 可用设备列表
     */
    val availableDevices: StateFlow<List<Device>>
    
    /**
     * 开始扫描设备
     */
    suspend fun startDiscovery()
    
    /**
     * 停止扫描设备
     */
    suspend fun stopDiscovery()
    
    /**
     * 根据设备ID获取设备实例
     */
    fun getDevice(deviceId: String): Device?
    
    /**
     * 注册设备工厂
     * @param factory 设备工厂实例
     */
    fun registerDeviceFactory(factory: DeviceFactory)
    
    /**
     * 注销设备工厂
     * @param deviceType 设备类型
     */
    fun unregisterDeviceFactory(deviceType: DeviceType)
}

/**
 * 设备工厂接口
 */
interface DeviceFactory {
    /**
     * 支持的设备类型
     */
    val deviceType: DeviceType
    
    /**
     * 创建设备实例
     */
    fun createDevice(): Device
}