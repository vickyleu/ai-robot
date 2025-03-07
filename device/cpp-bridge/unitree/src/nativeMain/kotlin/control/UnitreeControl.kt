package com.airobot.device.cppbridge.unitree.control

class UnitreeControl {

    fun move(direction: String, speed: Double) {
        // 发送运动控制命令到Unitree机器人
        println("Moving $direction at speed $speed")
    }

    fun stop() {
        // 发送停止命令到Unitree机器人
        println("Stopping robot")
    }
} 