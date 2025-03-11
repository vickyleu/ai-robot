package com.airobot.device.cppbridge.unitree.state

class UnitreeState {

    fun getCurrentState(): Map<String, Any> {
        // 模拟获取当前状态
        println("Getting current state")
        return mapOf("mode" to "active", "battery" to 80)
    }

    fun updateState(newState: Map<String, Any>) {
        // 更新状态
        println("Updating state to: $newState")
    }
} 