package com.airobot.protocol.completion

import com.squareup.wire.GrpcClient
import completion.CompletionRequest
import completion.CompletionResponse
import completion.CompletionServiceClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

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
        /*GrpcClient()
            .apply {
                if (proxyHost != null && proxyPort != null) {
                    proxy(ProxyConfig(proxyHost, proxyPort))
                }
            }
            .build()*/
    }
    
    private val service: CompletionServiceClient by lazy {
        CompletionServiceClient(client, host, port)
    }
    
    /**
     * 发送AI生成请求
     */
    suspend fun getCompletion(
        query: String,
        inputs: Map<String, String> = emptyMap(),
        user: String,
        responseMode: String? = null
    ): CompletionResponse = withContext(Dispatchers.IO) {
        val request = CompletionRequest(
            query = query,
            inputs = inputs,
            user = user,
            response_mode = responseMode
        )
        
        service.GetCompletion().execute(request)
    }
    
    /**
     * 关闭客户端连接
     */
    fun shutdown() {
//        client.shutdown()
    }
}