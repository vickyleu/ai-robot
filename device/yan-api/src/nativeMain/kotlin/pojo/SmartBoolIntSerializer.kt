package com.airobot.device.yanapi.pojo

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Smart boolean serializer that can handle various boolean representations
@OptIn(ExperimentalSerializationApi::class)
object SmartBooleanSerializer : KSerializer<Boolean> {
    override val descriptor = PrimitiveSerialDescriptor("SmartBoolean", PrimitiveKind.BOOLEAN)

    override fun serialize(encoder: Encoder, value: Boolean) {
        if (encoder is JsonEncoder) {
            encoder.encodeJsonElement(JsonPrimitive(value))
        } else {
            encoder.encodeBoolean(value)
        }
    }

    override fun deserialize(decoder: Decoder): Boolean {
        if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            println("\nSmartBooleanSerializer::deserialize: ${element::jsonPrimitive.get()}\n")
            return when {
                // Handle boolean primitive
                element is JsonPrimitive && element.booleanOrNull!=null -> element.boolean

                // Handle numeric values (0, 1)
                element is JsonPrimitive && element.intOrNull != null -> element.int != 0

                // Handle string values ("true", "false")
                element is JsonPrimitive && element.isString ->
                    when (element.content.lowercase()) {
                        "true", "1" -> true
                        "false", "0" -> false
                        else -> throw SerializationException("Cannot convert '${element.content}' to Boolean")
                    }

                else -> throw SerializationException("Unsupported JSON element for Boolean: $element")
            }
        } else {
            return decoder.decodeBoolean()
        }
    }
}