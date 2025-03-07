package com.airobot.device.cruzr.adapter

import android.content.Context
import com.airobot.core.command.Action
import com.airobot.core.command.ActionRegistry
import com.airobot.core.command.DeviceAdapter
import com.airobot.device.cruzr.api.ActionType
import com.airobot.device.cruzr.api.CruzrDeviceApiImpl
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Cruzr设备适配器实现
 * 用于连接CommandExecutor与Cruzr设备SDK
 */
class CruzrDeviceAdapter(private val context: Context) : DeviceAdapter {
    override val deviceName: String = "CruzrRobot"

    // Cruzr设备API实例
    private val deviceApi = CruzrDeviceApiImpl(context)

    override fun getSupportedActions(): List<String> {
        return listOf(
            "move_forward",
            "move_backward",
            "turn_left",
            "turn_right",
            "stop",
            "head_turn",
            "speak"
        )
    }

    override suspend fun initialize() {
        println("初始化 $deviceName 设备")
        try {
            deviceApi.connect()
        } catch (e: Exception) {
            println("初始化 $deviceName 设备失败: ${e.message}")
            throw e
        }
    }

    override suspend fun shutdown() {
        println("关闭 $deviceName 设备连接")
        try {
            deviceApi.disconnect()
        } catch (e: Exception) {
            println("关闭 $deviceName 设备连接失败: ${e.message}")
        }
    }

    override fun registerActions() {
        // 注册前进动作
        ActionRegistry.register("move_forward", object : Action {
            override suspend fun execute(params: Map<String, JsonElement>) {
                val speed = params["speed"]?.jsonPrimitive?.intOrNull ?: 50
                deviceApi.executeAction(ActionType.MOVE_FORWARD, mapOf("speed" to speed))
            }
        })

        // 注册后退动作
        ActionRegistry.register("move_backward", object : Action {
            override suspend fun execute(params: Map<String, JsonElement>) {
                val speed = params["speed"]?.jsonPrimitive?.intOrNull ?: 50
                deviceApi.executeAction(ActionType.MOVE_BACKWARD, mapOf("speed" to speed))
            }
        })

        // 注册左转动作
        ActionRegistry.register("turn_left", object : Action {
            override suspend fun execute(params: Map<String, JsonElement>) {
                val angle = params["angle"]?.jsonPrimitive?.intOrNull ?: 90
                deviceApi.executeAction(ActionType.TURN_LEFT, mapOf("angle" to angle))
            }
        })

        // 注册右转动作
        ActionRegistry.register("turn_right", object : Action {
            override suspend fun execute(params: Map<String, JsonElement>) {
                val angle = params["angle"]?.jsonPrimitive?.intOrNull ?: 90
                deviceApi.executeAction(ActionType.TURN_RIGHT, mapOf("angle" to angle))
            }
        })

        // 注册停止动作
        ActionRegistry.register("stop", object : Action {
            override suspend fun execute(params: Map<String, JsonElement>) {
                deviceApi.stopAction()
            }
        })

        // 注册头部转动动作 (假设Cruzr支持这个动作)
        ActionRegistry.register("head_turn", object : Action {
            override suspend fun execute(params: Map<String, JsonElement>) {
                val angle = params["angle"]?.jsonPrimitive?.intOrNull ?: 0
                // 注意：这里应该使用专门的头部转动API，而不是复用TURN_LEFT
                // 由于Cruzr SDK可能没有直接提供头部转动的API，这里使用自定义参数标识
                deviceApi.executeAction(
                    ActionType.TURN_LEFT,
                    mapOf("head_angle" to angle, "is_head_movement" to true)
                )
            }
        })

        // 注册语音动作 (假设Cruzr支持语音播放)
        ActionRegistry.register("speak", object : Action {
            override suspend fun execute(params: Map<String, JsonElement>) {
                val content = params["content"]?.jsonPrimitive?.content ?: ""
                val language = params["language"]?.jsonPrimitive?.content ?: "zh-CN"
                // 注意：语音播放不应该使用ActionType.STOP，而应该使用专门的语音API
                // 这里我们通过特殊参数标识这是一个语音请求
                deviceApi.executeAction(
                    ActionType.STOP, mapOf(
                        "speech_text" to content,
                        "speech_language" to language,
                        "is_speech_action" to true
                    )
                )
            }
        })
    }
}