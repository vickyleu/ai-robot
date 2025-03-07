package com.airobot.core.device

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 设备管理器默认实现
 */
class DefaultDeviceManager : DeviceManager {
    private val _connectedDevices = MutableStateFlow<List<Device>>(emptyList())
    override val connectedDevices: StateFlow<List<Device>> = _connectedDevices.asStateFlow()
    
    private val _availableDevices = MutableStateFlow<List<Device>>(emptyList())
    override val availableDevices: StateFlow<List<Device>> = _availableDevices.asStateFlow()
    
    private val deviceFactories = mutableMapOf<DeviceType, DeviceFactory>()
    private val devices = mutableMapOf<String, Device>()
    
    override suspend fun startDiscovery() {
        // TODO: 实现设备发现逻辑
    }
    
    override suspend fun stopDiscovery() {
        // TODO: 停止设备发现
    }
    
    override fun getDevice(deviceId: String): Device? = devices[deviceId]
    
    override fun registerDeviceFactory(factory: DeviceFactory) {
        deviceFactories[factory.deviceType] = factory
    }
    
    override fun unregisterDeviceFactory(deviceType: DeviceType) {
        deviceFactories.remove(deviceType)
    }
    
    /**
     * 添加新发现的设备
     */
    private fun addDevice(deviceId: String, name: String, type: DeviceType) {
        /*val factory = deviceFactories[type] ?: return
        val device = factory.createDevice(deviceId, name)
        devices[deviceId] = device
        updateAvailableDevices()*/
    }
    
    /**
     * 更新可用设备列表
     */
    private fun updateAvailableDevices() {
        _availableDevices.value = devices.values.toList()
    }
    
    /**
     * 更新已连接设备列表
     */
    private fun updateConnectedDevices() {
        _connectedDevices.value = devices.values.filter { 
            it.status.value == DeviceStatus.CONNECTED 
        }
    }
}