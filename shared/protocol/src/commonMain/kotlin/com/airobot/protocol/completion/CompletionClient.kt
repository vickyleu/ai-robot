package com.airobot.protocol.completion

import com.airobot.protocol.pb.CompletionRequest
import com.airobot.protocol.pb.CompletionResponse
import com.airobot.protocol.pb.CompletionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.withContext
import kotlinx.rpc.grpc.GrpcClient
import kotlinx.rpc.withService


/**
 * CompletionService的gRPC客户端,使用wire通信
 */
class CompletionClient(
    private val host: String,
    private val port: Int,
    private val proxyHost: String? = null,
    private val proxyPort: Int? = null
) {
    private val client: GrpcClient by lazy {
        GrpcClient(host, port) {
//            usePlaintext()
        }
    }

    private val service: CompletionService by lazy {
        client.withService<CompletionService.Server>()
    }

    /**
     * 发送AI生成请求
     */
    suspend fun getCompletion(
        query: String,
        inputs: Map<String, String> = emptyMap(),
        user: String,
        responseMode: String? = null
    ): CompletionResponse = withContext(Dispatchers.Default) {
        val request = CompletionRequest(
            query = query,
            inputs = inputs,
            user = user,
            responseMode = responseMode
        )
        service.GetCompletion(request)
    }

    /**
     * 关闭客户端连接
     */
    fun shutdown() {
        client.coroutineContext.cancelChildren()
    }
}