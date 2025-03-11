package com.airobot.protocol

import pbandk.gen.ServiceGenerator
import java.nio.file.Paths

@Suppress("unused")
class Generator : ServiceGenerator {
    override fun generate(service: ServiceGenerator.Service): List<ServiceGenerator.Result> {
        service.debug { "Generating code for service ${service.name} Generating code for service哈哈" }
        // This is just an example so the code is obviously simple.
        // Would use Kastree in more advanced situation
        var interfaceMethods = emptyList<String>()
        var clientMethods = emptyList<String>()
        var serverMethods = emptyList<String>()
        service.methods.forEach { method ->
            val reqType = service.kotlinTypeMappings[method.inputType!!]!!
            val respType = service.kotlinTypeMappings[method.outputType!!]!!
            val nameLit = "\"${method.name}\""
            interfaceMethods += "suspend fun ${method.name}(req: $reqType): $respType"
            clientMethods += "override suspend fun ${method.name}(req: $reqType): $respType { " +
                    "return client.callUnary($nameLit, req) }"
            serverMethods += "$nameLit -> ${method.name}(req as $reqType) as Resp"
        }
        return listOf(
            ServiceGenerator.Result(
                otherFilePath = Paths.get(service.filePath).resolveSibling(service.name + ".kt")
                    .toString(),
                code = """
                package ${service.file.kotlinPackageName}
                import com.airobot.protocol.Rpc

                interface ${service.name} {
                    ${interfaceMethods.joinToString("\n    ")}

                    class Client(val client: Rpc.Client) : ${service.name} {
                        ${clientMethods.joinToString("\n        ")}
                    }
                    
                    @kotlinx.rpc.annotations.Rpc    
                    interface Server : ${service.name} {
                        
                    }
                }
            """.trimIndent()
            )
        )
    }
}