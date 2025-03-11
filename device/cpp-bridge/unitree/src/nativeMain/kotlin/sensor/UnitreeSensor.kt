package com.airobot.device.cppbridge.unitree.sensor

class UnitreeSensor {

    fun readSensorData(): Map<String, Any> {
        // 模拟读取传感器数据
        println("Reading sensor data")
        return mapOf("temperature" to 25.0, "battery" to 80)
    }

    fun processSensorData(data: Map<String, Any>) {
        // 处理传感器数据
        println("Processing sensor data: $data")
    }
} 