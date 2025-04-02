package com.airobot.core.mock

import com.airobot.core.command.CommandExecutor
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * MockLLM流式响应使用示例
 * 
 * 展示如何使用MockLLMStreamService模拟大模型流式响应
 */
class MockLLMStreamExample {
    private val mockLLMStreamService = MockLLMStreamService()
    private val commandExecutor = CommandExecutor()
    private val json = Json { prettyPrint = true }
    
    /**
     * 处理用户查询并展示流式响应
     * 
     * @param query 用户查询文本
     * @param charLevel 是否使用字符级流式输出
     */
    suspend fun processStreamQuery(query: String, charLevel: Boolean = false) {
        println("\n[用户]: $query")
        println("[AI开始回复]")
        
        // 获取流式响应
        val responseFlow = if (charLevel) {
            mockLLMStreamService.getCompletionCharStream(query)
        } else {
            mockLLMStreamService.getCompletionStream(query)
        }
        
        // 收集并处理流式响应
        var lastContent = ""
        var finalResponse: CompletionResponse? = null
        
        responseFlow.onEach { response ->
            if (response.code != 200 || response.data == null) {
                println("错误: ${response.msg}")
                return@onEach
            }
            
            // 只打印新增的内容
            val newContent = response.data.content
            if (newContent.length > lastContent.length) {
                val diff = newContent.substring(lastContent.length)
                print(diff)
                lastContent = newContent
            }
            
            // 保存最终响应用于执行命令
            if (response.msg == "success") {
                finalResponse = response
            }
        }.collect()
        
        println("\n[AI回复完成]")
        
        // 执行命令
        finalResponse?.data?.let { data ->
            val commandJson = json.encodeToString(data)
            commandExecutor.execute(commandJson)
        }
    }
    
    /**
     * 演示流式响应
     */
    suspend fun runStreamDemo() {
        println("===== 开始MockLLM流式响应演示 =====")
        
        // 问候（单词级流式输出）
        processStreamQuery("你好，机器人")
        kotlinx.coroutines.delay(4000)
        
        // 自我介绍（字符级流式输出）
        processStreamQuery("请介绍一下你自己", true)
        kotlinx.coroutines.delay(11000)
        
        println("\n===== MockLLM流式响应演示结束 =====")
    }
}

/**
 * 示例：如何在应用中使用MockLLMStreamExample
 */
//suspend fun main() {
//    val example = MockLLMStreamExample()
//    example.runStreamDemo()
//}