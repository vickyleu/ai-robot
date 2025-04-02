package com.airobot.core.mock

import kotlinx.serialization.json.*
import kotlinx.serialization.Serializable

/**
 * 模拟大模型服务
 * 
 * 该服务用于在测试环境中模拟大模型的响应，无需实际调用外部API
 * 主要用途：
 * 1. 开发测试时快速获取模拟数据
 * 2. 离线环境下进行功能测试
 * 3. 提供稳定的预设响应用于演示
 */
class MockLLMService {
    
    /**
     * 获取完成响应
     * 
     * @param query 用户查询文本
     * @param inputs 额外输入参数
     * @param user 用户标识
     * @param responseMode 响应模式
     * @return 模拟的完成响应
     */
    fun getCompletion(
        query: String,
        inputs: Map<String, String> = emptyMap(),
        user: String = "default_user",
        responseMode: String = "blocking"
    ): CompletionResponse {
        // 根据查询内容选择不同的预设响应
        return when {
            query.contains("你好") || query.contains("hello") -> createGreetingResponse()
            query.contains("举手") || query.contains("抬手") -> createRaiseHandsResponse()
            query.contains("转头") || query.contains("看") -> createHeadTurnResponse()
            query.contains("介绍") || query.contains("自我介绍") -> createIntroductionResponse()
            else -> createDefaultResponse(query)
        }
    }
    
    /**
     * 创建问候响应
     */
    private fun createGreetingResponse(): CompletionResponse {
        return CompletionResponse(
            code = 200,
            msg = "success",
            data = CompletionData(
                content = "你好！我是AI机器人，很高兴见到你。有什么我可以帮助你的吗？",
                functions = listOf(
                    CompletionFunction("handsup", 0),
                    CompletionFunction("voice", 500, "你好！我是AI机器人，很高兴见到你。"),
                    CompletionFunction("handsdown", 3000)
                )
            )
        )
    }
    
    /**
     * 创建举手响应
     */
    private fun createRaiseHandsResponse(): CompletionResponse {
        return CompletionResponse(
            code = 200,
            msg = "success",
            data = CompletionData(
                content = "好的，我现在举起手来！",
                functions = listOf(
                    CompletionFunction("voice", 0, "好的，我现在举起手来！"),
                    CompletionFunction("handsup", 1000),
                    CompletionFunction("handsdown", 4000)
                )
            )
        )
    }
    
    /**
     * 创建转头响应
     */
    private fun createHeadTurnResponse(): CompletionResponse {
        return CompletionResponse(
            code = 200,
            msg = "success",
            data = CompletionData(
                content = "我正在看向你指定的方向。",
                functions = listOf(
                    CompletionFunction("voice", 0, "我正在看向你指定的方向。"),
                    CompletionFunction("headturn", 1000, params = "30")
                )
            )
        )
    }
    
    /**
     * 创建自我介绍响应
     */
    private fun createIntroductionResponse(): CompletionResponse {
        return CompletionResponse(
            code = 200,
            msg = "success",
            data = CompletionData(
                content = "我是一个AI机器人助手，可以回答问题、执行简单指令，并且能够进行基本的动作表演。我配备了语音识别和合成功能，可以与人进行自然语言交流。",
                functions = listOf(
                    CompletionFunction("handsup", 0),
                    CompletionFunction("voice", 500, "我是一个AI机器人助手，可以回答问题、执行简单指令，并且能够进行基本的动作表演。"),
                    CompletionFunction("headturn", 5000, params = "15"),
                    CompletionFunction("voice", 6000, "我配备了语音识别和合成功能，可以与人进行自然语言交流。"),
                    CompletionFunction("handsdown", 10000)
                )
            )
        )
    }
    
    /**
     * 创建默认响应
     */
    private fun createDefaultResponse(query: String): CompletionResponse {
        return CompletionResponse(
            code = 200,
            msg = "success",
            data = CompletionData(
                content = "我收到了你的问题：'$query'。但我目前只是一个模拟服务，无法提供真实的回答。",
                functions = listOf(
                    CompletionFunction("voice", 0, "我收到了你的问题，但我目前只是一个模拟服务，无法提供真实的回答。")
                )
            )
        )
    }
    
    /**
     * 创建错误响应
     */
    fun createErrorResponse(errorMessage: String): CompletionResponse {
        return CompletionResponse(
            code = 500,
            msg = errorMessage,
            data = null
        )
    }
}

/**
 * 完成响应数据类
 */
@Serializable
data class CompletionResponse(
    val code: Int,
    val msg: String,
    val data: CompletionData?
)

/**
 * 完成数据类
 */
@Serializable
data class CompletionData(
    val content: String,
    val functions: List<CompletionFunction>
)

/**
 * 完成功能类
 */
@Serializable
data class CompletionFunction(
    val action: String,
    val delay: Int,
    val params: String = ""
)