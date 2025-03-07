package com.airobot.device.cppbridge.ros.message

class RosMessageHandler {

    fun handleIncomingMessage(topic: String, message: Any) {
        // 实现接收消息的处理逻辑
    }

    fun prepareOutgoingMessage(topic: String, data: Any): Any {
        // 实现发送消息的准备逻辑
        return data
    }
} 