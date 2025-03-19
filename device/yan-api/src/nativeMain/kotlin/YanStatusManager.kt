package com.airobot.device.yanapi

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.Clock.System

/**
 * YAN设备状态管理器
 * 
 * 负责设备状态的监控、更新和事件通知
 */
class YanStatusManager {
    private val _currentStatus = MutableStateFlow<Map<String, Any>>(emptyMap())
    val currentStatus: StateFlow<Map<String, Any>> = _currentStatus
    
    private val _events = MutableStateFlow<List<String>>(emptyList())
    val events: StateFlow<List<String>> = _events
    
    private var statusCallback: ((Map<String, Any>) -> Unit)? = null
    private var monitorJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)
    
    /**
     * 启动状态监控
     */
    fun monitorStatus() {
        if (monitorJob != null) return
        
        monitorJob = scope.launch {
            while (isActive) {
                try {
                    // 模拟定期获取状态
                    val status = getCurrentStatus()
                    
                    // 通知回调
                    statusCallback?.invoke(status)
                    
                    // 延迟一段时间再次获取
                    delay(1000)
                } catch (e: Exception) {
                    notifyEvent("状态监控异常: ${e.message}")
                    delay(5000) // 出错后等待较长时间再重试
                }
            }
        }
    }
    
    /**
     * 停止状态监控
     */
    fun stopMonitor() {
        monitorJob?.cancel()
        monitorJob = null
    }
    
    /**
     * 更新设备状态
     * 
     * @param newStatus 新的状态数据
     */
    fun updateStatus(newStatus: Map<String, Any>) {
        val currentMap = _currentStatus.value.toMutableMap()
        currentMap.putAll(newStatus)
        _currentStatus.value = currentMap
    }
    
    /**
     * 通知事件
     * 
     * @param event 事件描述
     */
    fun notifyEvent(event: String) {
        val currentEvents = _events.value.toMutableList()
        currentEvents.add("${System.now().toEpochMilliseconds()}: $event")
        
        // 保留最近的100条事件记录
        if (currentEvents.size > 100) {
            currentEvents.removeAt(0)
        }
        
        _events.value = currentEvents
    }
    
    /**
     * 获取当前状态
     * 
     * @return 当前设备状态
     */
    fun getCurrentStatus(): Map<String, Any> {
        return _currentStatus.value
    }
    
    /**
     * 设置状态更新回调
     * 
     * @param callback 状态更新回调函数
     */
    fun setStatusCallback(callback: (Map<String, Any>) -> Unit) {
        statusCallback = callback
    }
    
    /**
     * 清除状态
     */
    fun clearStatus() {
        _currentStatus.value = emptyMap()
    }
    
    /**
     * 清除事件记录
     */
    fun clearEvents() {
        _events.value = emptyList()
    }
}