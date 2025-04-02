package com.airobot.device.yanapi.pojo

import kotlinx.serialization.*
import kotlinx.serialization.json.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*

@OptIn(ExperimentalSerializationApi::class)
object AnySerializer : KSerializer<Any> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Any")

    override fun serialize(encoder: Encoder, value: Any) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("This serializer can only be used with JSON format.")
        val jsonElement = when (value) {
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            is List<*> -> JsonArray(value.map { Json.encodeToJsonElement(this, it!!) })
            is Map<*, *> -> JsonObject(value.map { (k, v) ->
                k.toString() to Json.encodeToJsonElement(this, v!!)
            }.toMap())
            else -> throw SerializationException("Unsupported type: ${value::class}")
        }
        jsonEncoder.encodeJsonElement(jsonElement)
    }

    // 关键修复：反序列化时直接解析 JsonElement，不再创建 JsonDecoder
    override fun deserialize(decoder: Decoder): Any {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("This serializer can only be used with JSON format.")
        val jsonElement = jsonDecoder.decodeJsonElement()
        return parseJsonElement(jsonElement)
    }

    // 新增辅助方法：递归解析 JsonElement
    private fun parseJsonElement(element: JsonElement): Any = when (element) {
        is JsonPrimitive -> parsePrimitive(element)
        is JsonArray -> parseArray(element)
        is JsonObject -> parseObject(element)
    }
    // 解析基本类型
    private fun parsePrimitive(element: JsonPrimitive): Any {
        return when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.boolean
            element.doubleOrNull != null -> element.double
            else -> throw SerializationException("Unknown primitive type: $element")
        }
    }

    // 解析数组
    private fun parseArray(element: JsonArray): List<Any> {
        return element.map { parseJsonElement(it) }
    }

    // 解析对象
    private fun parseObject(element: JsonObject): Map<String, Any> {
        return element.mapValues { parseJsonElement(it.value) }
    }
}
