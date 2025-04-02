package com.airobot.core.mock

import com.airobot.core.command.CommandExecutor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * MockLLM使用示例
 * 
 * 展示如何使用MockLLMService模拟大模型响应并执行命令
 */
class MockLLMExample {
    private val mockLLMService = MockLLMService()
    private val commandExecutor = CommandExecutor()
    private val json = Json { prettyPrint = true }
    
    /**
     * 处理用户查询并执行响应动作
     * 
     * @param query 用户查询文本
     */
    suspend fun processQuery(query: String) {
        // 获取模拟的大模型响应
        val response = mockLLMService.getCompletion(query)
        
        // 检查响应状态
        if (response.code != 200 || response.data == null) {
            println("错误: ${response.msg}")
            return
        }
        
        // 打印响应内容
        println("AI回复: ${response.data.content}")
        
        // 将响应转换为CommandExecutor可执行的JSON格式
        val commandJson = json.encodeToString(response.data)
        
        // 执行命令
        commandExecutor.execute(commandJson)
    }
    
    /**
     * 演示不同类型的查询
     */
    suspend fun runDemo() {
        println("===== 开始MockLLM演示 =====")
        
        // 问候
        println("\n[用户]: 你好，机器人")
        processQuery("你好，机器人")
        
        // 等待动作完成
        kotlinx.coroutines.delay(4000)
        
        // 举手
        println("\n[用户]: 请举起你的手")
        processQuery("请举起你的手")
        
        // 等待动作完成
        kotlinx.coroutines.delay(5000)
        
        // 转头
        println("\n[用户]: 请向左看")
        processQuery("请向左看")
        
        // 等待动作完成
        kotlinx.coroutines.delay(2000)
        
        // 自我介绍
        println("\n[用户]: 请介绍一下你自己")
        processQuery("请介绍一下你自己")
        
        // 等待动作完成
        kotlinx.coroutines.delay(11000)
        
        println("\n===== MockLLM演示结束 =====")
    }
}

/**
 * 示例：如何在应用中使用MockLLMExample
 */
/*
suspend fun main() {
    val example = MockLLMExample()
    example.runDemo()
}*/
