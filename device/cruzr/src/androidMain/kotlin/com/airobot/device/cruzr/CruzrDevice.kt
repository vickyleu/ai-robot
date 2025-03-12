package com.airobot.device.cruzr

import android.content.Context
import com.airobot.core.device.Device
import com.airobot.core.device.DeviceCommand
import com.airobot.core.device.DeviceFactory
import com.airobot.core.device.DeviceStatus
import com.airobot.core.device.DeviceType
import com.airobot.device.cruzr.api.ActionType
import com.airobot.device.cruzr.api.ConnectionException
import com.airobot.device.cruzr.api.CruzrDeviceApiImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * CRUZR机器人设备工厂
 */
class CruzrDeviceFactory(private val context: Context) : DeviceFactory {
    override val deviceType: DeviceType = DeviceType.CRUZR

    override fun createDevice(): Device {
        return CruzrDevice(context)
    }
}

/**
 * CRUZR机器人设备实现
 */
class CruzrDevice(
    context: Context
) : Device {
    override val type: DeviceType = DeviceType.CRUZR

    private val _status = MutableStateFlow(DeviceStatus.DISCONNECTED)
    override val status: StateFlow<DeviceStatus> = _status

    private val api = CruzrDeviceApiImpl(context)
    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        // 监听API连接状态
        scope.launch {
            api.connectionStatus.collect { connectionStatus ->
                _status.value = when (connectionStatus) {
                    com.airobot.device.cruzr.api.ConnectionStatus.CONNECTED -> DeviceStatus.CONNECTED
                    com.airobot.device.cruzr.api.ConnectionStatus.CONNECTING -> DeviceStatus.CONNECTING
                    com.airobot.device.cruzr.api.ConnectionStatus.DISCONNECTED -> DeviceStatus.DISCONNECTED
                    com.airobot.device.cruzr.api.ConnectionStatus.ERROR -> DeviceStatus.ERROR
                }
            }
        }
    }

    override val name: String
        get() = "cruzr"

    override val deviceId: String
        get() = "cruzr"

    override suspend fun connect() {
        try {
            _status.value = DeviceStatus.CONNECTING
            api.connect()
        } catch (e: ConnectionException) {
            _status.value = DeviceStatus.ERROR
            throw RuntimeException("连接CRUZR设备失败: ${e.message}", e)
        } catch (e: Exception) {
            _status.value = DeviceStatus.ERROR
            throw e
        }
    }

    override suspend fun disconnect() {
        try {
            api.disconnect()
        } finally {
            _status.value = DeviceStatus.DISCONNECTED
        }
    }

    override suspend fun sendCommand(command: DeviceCommand) {
        try {
            // 将通用命令转换为CRUZR特定动作
            val actionType = mapCommandToActionType(command.type)

            // 执行动作
            api.executeAction(actionType, command.params)
        } catch (e: Exception) {
            throw RuntimeException("发送命令失败: ${e.message}", e)
        }
    }

    /**
     * 将通用命令类型映射为CRUZR特定动作类型
     */
    private fun mapCommandToActionType(commandType: String): ActionType {
        return when (commandType) {
            "move_forward" -> ActionType.MOVE_FORWARD
            "move_backward" -> ActionType.MOVE_BACKWARD
            "turn_left" -> ActionType.TURN_LEFT
            "turn_right" -> ActionType.TURN_RIGHT
            "stop" -> ActionType.STOP
            else -> throw IllegalArgumentException("不支持的命令类型: $commandType")
        }
    }
}