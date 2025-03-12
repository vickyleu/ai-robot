package com.airobot.core.command

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/**
 * 动作指令接口
 */
interface Action {
    /**
     * 执行动作
     */
    suspend fun execute(params: Map<String, JsonElement>)
}

/**
 * 动作注册表，用于管理不同设备支持的动作
 */
object ActionRegistry {
    private val actions = mutableMapOf<String, Action>()

    /**
     * 注册动作
     */
    fun register(name: String, action: Action) {
        actions[name] = action
    }

    /**
     * 获取动作
     */
    fun getAction(name: String): Action? = actions[name]
}

/**
 * 命令执行器，负责解析和执行机器人动作指令
 */
class CommandExecutor(private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)) {
    /**
     * 执行命令
     * @param jsonString AI输出的JSON指令字符串
     */
    suspend fun execute(jsonString: String) {
        val json = Json.parseToJsonElement(jsonString).jsonObject
        val content = json["content"]?.jsonPrimitive?.content
        val functions = json["functions"]?.jsonArray ?: return

        // 按delay排序
        val sortedFunctions = functions.sortedBy { 
            it.jsonObject["delay"]?.jsonPrimitive?.long ?: 0L 
        }

        // 执行每个动作
        for (func in sortedFunctions) {
            val actionObj = func.jsonObject
            val actionName = actionObj["action"]?.jsonPrimitive?.content ?: continue
            val delayMs = actionObj["delay"]?.jsonPrimitive?.long ?: 0L

            // 延时执行
            if (delayMs > 0) {
                delay(delayMs)
            }

            // 获取动作处理器并执行
            val action = ActionRegistry.getAction(actionName)
            if (action != null) {
                scope.launch {
                    val params = actionObj.toMutableMap()
                    params.remove("action")
                    params.remove("delay")
                    action.execute(params)
                }
            }
        }
    }
}