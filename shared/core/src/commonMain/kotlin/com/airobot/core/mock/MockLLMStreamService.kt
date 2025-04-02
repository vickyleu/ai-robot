package com.airobot.core.mock

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay

/**
 * 模拟大模型流式服务
 * 
 * 该服务用于模拟大模型的流式响应，适用于需要实时显示输出的场景
 */
class MockLLMStreamService {
    private val mockLLMService = MockLLMService()
    
    /**
     * 获取流式完成响应
     * 
     * @param query 用户查询文本
     * @param inputs 额外输入参数
     * @param user 用户标识
     * @return 模拟的流式完成响应
     */
    fun getCompletionStream(
        query: String,
        inputs: Map<String, String> = emptyMap(),
        user: String = "default_user"
    ): Flow<CompletionResponse> = flow {
        // 首先获取完整的响应
        val fullResponse = mockLLMService.getCompletion(query, inputs, user)
        
        // 如果是错误响应，直接返回
        if (fullResponse.code != 200 || fullResponse.data == null) {
            emit(fullResponse)
            return@flow
        }
        
        // 模拟流式输出内容
        val content = fullResponse.data.content
        val words = content.split(" ")
        
        // 初始响应（只有函数，没有内容）
        emit(CompletionResponse(
            code = 200,
            msg = "streaming",
            data = CompletionData(
                content = "",
                functions = fullResponse.data.functions
            )
        ))
        
        // 逐字输出内容
        var currentContent = ""
        for (word in words) {
            delay(300) // 模拟网络延迟
            currentContent += if (currentContent.isEmpty()) word else " $word"
            
            emit(CompletionResponse(
                code = 200,
                msg = "streaming",
                data = CompletionData(
                    content = currentContent,
                    functions = fullResponse.data.functions
                )
            ))
        }
        
        // 最终完成响应
        delay(500)
        emit(CompletionResponse(
            code = 200,
            msg = "success",
            data = fullResponse.data
        ))
    }
    
    /**
     * 获取字符级流式完成响应（更细粒度的流式输出）
     */
    fun getCompletionCharStream(
        query: String,
        inputs: Map<String, String> = emptyMap(),
        user: String = "default_user"
    ): Flow<CompletionResponse> = flow {
        // 首先获取完整的响应
        val fullResponse = mockLLMService.getCompletion(query, inputs, user)
        
        // 如果是错误响应，直接返回
        if (fullResponse.code != 200 || fullResponse.data == null) {
            emit(fullResponse)
            return@flow
        }
        
        // 模拟流式输出内容
        val content = fullResponse.data.content
        
        // 初始响应（只有函数，没有内容）
        emit(CompletionResponse(
            code = 200,
            msg = "streaming",
            data = CompletionData(
                content = "",
                functions = fullResponse.data.functions
            )
        ))
        
        // 逐字符输出内容
        var currentContent = ""
        for (char in content) {
            delay(100) // 模拟网络延迟
            currentContent += char
            
            emit(CompletionResponse(
                code = 200,
                msg = "streaming",
                data = CompletionData(
                    content = currentContent,
                    functions = fullResponse.data.functions
                )
            ))
        }
        
        // 最终完成响应
        delay(500)
        emit(CompletionResponse(
            code = 200,
            msg = "success",
            data = fullResponse.data
        ))
    }
}