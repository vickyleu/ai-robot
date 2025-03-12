package com.airobot.device.cruzr.api

import android.content.Context
import android.util.Log
import com.airobot.device.cruzr.bridge.CruzrBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Cruzr设备API实现类
 *
 * 实现了CruzrDeviceApi接口，提供与Cruzr机器人交互的具体实现
 */
class CruzrDeviceApiImpl(private val context: Context) : CruzrDeviceApi {

    private val bridge = CruzrBridge(context)
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    override val connectionStatus: Flow<ConnectionStatus> = _connectionStatus

    private val _deviceStatus = MutableStateFlow(
        DeviceStatus(
            batteryLevel = 0,
            isCharging = false,
            currentAction = null,
            errorCode = null
        )
    )
    override val deviceStatus: Flow<DeviceStatus> = _deviceStatus

    private val _sensorData = MutableStateFlow<SensorData?>(null)

    init {
        // 初始化桥接层
        try {
            bridge.initialize()
        } catch (e: Exception) {
            _connectionStatus.value = ConnectionStatus.ERROR
        }

        // 监听桥接层状态变化
        scope.launch {
            bridge.isConnected.collect { isConnected ->
                _connectionStatus.value = if (isConnected) {
                    ConnectionStatus.CONNECTED
                } else {
                    ConnectionStatus.DISCONNECTED
                }
            }
        }

        // 监听电池状态
        scope.launch {
            bridge.batteryLevel.collect { level ->
                _deviceStatus.value = _deviceStatus.value.copy(batteryLevel = level)
            }
        }

        scope.launch {
            bridge.isCharging.collect { isCharging ->
                _deviceStatus.value = _deviceStatus.value.copy(isCharging = isCharging)
            }
        }

        // 监听当前动作
        scope.launch {
            bridge.currentAction.collect { action ->
                _deviceStatus.value = _deviceStatus.value.copy(
                    currentAction = when (action) {
                        "move_forward" -> ActionType.MOVE_FORWARD
                        "move_backward" -> ActionType.MOVE_BACKWARD
                        "turn_left" -> ActionType.TURN_LEFT
                        "turn_right" -> ActionType.TURN_RIGHT
                        "stop" -> ActionType.STOP
                        else -> null
                    }
                )
            }
        }

        // 监听错误状态
        scope.launch {
            bridge.errorCode.collect { errorCode ->
                _deviceStatus.value = _deviceStatus.value.copy(errorCode = errorCode)
            }
        }
    }

    override suspend fun connect() {
        try {
            _connectionStatus.value = ConnectionStatus.CONNECTING
            bridge.connect()
        } catch (e: Exception) {
            _connectionStatus.value = ConnectionStatus.ERROR
            throw ConnectionException("连接设备失败: ${e.message}", e)
        }
    }

    override suspend fun disconnect() {
        try {
            bridge.disconnect()
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
        } catch (e: Exception) {
            // 即使断开连接失败，也将状态设置为断开
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
        }
    }

    override suspend fun executeAction(action: ActionType, params: Map<String, Any>) {
        try {
            // 检查特殊动作参数
            if (params.containsKey("is_head_movement") && params["is_head_movement"] == true) {
                // 头部转动动作，使用turn_left动作类型但添加特殊参数
                bridge.executeAction("turn_left", params)
                return
            } else if (params.containsKey("is_speech_action") && params["is_speech_action"] == true) {
                // 语音播放动作，使用stop动作类型但添加特殊参数
                bridge.executeAction("stop", params)
                return
            }
            
            // 常规动作类型映射
            val actionType = when (action) {
                ActionType.MOVE_FORWARD -> "move_forward"
                ActionType.MOVE_BACKWARD -> "move_backward"
                ActionType.TURN_LEFT -> "turn_left"
                ActionType.TURN_RIGHT -> "turn_right"
                ActionType.STOP -> "stop"
            }

            bridge.executeAction(actionType, params)
        } catch (e: Exception) {
            throw ActionException("执行动作失败: ${e.message}", e)
        }
    }

    override suspend fun stopAction() {
        try {
            bridge.stopAction()
        } catch (e: Exception) {
            throw ActionException("停止动作失败: ${e.message}", e)
        }
    }

    /**
     * 获取传感器数据流
     *
     * @return 传感器数据流，如果没有数据则返回空的传感器数据对象
     */
    override fun getSensorData(): Flow<SensorData> {
        // 启动定期更新传感器数据的任务
        startSensorDataUpdate()
        // 返回传感器数据流
        return _sensorData.map { it ?: createEmptySensorData() }
    }

    /**
     * 创建空的传感器数据对象
     *
     * @return 包含当前时间戳但没有实际数据的SensorData对象
     */
    private fun createEmptySensorData(): SensorData {
        return SensorData(
            timestamp = System.currentTimeMillis(),
            type = "empty",
            values = emptyMap()
        )
    }

    /**
     * 启动定期更新传感器数据的任务
     * 每隔一定时间获取一次传感器数据
     */
    private fun startSensorDataUpdate() {
        scope.launch {
            while (true) {
                updateSensorData()
                kotlinx.coroutines.delay(1000) // 每秒更新一次传感器数据
            }
        }
    }

    /**
     * 更新传感器数据
     * 从桥接层获取设备状态，提取传感器相关数据并更新_sensorData
     */
    private fun updateSensorData() {
        scope.launch {
            try {
                // 使用bridge.sensorData流而不是调用不存在的getDeviceStatus方法
                bridge.sensorData.collect { sensorValues ->
                    if (sensorValues.isNotEmpty()) {
                        _sensorData.value = SensorData(
                            timestamp = System.currentTimeMillis(),
                            type = "status",
                            values = sensorValues
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("CruzrDeviceApiImpl", "获取传感器数据异常: ${e.message}")
                // 忽略异常，保持上一次的传感器数据
            }
        }
    }
}
