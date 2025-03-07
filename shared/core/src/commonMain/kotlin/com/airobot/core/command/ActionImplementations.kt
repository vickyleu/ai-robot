package com.airobot.core.command

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

/**
 * 示例动作实现类
 */
class ActionImplementations {
    /**
     * 初始化并注册所有动作
     */
    fun initialize() {
        // 注册举手动作
        ActionRegistry.register("handsup", object : Action {
            override suspend fun execute(params: Map<String, JsonElement>) {
                println("执行举手动作")
                // 实际实现中调用设备SDK
            }
        })
        
        // 注册放手动作
        ActionRegistry.register("handsdown", object : Action {
            override suspend fun execute(params: Map<String, JsonElement>) {
                println("执行放手动作")
                // 实际实现中调用设备SDK
            }
        })
        
        // 注册头部转动动作
        ActionRegistry.register("headturn", object : Action {
            override suspend fun execute(params: Map<String, JsonElement>) {
                val angle = params["angle"]?.jsonPrimitive?.int ?: 0
                println("执行头部转动动作，角度: $angle")
                // 实际实现中调用设备SDK
            }
        })
        
        // 注册舵机移动动作
        ActionRegistry.register("servo_move", object : Action {
            override suspend fun execute(params: Map<String, JsonElement>) {
                val joint = params["joint"]?.jsonPrimitive?.content
                val angle = params["angle"]?.jsonPrimitive?.int ?: 0
                println("执行舵机移动动作，关节: $joint, 角度: $angle")
                // 实际实现中调用设备SDK
            }
        })
        
        // 注册语音动作
        ActionRegistry.register("voice", object : Action {
            override suspend fun execute(params: Map<String, JsonElement>) {
                val content = params["params"]?.jsonPrimitive?.content
                println("执行语音动作，内容: $content")
                // 实际实现中调用设备SDK
            }
        })
    }
    
    /**
     * 示例：使用CommandExecutor执行AI生成的指令
     */
    suspend fun executeAiCommand(jsonString: String) {
        val executor = CommandExecutor()
        executor.execute(jsonString)
    }
    
    /**
     * 示例：生成测试用的AI指令JSON
     */
    fun generateSampleCommand(): String {
        return """{
            "content": "机器人回答文本",
            "functions": [
                { "action": "handsup", "delay": 0 },
                { "action": "headturn", "angle": 30, "delay": 200 },
                { "action": "servo_move", "joint": "arm", "angle": 45, "delay": 300 },
                { "action": "voice", "params": "语音内容", "delay": 500 },
                { "action": "handsdown", "delay": 1000 }
            ]
        }""".trimIndent()
    }
}

/**
 * 示例：如何在应用中使用
 */
suspend fun exampleUsage() {
    // 初始化动作实现
    val actionImpl = ActionImplementations()
    actionImpl.initialize()
    
    // 生成示例命令并执行
    val sampleCommand = actionImpl.generateSampleCommand()
    actionImpl.executeAiCommand(sampleCommand)
}