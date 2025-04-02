package com.airobot.device.yanapi.pojo

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@OptIn(ExperimentalSerializationApi::class)
object SmartIntBoolSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("SmartIntBool", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: Int) {
        // 序列化时输出整型：true -> 1，false -> 0
        encoder.encodeBoolean(value != 0)
    }

    override fun deserialize(decoder: Decoder): Int {
        // 反序列化时既支持整型也支持布尔型
        return runCatching { decoder.decodeBoolean().let { if (it) 1 else 0 } }
            .getOrElse { decoder.decodeInt() }
    }
}
