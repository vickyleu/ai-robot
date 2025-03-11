package com.airobot.protocol
import pbandk.Message

object Rpc {
    interface Client {
        suspend fun <Req : Message, Resp : Message> callUnary(name: String, req: Req): Resp

        class Mock(val server: Server) : Client {
            override suspend fun <Req : Message, Resp : Message> callUnary(
                name: String,
                req: Req
            ): Resp {
                return server.onUnaryCall(name, req)
            }
        }
    }

    interface Server {
        suspend fun <Req : Message, Resp : Message> onUnaryCall(
            name: String,
            req: Req
        ): Resp
    }
}