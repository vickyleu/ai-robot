package com.airobot.device.cppbridge.ros.service

class RosServiceManager {

    fun registerService(serviceName: String, handler: (request: Any) -> Any) {
        // 实现服务注册逻辑
    }

    fun callService(serviceName: String, request: Any): Any {
        // 实现服务调用逻辑
        return Any()
    }
} 