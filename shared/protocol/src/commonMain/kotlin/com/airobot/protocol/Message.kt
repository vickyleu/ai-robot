package com.airobot.protocol

import kotlinx.datetime.Clock

/**
 * 消息类型
 */
enum class MessageType {
    COMMAND,    // 控制指令
    RESPONSE,   // 响应消息
    EVENT,      // 事件消息
    ERROR       // 错误消息
}

/**
 * 通信消息接口
 */
interface Message {
    /**
     * 消息ID
     */
    val messageId: String

    /**
     * 消息类型
     */
    val type: MessageType

    /**
     * 消息内容
     */
    val payload: Map<String, Any>

    /**
     * 时间戳
     */
    val timestamp: Long
}

/**
 * 消息构建器
 */
class MessageBuilder {
    private var messageId: String = ""
    private var type: MessageType = MessageType.COMMAND
    private val payload = mutableMapOf<String, Any>()
    private var timestamp: Long = Clock.System.now().toEpochMilliseconds()

    fun messageId(id: String) = apply { messageId = id }
    fun type(type: MessageType) = apply { this.type = type }
    fun payload(key: String, value: Any) = apply { payload[key] = value }
    fun timestamp(time: Long) = apply { timestamp = time }

    fun build(): Message = object : Message {
        override val messageId: String = this@MessageBuilder.messageId
        override val type: MessageType = this@MessageBuilder.type
        override val payload: Map<String, Any> = this@MessageBuilder.payload.toMap()
        override val timestamp: Long = this@MessageBuilder.timestamp
    }
}