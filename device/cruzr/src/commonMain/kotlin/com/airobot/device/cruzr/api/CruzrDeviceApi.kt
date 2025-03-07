package com.airobot.device.cruzr.api

import kotlinx.coroutines.flow.Flow

/**
 * Cruzr设备接口
 *
 * 定义了与Cruzr机器人交互的基本操作接口
 */
interface CruzrDeviceApi {
    /**
     * 设备连接状态
     */
    val connectionStatus: Flow<ConnectionStatus>

    /**
     * 设备运行状态
     */
    val deviceStatus: Flow<DeviceStatus>

    /**
     * 连接设备
     *
     * @throws ConnectionException 连接异常时抛出
     */
    suspend fun connect()

    /**
     * 断开连接
     */
    suspend fun disconnect()

    /**
     * 执行动作
     *
     * @param action 动作类型
     * @param params 动作参数
     * @throws ActionException 动作执行异常时抛出
     */
    suspend fun executeAction(action: ActionType, params: Map<String, Any>)

    /**
     * 停止当前动作
     */
    suspend fun stopAction()

    /**
     * 获取传感器数据
     *
     * @return 传感器数据流
     */
    fun getSensorData(): Flow<SensorData>
}

/**
 * 连接状态
 */
enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

/**
 * 设备状态
 */
data class DeviceStatus(
    val batteryLevel: Int,
    val isCharging: Boolean,
    val currentAction: ActionType?,
    val errorCode: Int?
)

/**
 * 动作类型
 */
enum class ActionType {
    MOVE_FORWARD,
    MOVE_BACKWARD,
    TURN_LEFT,
    TURN_RIGHT,
    STOP
}

/**
 * 传感器数据
 */
data class SensorData(
    val timestamp: Long,
    val type: String,
    val values: Map<String, Any>
)

/**
 * 连接异常
 */
class ConnectionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 动作执行异常
 */
class ActionException(message: String, cause: Throwable? = null) : Exception(message, cause)