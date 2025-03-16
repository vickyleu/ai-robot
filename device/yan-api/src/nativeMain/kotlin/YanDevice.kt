package com.airobot.device.yanapi

import com.airobot.core.device.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * YAN设备实现类
 *
 * 实现了Device接口，提供与YAN协议兼容的设备控制功能
 */
class YanDevice(
    override val deviceId: String,
    override val name: String
) : Device {
    // 系统服务
    private val locomotionService = YanLocomotionService()
    private val speechService = YanSpeechService()
    private val servoService = YanServoService()
    private val navigationService = YanNavigationService()
    private val emotionService = YanEmotionService()
    private val sensorService = YanSensorService()
    private val powerService = YanPowerService()
    private val lightService = YanLightService()
    private val skillManager = YanSkillManager()
    private val diagnosisService = YanDiagnosisService()
    override val type: DeviceType = DeviceType.YAN
    
    private val _status = MutableStateFlow(DeviceStatus.DISCONNECTED)
    override val status: StateFlow<DeviceStatus> = _status
    
    private val protocol = YanProtocol
    private val statusManager = YanStatusManager()
    
    private var connection: YanConnection? = null
    
    override suspend fun connect() {
        try {
            _status.value = DeviceStatus.CONNECTING
            // 创建连接
            connection = YanConnection().apply {
                connect(deviceId)
                
                // 设置状态更新回调
                onStatusUpdate { status ->
                    statusManager.updateStatus(status)
                }
                
                // 设置错误处理回调
                onError { error ->
                    _status.value = DeviceStatus.ERROR
                    statusManager.notifyEvent("error: $error")
                }
            }
            
            // 启动状态监控
            statusManager.monitorStatus()
            
            _status.value = DeviceStatus.CONNECTED
        } catch (e: Exception) {
            _status.value = DeviceStatus.ERROR
            throw ConnectionException("连接失败: ${e.message}", e)
        }
    }
    
    override suspend fun disconnect() {
        try {
            connection?.disconnect()
            statusManager.stopMonitor()
            _status.value = DeviceStatus.DISCONNECTED
        } catch (e: Exception) {
            // 即使断开连接失败，也将状态设置为断开
            _status.value = DeviceStatus.DISCONNECTED
            throw ConnectionException("断开连接失败: ${e.message}", e)
        } finally {
            connection = null
        }
    }
    
    override suspend fun sendCommand(command: DeviceCommand) {
        try {
            if (_status.value != DeviceStatus.CONNECTED) {
                throw IllegalStateException("设备未连接")
            }
            
            // 序列化命令
            val message = protocol.serialize(command)
            
            // 加密数据
            val encryptedMessage = protocol.encrypt(message)
            
            // 发送命令
            connection?.send(encryptedMessage)
        } catch (e: Exception) {
            throw CommandException("发送命令失败: ${e.message}", e)
        }
    }
    
    /**
     * 获取设备状态
     */
    fun getStatus(): Map<String, Any> {
        return statusManager.getCurrentStatus()
    }
    
    /**
     * 设置状态更新回调
     */
    fun onStatusUpdate(callback: (Map<String, Any>) -> Unit) {
        statusManager.setStatusCallback(callback)
    }

    // 运动控制
    suspend fun move(speed: Int, direction: String) {
        locomotionService.move(speed, direction)
    }

    suspend fun stop() {
        locomotionService.stop()
    }

    // 语音服务
    suspend fun speak(text: String, language: String = "zh-CN") {
        speechService.speak(text, language)
    }

    suspend fun stopSpeak() {
        speechService.stopSpeak()
    }

    // 舵机控制
    suspend fun moveJoint(jointId: String, angle: Float) {
        servoService.moveJoint(jointId, angle)
    }

    suspend fun resetJoint(jointId: String) {
        servoService.resetJoint(jointId)
    }

    // 导航服务
    suspend fun navigateTo(x: Float, y: Float) {
        navigationService.navigateTo(x, y)
    }

    suspend fun getPosition(): Pair<Float, Float> {
        return navigationService.getPosition()
    }

    // 情感服务
    suspend fun setEmotion(type: String) {
        emotionService.setEmotion(type)
    }

    suspend fun playAnimation(name: String) {
        emotionService.playAnimation(name)
    }

    // 传感器服务
    suspend fun getSensorData(type: String): Map<String, Any> {
        return sensorService.getSensorData(type)
    }

    // 电源管理
    suspend fun getBatteryLevel(): Int {
        return powerService.getBatteryLevel()
    }

    suspend fun isCharging(): Boolean {
        return powerService.isCharging()
    }

    // 灯光控制
    suspend fun setLight(position: String, color: Int) {
        lightService.setLight(position, color)
    }

    suspend fun turnOffLight(position: String) {
        lightService.turnOff(position)
    }

    // 技能管理
    suspend fun loadSkill(skillId: String) {
        skillManager.loadSkill(skillId)
    }

    suspend fun startSkill(skillId: String, params: Map<String, Any>) {
        skillManager.startSkill(skillId, params)
    }
}

/**
 * YAN设备连接类
 */
private class YanConnection {
    private var socket: YanSocket? = null
    private var statusCallback: ((Map<String, Any>) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null
    
    fun connect(deviceId: String) {
        socket = YanSocket().apply {
            connect("yan://$deviceId")
        }
    }
    
    fun disconnect() {
        socket?.close()
        socket = null
    }
    
    fun send(data: ByteArray) {
        socket?.send(data)
    }
    
    fun onStatusUpdate(callback: (Map<String, Any>) -> Unit) {
        statusCallback = callback
    }
    
    fun onError(callback: (String) -> Unit) {
        errorCallback = callback
    }
}

/**
 * YAN设备Socket类
 */
private class YanSocket {
    fun connect(url: String) {
        // 实现Socket连接逻辑
    }
    
    fun close() {
        // 实现Socket关闭逻辑
    }
    
    fun send(data: ByteArray) {
        // 实现数据发送逻辑
    }
}

/**
 * 连接异常
 */
class ConnectionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 命令异常
 */
class CommandException(message: String, cause: Throwable? = null) : Exception(message, cause)