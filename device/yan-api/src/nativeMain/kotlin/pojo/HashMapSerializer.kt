package com.airobot.device.yanapi.pojo

import kotlinx.serialization.Contextual
import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.mapSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

// 这是一个正确类型化的HashMap<String, Any>序列化器
object HashMapStringAnySerializer : KSerializer<HashMap<String, Any>> {
    // 使用委托序列化器来处理实际的序列化/反序列化
    private val delegateSerializer = MapSerializer(String.serializer(), AnySerializer)

    override val descriptor: SerialDescriptor = SerialDescriptor("HashMap", delegateSerializer.descriptor)

    override fun serialize(encoder: Encoder, value: HashMap<String, Any>) {
        delegateSerializer.serialize(encoder, value)
    }
    override fun deserialize(decoder: Decoder): HashMap<String, Any> {
        return HashMap(delegateSerializer.deserialize(decoder))
    }
}
//object HashMapSerializer :  KSerializer<HashMap<String, Any>> {
//    @OptIn(ExperimentalSerializationApi::class)
//    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("HashMap") {
//        mapSerialDescriptor(String.serializer().descriptor, ContextualSerializer(Any::class).descriptor)
//    }
//
//    override fun serialize(encoder: Encoder, value: HashMap<String, @Contextual Any>) {
//        val jsonEncoder = encoder as? JsonEncoder
//            ?: throw SerializationException("This serializer can only be used with Json format")
//        val jsonObject = buildJsonObject {
//            value.forEach { (key, v) ->
//                val jsonElement = when (v) {
//                    is Int -> JsonPrimitive(if (v == 0) false else true)
//                    is String -> JsonPrimitive(v)
//                    is Boolean -> JsonPrimitive(v)
//                    is Double -> JsonPrimitive(v)
//                    is Float -> JsonPrimitive(v)
//                    is Long -> JsonPrimitive(v)
//                    is Short -> JsonPrimitive(v)
//                    is Byte -> JsonPrimitive(v)
//                    else -> throw SerializationException("Unsupported value type: ${v::class}")
//                }
//                put(key, jsonElement)
//            }
//        }
//        jsonEncoder.encodeJsonElement(jsonObject)
//    }
//
//    override fun deserialize(decoder: Decoder): HashMap<String, @Contextual Any> {
//        val jsonDecoder = decoder as? JsonDecoder
//            ?: throw SerializationException("This serializer can only be used with Json format")
//        val jsonObject = jsonDecoder.decodeJsonElement().jsonObject
//        return HashMap(jsonObject.mapValues { (_, jsonElement) ->
//            when {
//                jsonElement is JsonPrimitive && jsonElement.isString -> jsonElement.content
//                jsonElement is JsonPrimitive && jsonElement.booleanOrNull!=null -> jsonElement.boolean
//                jsonElement is JsonPrimitive && jsonElement.intOrNull!=null -> jsonElement.int
//                jsonElement is JsonPrimitive && jsonElement.doubleOrNull!=null -> jsonElement.double
//                else -> throw SerializationException("Unsupported JSON element: $jsonElement")
//            }
//        }.toMap())
//    }
//}
