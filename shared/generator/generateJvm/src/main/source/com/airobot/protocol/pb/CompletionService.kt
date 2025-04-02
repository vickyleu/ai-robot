package com.airobot.protocol.pb
import com.airobot.protocol.Rpc

interface CompletionService {
    suspend fun GetCompletion(req: com.airobot.protocol.pb.CompletionRequest): com.airobot.protocol.pb.CompletionResponse

    class Client(val client: Rpc.Client) : CompletionService {
        override suspend fun GetCompletion(req: com.airobot.protocol.pb.CompletionRequest): com.airobot.protocol.pb.CompletionResponse { return client.callUnary("GetCompletion", req) }
    }
    
    @kotlinx.rpc.annotations.Rpc    
    interface Server : CompletionService {
        
    }
}