@file:OptIn(ExperimentalForeignApi::class)
@file:Suppress("UNCHECKED_CAST")
package com.airobot.device.yanapi

import com.airobot.core.device.DeviceCommand
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.json.*

/**
 * YAN协议实现
 * 
 * 负责消息的序列化、反序列化和加密解密
 */
object YanProtocol {

    /**
     * 序列化消息
     * 
     * @param message 要序列化的消息对象
     * @return 序列化后的字节数组
     */
    fun serialize(message: Any): ByteArray {
        return when (message) {
            is DeviceCommand -> serializeCommand(message)
            is Map<*, *> -> serializeMap(message as Map<String, Any>)
            else -> throw IllegalArgumentException("不支持的消息类型: ${message::class}")
        }
    }

    /**
     * 反序列化消息
     * 
     * @param data 要反序列化的字节数组
     * @return 反序列化后的对象
     */
    fun deserialize(data: ByteArray): Any {
        val jsonString = data.decodeToString()
        val jsonElement = Json.parseToJsonElement(jsonString)
        
        return when {
            jsonElement is JsonObject -> deserializeToMap(jsonElement)
            else -> throw IllegalArgumentException("不支持的JSON格式")
        }
    }

    /**
     * 加密数据
     * 
     * @param data 要加密的数据
     * @return 加密后的数据
     */
    fun encrypt(data: ByteArray): ByteArray {
        // 简单的XOR加密，实际项目中应使用更安全的加密算法
        val key: Byte = 0x42
        return ByteArray(data.size) { i -> (data[i].toInt() xor key.toInt()).toByte() }
    }

    /**
     * 解密数据
     * 
     * @param data 要解密的数据
     * @return 解密后的数据
     */
    fun decrypt(data: ByteArray): ByteArray {
        // XOR加密的解密过程与加密相同
        return encrypt(data)
    }
    
    /**
     * 序列化命令
     */
    private fun serializeCommand(command: DeviceCommand): ByteArray {
        val jsonObject = buildJsonObject {
            put("type", command.type)
            put("params", buildJsonObject {
                command.params.forEach { (key, value) ->
                    when (value) {
                        is String -> put(key, value)
                        is Number -> put(key, value.toDouble())
                        is Boolean -> put(key, value)
                        else -> put(key, value.toString())
                    }
                }
            })
        }
        
        return Json.encodeToString(JsonElement.serializer(), jsonObject).encodeToByteArray()
    }
    
    /**
     * 序列化Map
     */
    private fun serializeMap(map: Map<String, Any>): ByteArray {
        val jsonObject = buildJsonObject {
            map.forEach { (key, value) ->
                when (value) {
                    is String -> put(key, value)
                    is Number -> put(key, value.toDouble())
                    is Boolean -> put(key, value)
                    else -> put(key, value.toString())
                }
            }
        }
        
        return Json.encodeToString(JsonElement.serializer(), jsonObject).encodeToByteArray()
    }
    
    /**
     * 将JsonObject反序列化为Map
     */
    private fun deserializeToMap(jsonObject: JsonObject): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        
        jsonObject.forEach { (key, value) ->
            result[key] = when (value) {
                is JsonPrimitive -> {
                    when {
                        value.isString -> value.content
                        value.booleanOrNull != null -> value.boolean
                        value.longOrNull != null -> value.long
                        value.doubleOrNull != null -> value.double
                        else -> value.content
                    }
                }
                is JsonObject -> deserializeToMap(value)
                is JsonArray -> deserializeToList(value)
                else -> value.toString()
            }
        }
        
        return result
    }
    
    /**
     * 将JsonArray反序列化为List
     */
    private fun deserializeToList(jsonArray: JsonArray): List<Any> {
        return jsonArray.map { element ->
            when (element) {
                is JsonPrimitive -> {
                    when {
                        element.isString -> element.content
                        element.booleanOrNull != null -> element.boolean
                        element.longOrNull != null -> element.long
                        element.doubleOrNull != null -> element.double
                        else -> element.content
                    }
                }
                is JsonObject -> deserializeToMap(element)
                is JsonArray -> deserializeToList(element)
                else -> element.toString()
            }
        }
    }
}