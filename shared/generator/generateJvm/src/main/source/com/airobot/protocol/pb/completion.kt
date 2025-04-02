@file:OptIn(pbandk.PublicForGeneratedCode::class)

package com.airobot.protocol.pb

@pbandk.Export
public data class CompletionRequest(
    val query: String = "",
    val inputs: Map<String, String> = emptyMap(),
    val user: String = "",
    val responseMode: String? = null,
    override val unknownFields: Map<Int, pbandk.UnknownField> = emptyMap()
) : pbandk.Message {
    override operator fun plus(other: pbandk.Message?): com.airobot.protocol.pb.CompletionRequest = protoMergeImpl(other)
    override val descriptor: pbandk.MessageDescriptor<com.airobot.protocol.pb.CompletionRequest> get() = Companion.descriptor
    override val protoSize: Int by lazy { super.protoSize }
    public companion object : pbandk.Message.Companion<com.airobot.protocol.pb.CompletionRequest> {
        public val defaultInstance: com.airobot.protocol.pb.CompletionRequest by lazy { com.airobot.protocol.pb.CompletionRequest() }
        override fun decodeWith(u: pbandk.MessageDecoder): com.airobot.protocol.pb.CompletionRequest = com.airobot.protocol.pb.CompletionRequest.decodeWithImpl(u)

        override val descriptor: pbandk.MessageDescriptor<com.airobot.protocol.pb.CompletionRequest> = pbandk.MessageDescriptor(
            fullName = "completion.CompletionRequest",
            messageClass = com.airobot.protocol.pb.CompletionRequest::class,
            messageCompanion = this,
            fields = buildList(4) {
                add(
                    pbandk.FieldDescriptor(
                        messageDescriptor = this@Companion::descriptor,
                        name = "query",
                        number = 1,
                        type = pbandk.FieldDescriptor.Type.Primitive.String(),
                        jsonName = "query",
                        value = com.airobot.protocol.pb.CompletionRequest::query
                    )
                )
                add(
                    pbandk.FieldDescriptor(
                        messageDescriptor = this@Companion::descriptor,
                        name = "inputs",
                        number = 2,
                        type = pbandk.FieldDescriptor.Type.Map<String, String>(keyType = pbandk.FieldDescriptor.Type.Primitive.String(), valueType = pbandk.FieldDescriptor.Type.Primitive.String()),
                        jsonName = "inputs",
                        value = com.airobot.protocol.pb.CompletionRequest::inputs
                    )
                )
                add(
                    pbandk.FieldDescriptor(
                        messageDescriptor = this@Companion::descriptor,
                        name = "user",
                        number = 3,
                        type = pbandk.FieldDescriptor.Type.Primitive.String(),
                        jsonName = "user",
                        value = com.airobot.protocol.pb.CompletionRequest::user
                    )
                )
                add(
                    pbandk.FieldDescriptor(
                        messageDescriptor = this@Companion::descriptor,
                        name = "response_mode",
                        number = 4,
                        type = pbandk.FieldDescriptor.Type.Primitive.String(hasPresence = true),
                        jsonName = "responseMode",
                        value = com.airobot.protocol.pb.CompletionRequest::responseMode
                    )
                )
            }
        )
    }

    public data class InputsEntry(
        override val key: String = "",
        override val value: String = "",
        override val unknownFields: Map<Int, pbandk.UnknownField> = emptyMap()
    ) : pbandk.Message, Map.Entry<String, String> {
        override operator fun plus(other: pbandk.Message?): com.airobot.protocol.pb.CompletionRequest.InputsEntry = protoMergeImpl(other)
        override val descriptor: pbandk.MessageDescriptor<com.airobot.protocol.pb.CompletionRequest.InputsEntry> get() = Companion.descriptor
        override val protoSize: Int by lazy { super.protoSize }
        public companion object : pbandk.Message.Companion<com.airobot.protocol.pb.CompletionRequest.InputsEntry> {
            public val defaultInstance: com.airobot.protocol.pb.CompletionRequest.InputsEntry by lazy { com.airobot.protocol.pb.CompletionRequest.InputsEntry() }
            override fun decodeWith(u: pbandk.MessageDecoder): com.airobot.protocol.pb.CompletionRequest.InputsEntry = com.airobot.protocol.pb.CompletionRequest.InputsEntry.decodeWithImpl(u)

            override val descriptor: pbandk.MessageDescriptor<com.airobot.protocol.pb.CompletionRequest.InputsEntry> = pbandk.MessageDescriptor(
                fullName = "completion.CompletionRequest.InputsEntry",
                messageClass = com.airobot.protocol.pb.CompletionRequest.InputsEntry::class,
                messageCompanion = this,
                fields = buildList(2) {
                    add(
                        pbandk.FieldDescriptor(
                            messageDescriptor = this@Companion::descriptor,
                            name = "key",
                            number = 1,
                            type = pbandk.FieldDescriptor.Type.Primitive.String(),
                            jsonName = "key",
                            value = com.airobot.protocol.pb.CompletionRequest.InputsEntry::key
                        )
                    )
                    add(
                        pbandk.FieldDescriptor(
                            messageDescriptor = this@Companion::descriptor,
                            name = "value",
                            number = 2,
                            type = pbandk.FieldDescriptor.Type.Primitive.String(),
                            jsonName = "value",
                            value = com.airobot.protocol.pb.CompletionRequest.InputsEntry::value
                        )
                    )
                }
            )
        }
    }
}

@pbandk.Export
public data class CompletionResponse(
    val code: Int? = null,
    val msg: String? = null,
    val data: pbandk.wkt.Value? = null,
    override val unknownFields: Map<Int, pbandk.UnknownField> = emptyMap()
) : pbandk.Message {
    override operator fun plus(other: pbandk.Message?): com.airobot.protocol.pb.CompletionResponse = protoMergeImpl(other)
    override val descriptor: pbandk.MessageDescriptor<com.airobot.protocol.pb.CompletionResponse> get() = Companion.descriptor
    override val protoSize: Int by lazy { super.protoSize }
    public companion object : pbandk.Message.Companion<com.airobot.protocol.pb.CompletionResponse> {
        public val defaultInstance: com.airobot.protocol.pb.CompletionResponse by lazy { com.airobot.protocol.pb.CompletionResponse() }
        override fun decodeWith(u: pbandk.MessageDecoder): com.airobot.protocol.pb.CompletionResponse = com.airobot.protocol.pb.CompletionResponse.decodeWithImpl(u)

        override val descriptor: pbandk.MessageDescriptor<com.airobot.protocol.pb.CompletionResponse> = pbandk.MessageDescriptor(
            fullName = "completion.CompletionResponse",
            messageClass = com.airobot.protocol.pb.CompletionResponse::class,
            messageCompanion = this,
            fields = buildList(3) {
                add(
                    pbandk.FieldDescriptor(
                        messageDescriptor = this@Companion::descriptor,
                        name = "code",
                        number = 1,
                        type = pbandk.FieldDescriptor.Type.Message(messageCompanion = pbandk.wkt.Int32Value.Companion),
                        jsonName = "code",
                        value = com.airobot.protocol.pb.CompletionResponse::code
                    )
                )
                add(
                    pbandk.FieldDescriptor(
                        messageDescriptor = this@Companion::descriptor,
                        name = "msg",
                        number = 2,
                        type = pbandk.FieldDescriptor.Type.Message(messageCompanion = pbandk.wkt.StringValue.Companion),
                        jsonName = "msg",
                        value = com.airobot.protocol.pb.CompletionResponse::msg
                    )
                )
                add(
                    pbandk.FieldDescriptor(
                        messageDescriptor = this@Companion::descriptor,
                        name = "data",
                        number = 3,
                        type = pbandk.FieldDescriptor.Type.Message(messageCompanion = pbandk.wkt.Value.Companion),
                        jsonName = "data",
                        value = com.airobot.protocol.pb.CompletionResponse::data
                    )
                )
            }
        )
    }
}

@pbandk.Export
public data class CompletionData(
    val event: String = "",
    val taskId: String = "",
    val id: String = "",
    val messageId: String = "",
    val conversationId: String = "",
    val mode: String = "",
    val answer: pbandk.wkt.Struct? = null,
    val createdAt: String = "",
    val metadata: Map<String, String> = emptyMap(),
    val outputs: Map<String, String> = emptyMap(),
    override val unknownFields: Map<Int, pbandk.UnknownField> = emptyMap()
) : pbandk.Message {
    override operator fun plus(other: pbandk.Message?): com.airobot.protocol.pb.CompletionData = protoMergeImpl(other)
    override val descriptor: pbandk.MessageDescriptor<com.airobot.protocol.pb.CompletionData> get() = Companion.descriptor
    override val protoSize: Int by lazy { super.protoSize }
    public companion object : pbandk.Message.Companion<com.airobot.protocol.pb.CompletionData> {
        public val defaultInstance: com.airobot.protocol.pb.CompletionData by lazy { com.airobot.protocol.pb.CompletionData() }
        override fun decodeWith(u: pbandk.MessageDecoder): com.airobot.protocol.pb.CompletionData = com.airobot.protocol.pb.CompletionData.decodeWithImpl(u)

        override val descriptor: pbandk.MessageDescriptor<com.airobot.protocol.pb.CompletionData> = pbandk.MessageDescriptor(
            fullName = "completion.CompletionData",
            messageClass = com.airobot.protocol.pb.CompletionData::class,
            messageCompanion = this,
            fields = buildList(10) {
                add(
                    pbandk.FieldDescriptor(
                        messageDescriptor = this@Companion::descriptor,
                        name = "event",
                        number = 1,
                        type = pbandk.FieldDescriptor.Type.Primitive.String(),
                        jsonName = "event",
                        value = com.airobot.protocol.pb.CompletionData::event
                    )
                )
                add(
                    pbandk.FieldDescriptor(
                        messageDescriptor = this@Companion::descriptor,
                        name = "task_id",
                        number = 2,
                        type = pbandk.FieldDescriptor.Type.Primitive.String(),
                        jsonName = "taskId",
                        value = com.airobot.protocol.pb.CompletionData::taskId
                    )
                )
                add(
                    pbandk.FieldDescriptor(
                        messageDescriptor = this@Companion::descriptor,
                        name = "id",
                        number = 3,
                        type = pbandk.FieldDescriptor.Type.Primitive.String(),
                        jsonName = "id",
                        value = com.airobot.protocol.pb.CompletionData::id
                    )
                )
                add(
                    pbandk.FieldDescriptor(
                        messageDescriptor = this@Companion::descriptor,
                        name = "message_id",
                        number = 4,
                        type = pbandk.FieldDescriptor.Type.Primitive.String(),
                        jsonName = "messageId",
                        value = com.airobot.protocol.pb.CompletionData::messageId
                    )
                )
                add(
                    pbandk.FieldDescriptor(
                        messageDescriptor = this@Companion::descriptor,
                        name = "conversation_id",
                        number = 5,
                        type = pbandk.FieldDescriptor.Type.Primitive.String(),
                        jsonName = "conversationId",
                        value = com.airobot.protocol.pb.CompletionData::conversationId
                    )
                )
                add(
                    pbandk.FieldDescriptor(
                        messageDescriptor = this@Companion::descriptor,
                        name = "mode",
                        number = 6,
                        type = pbandk.FieldDescriptor.Type.Primitive.String(),
                        jsonName = "mode",
                        value = com.airobot.protocol.pb.CompletionData::mode
                    )
                )
                add(
                    pbandk.FieldDescriptor(
                        messageDescriptor = this@Companion::descriptor,
                        name = "answer",
                        number = 7,
                        type = pbandk.FieldDescriptor.Type.Message(messageCompanion = pbandk.wkt.Struct.Companion),
                        jsonName = "answer",
                        value = com.airobot.protocol.pb.CompletionData::answer
                    )
                )
                add(
                    pbandk.FieldDescriptor(
                        messageDescriptor = this@Companion::descriptor,
                        name = "created_at",
                        number = 8,
                        type = pbandk.FieldDescriptor.Type.Primitive.String(),
                        jsonName = "createdAt",
                        value = com.airobot.protocol.pb.CompletionData::createdAt
                    )
                )
                add(
                    pbandk.FieldDescriptor(
                        messageDescriptor = this@Companion::descriptor,
                        name = "metadata",
                        number = 9,
                        type = pbandk.FieldDescriptor.Type.Map<String, String>(keyType = pbandk.FieldDescriptor.Type.Primitive.String(), valueType = pbandk.FieldDescriptor.Type.Primitive.String()),
                        jsonName = "metadata",
                        value = com.airobot.protocol.pb.CompletionData::metadata
                    )
                )
                add(
                    pbandk.FieldDescriptor(
                        messageDescriptor = this@Companion::descriptor,
                        name = "outputs",
                        number = 10,
                        type = pbandk.FieldDescriptor.Type.Map<String, String>(keyType = pbandk.FieldDescriptor.Type.Primitive.String(), valueType = pbandk.FieldDescriptor.Type.Primitive.String()),
                        jsonName = "outputs",
                        value = com.airobot.protocol.pb.CompletionData::outputs
                    )
                )
            }
        )
    }

    public data class MetadataEntry(
        override val key: String = "",
        override val value: String = "",
        override val unknownFields: Map<Int, pbandk.UnknownField> = emptyMap()
    ) : pbandk.Message, Map.Entry<String, String> {
        override operator fun plus(other: pbandk.Message?): com.airobot.protocol.pb.CompletionData.MetadataEntry = protoMergeImpl(other)
        override val descriptor: pbandk.MessageDescriptor<com.airobot.protocol.pb.CompletionData.MetadataEntry> get() = Companion.descriptor
        override val protoSize: Int by lazy { super.protoSize }
        public companion object : pbandk.Message.Companion<com.airobot.protocol.pb.CompletionData.MetadataEntry> {
            public val defaultInstance: com.airobot.protocol.pb.CompletionData.MetadataEntry by lazy { com.airobot.protocol.pb.CompletionData.MetadataEntry() }
            override fun decodeWith(u: pbandk.MessageDecoder): com.airobot.protocol.pb.CompletionData.MetadataEntry = com.airobot.protocol.pb.CompletionData.MetadataEntry.decodeWithImpl(u)

            override val descriptor: pbandk.MessageDescriptor<com.airobot.protocol.pb.CompletionData.MetadataEntry> = pbandk.MessageDescriptor(
                fullName = "completion.CompletionData.MetadataEntry",
                messageClass = com.airobot.protocol.pb.CompletionData.MetadataEntry::class,
                messageCompanion = this,
                fields = buildList(2) {
                    add(
                        pbandk.FieldDescriptor(
                            messageDescriptor = this@Companion::descriptor,
                            name = "key",
                            number = 1,
                            type = pbandk.FieldDescriptor.Type.Primitive.String(),
                            jsonName = "key",
                            value = com.airobot.protocol.pb.CompletionData.MetadataEntry::key
                        )
                    )
                    add(
                        pbandk.FieldDescriptor(
                            messageDescriptor = this@Companion::descriptor,
                            name = "value",
                            number = 2,
                            type = pbandk.FieldDescriptor.Type.Primitive.String(),
                            jsonName = "value",
                            value = com.airobot.protocol.pb.CompletionData.MetadataEntry::value
                        )
                    )
                }
            )
        }
    }

    public data class OutputsEntry(
        override val key: String = "",
        override val value: String = "",
        override val unknownFields: Map<Int, pbandk.UnknownField> = emptyMap()
    ) : pbandk.Message, Map.Entry<String, String> {
        override operator fun plus(other: pbandk.Message?): com.airobot.protocol.pb.CompletionData.OutputsEntry = protoMergeImpl(other)
        override val descriptor: pbandk.MessageDescriptor<com.airobot.protocol.pb.CompletionData.OutputsEntry> get() = Companion.descriptor
        override val protoSize: Int by lazy { super.protoSize }
        public companion object : pbandk.Message.Companion<com.airobot.protocol.pb.CompletionData.OutputsEntry> {
            public val defaultInstance: com.airobot.protocol.pb.CompletionData.OutputsEntry by lazy { com.airobot.protocol.pb.CompletionData.OutputsEntry() }
            override fun decodeWith(u: pbandk.MessageDecoder): com.airobot.protocol.pb.CompletionData.OutputsEntry = com.airobot.protocol.pb.CompletionData.OutputsEntry.decodeWithImpl(u)

            override val descriptor: pbandk.MessageDescriptor<com.airobot.protocol.pb.CompletionData.OutputsEntry> = pbandk.MessageDescriptor(
                fullName = "completion.CompletionData.OutputsEntry",
                messageClass = com.airobot.protocol.pb.CompletionData.OutputsEntry::class,
                messageCompanion = this,
                fields = buildList(2) {
                    add(
                        pbandk.FieldDescriptor(
                            messageDescriptor = this@Companion::descriptor,
                            name = "key",
                            number = 1,
                            type = pbandk.FieldDescriptor.Type.Primitive.String(),
                            jsonName = "key",
                            value = com.airobot.protocol.pb.CompletionData.OutputsEntry::key
                        )
                    )
                    add(
                        pbandk.FieldDescriptor(
                            messageDescriptor = this@Companion::descriptor,
                            name = "value",
                            number = 2,
                            type = pbandk.FieldDescriptor.Type.Primitive.String(),
                            jsonName = "value",
                            value = com.airobot.protocol.pb.CompletionData.OutputsEntry::value
                        )
                    )
                }
            )
        }
    }
}

@pbandk.Export
@pbandk.JsName("orDefaultForCompletionRequest")
public fun CompletionRequest?.orDefault(): com.airobot.protocol.pb.CompletionRequest = this ?: CompletionRequest.defaultInstance

private fun CompletionRequest.protoMergeImpl(plus: pbandk.Message?): CompletionRequest = (plus as? CompletionRequest)?.let {
    it.copy(
        inputs = inputs + plus.inputs,
        responseMode = plus.responseMode ?: responseMode,
        unknownFields = unknownFields + plus.unknownFields
    )
} ?: this

@Suppress("UNCHECKED_CAST")
private fun CompletionRequest.Companion.decodeWithImpl(u: pbandk.MessageDecoder): CompletionRequest {
    var query = ""
    var inputs: pbandk.MessageMap.Builder<String, String>? = null
    var user = ""
    var responseMode: String? = null

    val unknownFields = u.readMessage(this) { _fieldNumber, _fieldValue ->
        when (_fieldNumber) {
            1 -> query = _fieldValue as String
            2 -> inputs = (inputs ?: pbandk.MessageMap.Builder()).apply { this.entries += _fieldValue as kotlin.sequences.Sequence<pbandk.MessageMap.Entry<String, String>> }
            3 -> user = _fieldValue as String
            4 -> responseMode = _fieldValue as String
        }
    }

    return CompletionRequest(query, pbandk.MessageMap.Builder.fixed(inputs), user, responseMode, unknownFields)
}

@pbandk.Export
@pbandk.JsName("orDefaultForCompletionRequestInputsEntry")
public fun CompletionRequest.InputsEntry?.orDefault(): com.airobot.protocol.pb.CompletionRequest.InputsEntry = this ?: CompletionRequest.InputsEntry.defaultInstance

private fun CompletionRequest.InputsEntry.protoMergeImpl(plus: pbandk.Message?): CompletionRequest.InputsEntry = (plus as? CompletionRequest.InputsEntry)?.let {
    it.copy(
        unknownFields = unknownFields + plus.unknownFields
    )
} ?: this

@Suppress("UNCHECKED_CAST")
private fun CompletionRequest.InputsEntry.Companion.decodeWithImpl(u: pbandk.MessageDecoder): CompletionRequest.InputsEntry {
    var key = ""
    var value = ""

    val unknownFields = u.readMessage(this) { _fieldNumber, _fieldValue ->
        when (_fieldNumber) {
            1 -> key = _fieldValue as String
            2 -> value = _fieldValue as String
        }
    }

    return CompletionRequest.InputsEntry(key, value, unknownFields)
}

@pbandk.Export
@pbandk.JsName("orDefaultForCompletionResponse")
public fun CompletionResponse?.orDefault(): com.airobot.protocol.pb.CompletionResponse = this ?: CompletionResponse.defaultInstance

private fun CompletionResponse.protoMergeImpl(plus: pbandk.Message?): CompletionResponse = (plus as? CompletionResponse)?.let {
    it.copy(
        code = plus.code ?: code,
        msg = plus.msg ?: msg,
        data = data?.plus(plus.data) ?: plus.data,
        unknownFields = unknownFields + plus.unknownFields
    )
} ?: this

@Suppress("UNCHECKED_CAST")
private fun CompletionResponse.Companion.decodeWithImpl(u: pbandk.MessageDecoder): CompletionResponse {
    var code: Int? = null
    var msg: String? = null
    var data: pbandk.wkt.Value? = null

    val unknownFields = u.readMessage(this) { _fieldNumber, _fieldValue ->
        when (_fieldNumber) {
            1 -> code = _fieldValue as Int
            2 -> msg = _fieldValue as String
            3 -> data = _fieldValue as pbandk.wkt.Value
        }
    }

    return CompletionResponse(code, msg, data, unknownFields)
}

@pbandk.Export
@pbandk.JsName("orDefaultForCompletionData")
public fun CompletionData?.orDefault(): com.airobot.protocol.pb.CompletionData = this ?: CompletionData.defaultInstance

private fun CompletionData.protoMergeImpl(plus: pbandk.Message?): CompletionData = (plus as? CompletionData)?.let {
    it.copy(
        answer = answer?.plus(plus.answer) ?: plus.answer,
        metadata = metadata + plus.metadata,
        outputs = outputs + plus.outputs,
        unknownFields = unknownFields + plus.unknownFields
    )
} ?: this

@Suppress("UNCHECKED_CAST")
private fun CompletionData.Companion.decodeWithImpl(u: pbandk.MessageDecoder): CompletionData {
    var event = ""
    var taskId = ""
    var id = ""
    var messageId = ""
    var conversationId = ""
    var mode = ""
    var answer: pbandk.wkt.Struct? = null
    var createdAt = ""
    var metadata: pbandk.MessageMap.Builder<String, String>? = null
    var outputs: pbandk.MessageMap.Builder<String, String>? = null

    val unknownFields = u.readMessage(this) { _fieldNumber, _fieldValue ->
        when (_fieldNumber) {
            1 -> event = _fieldValue as String
            2 -> taskId = _fieldValue as String
            3 -> id = _fieldValue as String
            4 -> messageId = _fieldValue as String
            5 -> conversationId = _fieldValue as String
            6 -> mode = _fieldValue as String
            7 -> answer = _fieldValue as pbandk.wkt.Struct
            8 -> createdAt = _fieldValue as String
            9 -> metadata = (metadata ?: pbandk.MessageMap.Builder()).apply { this.entries += _fieldValue as kotlin.sequences.Sequence<pbandk.MessageMap.Entry<String, String>> }
            10 -> outputs = (outputs ?: pbandk.MessageMap.Builder()).apply { this.entries += _fieldValue as kotlin.sequences.Sequence<pbandk.MessageMap.Entry<String, String>> }
        }
    }

    return CompletionData(event, taskId, id, messageId,
        conversationId, mode, answer, createdAt,
        pbandk.MessageMap.Builder.fixed(metadata), pbandk.MessageMap.Builder.fixed(outputs), unknownFields)
}

@pbandk.Export
@pbandk.JsName("orDefaultForCompletionDataMetadataEntry")
public fun CompletionData.MetadataEntry?.orDefault(): com.airobot.protocol.pb.CompletionData.MetadataEntry = this ?: CompletionData.MetadataEntry.defaultInstance

private fun CompletionData.MetadataEntry.protoMergeImpl(plus: pbandk.Message?): CompletionData.MetadataEntry = (plus as? CompletionData.MetadataEntry)?.let {
    it.copy(
        unknownFields = unknownFields + plus.unknownFields
    )
} ?: this

@Suppress("UNCHECKED_CAST")
private fun CompletionData.MetadataEntry.Companion.decodeWithImpl(u: pbandk.MessageDecoder): CompletionData.MetadataEntry {
    var key = ""
    var value = ""

    val unknownFields = u.readMessage(this) { _fieldNumber, _fieldValue ->
        when (_fieldNumber) {
            1 -> key = _fieldValue as String
            2 -> value = _fieldValue as String
        }
    }

    return CompletionData.MetadataEntry(key, value, unknownFields)
}

@pbandk.Export
@pbandk.JsName("orDefaultForCompletionDataOutputsEntry")
public fun CompletionData.OutputsEntry?.orDefault(): com.airobot.protocol.pb.CompletionData.OutputsEntry = this ?: CompletionData.OutputsEntry.defaultInstance

private fun CompletionData.OutputsEntry.protoMergeImpl(plus: pbandk.Message?): CompletionData.OutputsEntry = (plus as? CompletionData.OutputsEntry)?.let {
    it.copy(
        unknownFields = unknownFields + plus.unknownFields
    )
} ?: this

@Suppress("UNCHECKED_CAST")
private fun CompletionData.OutputsEntry.Companion.decodeWithImpl(u: pbandk.MessageDecoder): CompletionData.OutputsEntry {
    var key = ""
    var value = ""

    val unknownFields = u.readMessage(this) { _fieldNumber, _fieldValue ->
        when (_fieldNumber) {
            1 -> key = _fieldValue as String
            2 -> value = _fieldValue as String
        }
    }

    return CompletionData.OutputsEntry(key, value, unknownFields)
}
