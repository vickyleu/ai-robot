package com.airobot.protocol.completion

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.ProxyParameters
import io.grpc.ProxyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CompletionService的gRPC客户端
 */
class CompletionClient(
    private val host: String,
    private val port: Int,
    private val proxyHost: String? = null,
    private val proxyPort: Int? = null
) {
    private val channel: ManagedChannel by lazy {
        val builder = ManagedChannelBuilder.forAddress(host, port)
            .usePlaintext()
        
        if (proxyHost != null && proxyPort != null) {
            builder.proxyDetector { _, targetServerAddress, _ ->
                ProxyParameters.newBuilder()
                    .setHost(proxyHost)
                    .setPort(proxyPort)
                    .setProxyType(ProxyType.HTTP)
                    .build()
            }
        }
        
        builder.build()
    }
    
    private val stub: CompletionServiceGrpcKt.CompletionServiceCoroutineStub by lazy {
        CompletionServiceGrpcKt.CompletionServiceCoroutineStub(channel)
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
        val request = CompletionRequest.newBuilder()
            .setQuery(query)
            .putAllInputs(inputs)
            .setUser(user)
            .apply { responseMode?.let { setResponseMode(it) } }
            .build()
            
        stub.getCompletion(request)
    }
    
    /**
     * 关闭客户端连接
     */
    fun shutdown() {
        channel.shutdown()
    }
}