package com.airobot.protocol

/**
 * 消息处理器接口
 */
interface MessageHandler {
    /**
     * 处理接收到的消息
     */
    suspend fun handleMessage(message: Message)
    
    /**
     * 发送消息
     */
    suspend fun sendMessage(message: Message)
}

/**
 * 消息处理器基类
 */
abstract class BaseMessageHandler : MessageHandler {
    /**
     * 消息监听器列表
     */
    private val listeners = mutableListOf<MessageListener>()
    
    /**
     * 添加消息监听器
     */
    fun addListener(listener: MessageListener) {
        listeners.add(listener)
    }
    
    /**
     * 移除消息监听器
     */
    fun removeListener(listener: MessageListener) {
        listeners.remove(listener)
    }
    
    /**
     * 通知所有监听器
     */
    protected fun notifyListeners(message: Message) {
        listeners.forEach { it.onMessage(message) }
    }
}

/**
 * 消息监听器接口
 */
interface MessageListener {
    /**
     * 收到消息的回调
     */
    fun onMessage(message: Message)
}

/**
 * 通信适配器接口
 */
interface CommunicationAdapter {
    /**
     * 连接状态
     */
    val isConnected: Boolean
    
    /**
     * 建立连接
     */
    suspend fun connect()
    
    /**
     * 断开连接
     */
    suspend fun disconnect()
    
    /**
     * 发送数据
     */
    suspend fun send(data: ByteArray)
    
    /**
     * 接收数据
     */
    suspend fun receive(): ByteArray
}