package com.airobot.device.cruzr

import com.airobot.core.device.*
import com.airobot.protocol.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * CRUZR机器人设备工厂
 */
class CruzrDeviceFactory : DeviceFactory {
    override val deviceType: DeviceType = DeviceType.CRUZR
    
    override fun createDevice(deviceId: String, name: String): Device {
        return CruzrDevice(deviceId, name)
    }
}

/**
 * CRUZR机器人设备实现
 */
class CruzrDevice(
    override val deviceId: String,
    override val name: String
) : Device {
    override val type: DeviceType = DeviceType.CRUZR
    
    private val _status = MutableStateFlow(DeviceStatus.DISCONNECTED)
    override val status: StateFlow<DeviceStatus> = _status
    
    private var messageHandler: MessageHandler? = null
    private var adapter: CommunicationAdapter? = null
    
    override suspend fun connect() {
        try {
            _status.value = DeviceStatus.CONNECTING
            
            // 初始化通信适配器
            adapter = createAdapter()
            adapter?.connect()
            
            // 初始化消息处理器
            messageHandler = createMessageHandler()
            
            _status.value = DeviceStatus.CONNECTED
        } catch (e: Exception) {
            _status.value = DeviceStatus.ERROR
            throw e
        }
    }
    
    override suspend fun disconnect() {
        adapter?.disconnect()
        messageHandler = null
        adapter = null
        _status.value = DeviceStatus.DISCONNECTED
    }
    
    override suspend fun sendCommand(command: DeviceCommand) {
        val message = MessageBuilder()
            .messageId(System.currentTimeMillis().toString())
            .type(MessageType.COMMAND)
            .payload("type", command.type)
            .apply { command.params.forEach { (key, value) -> payload(key, value) } }
            .build()
            
        messageHandler?.sendMessage(message)
    }
    
    private fun createAdapter(): CommunicationAdapter {
        return object : CommunicationAdapter {
            private var webSocket: WebSocket? = null
            private val messageQueue = Channel<ByteArray>(Channel.UNLIMITED)
            
            override val isConnected: Boolean
                get() = webSocket?.isConnected == true
            
            override suspend fun connect() {
                try {
                    val client = HttpClient {
                        install(WebSockets)
                    }
                    
                    webSocket = client.webSocketSession {
                        url("ws://localhost:8080/device/$deviceId")
                    }
                    
                    // 启动接收消息的协程
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            webSocket?.incoming?.consumeEach { frame ->
                                if (frame is Frame.Binary) {
                                    messageQueue.send(frame.readBytes())
                                }
                            }
                        } catch (e: Exception) {
                            println("接收消息错误: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    throw ConnectionException("连接失败: ${e.message}")
                }
            }
            
            override suspend fun disconnect() {
                try {
                    webSocket?.close()
                    webSocket = null
                } catch (e: Exception) {
                    println("断开连接错误: ${e.message}")
                }
            }
            
            override suspend fun send(data: ByteArray) {
                try {
                    webSocket?.send(Frame.Binary(true, data))
                } catch (e: Exception) {
                    throw SendMessageException("发送消息失败: ${e.message}")
                }
            }
            
            override suspend fun receive(): ByteArray {
                return try {
                    messageQueue.receive()
                } catch (e: Exception) {
                    throw ReceiveMessageException("接收消息失败: ${e.message}")
                }
            }
        }
    }
    
    private fun createMessageHandler(): MessageHandler {
        return object : BaseMessageHandler() {
            override suspend fun handleMessage(message: Message) {
                // 处理接收到的消息
                notifyListeners(message)
            }
            
            override suspend fun sendMessage(message: Message) {
                // 发送消息
                adapter?.send(serializeMessage(message))
            }
        }
    }
    
    private fun serializeMessage(message: Message): ByteArray {
        val jsonObject = buildJsonObject {
            put("messageId", message.messageId)
            put("type", message.type.toString())
            put("payload", buildJsonObject {
                message.payload.forEach { (key, value) ->
                    when (value) {
                        is String -> put(key, value)
                        is Number -> put(key, value)
                        is Boolean -> put(key, value)
                        else -> put(key, value.toString())
                    }
                }
            })
        }
        return Json.encodeToString(jsonObject).toByteArray()
    }
    
    companion object {
        // CRUZR机器人特定的命令类型
        const val COMMAND_MOVE = "move"
        const val COMMAND_SPEAK = "speak"
        const val COMMAND_GESTURE = "gesture"
        const val COMMAND_EXPRESSION = "expression"
    }
}

/**
 * CRUZR机器人移动命令
 */
data class CruzrMoveCommand(
    val direction: String,
    val speed: Float
) : DeviceCommand {
    override val type: String = CruzrDevice.COMMAND_MOVE
    override val params: Map<String, Any> = mapOf(
        "direction" to direction,
        "speed" to speed
    )
}

/**
 * CRUZR机器人语音命令
 */
data class CruzrSpeakCommand(
    val text: String,
    val language: String = "zh-CN"
) : DeviceCommand {
    override val type: String = CruzrDevice.COMMAND_SPEAK
    override val params: Map<String, Any> = mapOf(
        "text" to text,
        "language" to language
    )
}

/**
 * CRUZR机器人手势命令
 */
data class CruzrGestureCommand(
    val gesture: String
) : DeviceCommand {
    override val type: String = CruzrDevice.COMMAND_GESTURE
    override val params: Map<String, Any> = mapOf(
        "gesture" to gesture
    )
}

/**
 * CRUZR机器人表情命令
 */
data class CruzrExpressionCommand(
    val expression: String
) : DeviceCommand {
    override val type: String = CruzrDevice.COMMAND_EXPRESSION
    override val params: Map<String, Any> = mapOf(
        "expression" to expression
    )
}