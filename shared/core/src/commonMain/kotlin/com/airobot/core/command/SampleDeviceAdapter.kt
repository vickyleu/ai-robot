package com.airobot.core.command

import kotlinx.serialization.json.*

/**
 * 示例设备适配器实现
 * 展示如何将CommandExecutor与实际设备SDK连接
 */
class SampleDeviceAdapter : DeviceAdapter {
    override val deviceName: String = "SampleRobot"
    
    // 设备SDK的引用，实际实现中应该是真实的SDK实例
    private val deviceSdk = object {
        fun moveArm(angle: Int) {
            println("SDK: 移动手臂到 $angle 度")
        }
        
        fun moveHead(angle: Int) {
            println("SDK: 转动头部到 $angle 度")
        }
        
        fun speak(text: String) {
            println("SDK: 播放语音 '$text'")
        }
        
        fun raiseHands() {
            println("SDK: 举起手臂")
        }
        
        fun lowerHands() {
            println("SDK: 放下手臂")
        }
    }
    
    override fun getSupportedActions(): List<String> {
        return listOf("handsup", "handsdown", "headturn", "servo_move", "voice")
    }
    
    override suspend fun initialize() {
        println("初始化 $deviceName 设备")
        // 实际实现中可能需要连接设备、初始化SDK等
    }
    
    override suspend fun shutdown() {
        println("关闭 $deviceName 设备连接")
        // 实际实现中可能需要释放资源、关闭连接等
    }
    
    override fun registerActions() {
        // 注册举手动作
        ActionRegistry.register("handsup", object : Action {
            override suspend fun execute(params: Map<String, JsonElement>) {
                deviceSdk.raiseHands()
            }
        })
        
        // 注册放手动作
        ActionRegistry.register("handsdown", object : Action {
            override suspend fun execute(params: Map<String, JsonElement>) {
                deviceSdk.lowerHands()
            }
        })
        
        // 注册头部转动动作
        ActionRegistry.register("headturn", object : Action {
            override suspend fun execute(params: Map<String, JsonElement>) {
                val angle = params["angle"]?.jsonPrimitive?.int ?: 0
                deviceSdk.moveHead(angle)
            }
        })
        
        // 注册舵机移动动作
        ActionRegistry.register("servo_move", object : Action {
            override suspend fun execute(params: Map<String, JsonElement>) {
                val joint = params["joint"]?.jsonPrimitive?.content
                val angle = params["angle"]?.jsonPrimitive?.int ?: 0
                
                if (joint == "arm") {
                    deviceSdk.moveArm(angle)
                }
                // 可以根据不同关节添加更多条件
            }
        })
        
        // 注册语音动作
        ActionRegistry.register("voice", object : Action {
            override suspend fun execute(params: Map<String, JsonElement>) {
                val content = params["params"]?.jsonPrimitive?.content ?: ""
                deviceSdk.speak(content)
            }
        })
    }
}

/**
 * 示例：如何在应用中使用设备适配器
 */
suspend fun exampleDeviceUsage() {
    // 创建设备适配器
    val deviceAdapter = SampleDeviceAdapter()
    
    // 注册到设备管理器
    DeviceManager.registerDevice(deviceAdapter)
    
    // 初始化所有设备
    DeviceManager.initializeAll()
    
    // 创建命令执行器
    val executor = CommandExecutor()
    
    // 执行AI生成的指令
    val aiCommand = """{"content":"机器人回答文本","functions":[{"action":"handsup","delay":0},{"action":"headturn","angle":30,"delay":200}]}"""
    executor.execute(aiCommand)
    
    // 关闭所有设备
    DeviceManager.shutdownAll()
}