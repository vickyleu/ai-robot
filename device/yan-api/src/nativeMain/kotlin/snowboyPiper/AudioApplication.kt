@file:OptIn(ExperimentalForeignApi::class)

package snowboyPiper

import kotlinx.cinterop.ExperimentalForeignApi
import snowboyPiper.interop.AudioProcessingResourceManager

/**
 * 应用程序入口点
 * 负责初始化各种组件和资源
 */
object AudioApplication {
    private var isInitialized = false
    
    /**
     * 初始化应用程序
     * 注册资源管理器，确保程序退出时释放资源
     */
    fun initialize() {
        if (isInitialized) return
        
        // 注册资源释放钩子
        AudioProcessingResourceManager.registerShutdownHook()
        
        // 初始化完成
        isInitialized = true
        println("[INFO] 应用程序初始化完成，资源管理器已注册")
    }
    
    /**
     * 关闭应用程序
     * 释放所有资源
     */
    fun shutdown() {
        // 释放音频处理资源
        AudioProcessingResourceManager.releaseAllResources()
        
        // 重置初始化状态
        isInitialized = false
        println("[INFO] 应用程序已关闭，所有资源已释放")
    }
} 