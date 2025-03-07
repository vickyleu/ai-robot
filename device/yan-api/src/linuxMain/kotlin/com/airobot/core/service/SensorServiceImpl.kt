package com.airobot.core.service

import com.airobot.device.yanapi.YanSensorService
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

/**
 * 传感器服务接口的YAN机器人实现
 * 
 * 将YanSensorService适配到SensorService接口
 */
class SensorServiceImpl : SensorService {
    private val yanSensorService = YanSensorService()
    private var isInitialized = false

    // 存储各类型传感器数据的状态流
    private val sensorDataFlows =
        mutableMapOf<SensorService.SensorType, MutableStateFlow<SensorService.SensorData?>>()

    /**
     * 初始化服务
     *
     * @return 初始化是否成功
     */
    override suspend fun initialize(): Boolean {
        isInitialized = true
        return true
    }

    /**
     * 关闭服务
     *
     * @return 关闭是否成功
     */
    override suspend fun shutdown(): Boolean {
        isInitialized = false
        return true
    }

    /**
     * 获取服务名称
     *
     * @return 服务名称
     */
    override fun getServiceName(): String {
        return "YAN传感器服务"
    }

    /**
     * 检查服务是否可用
     *
     * @return 服务是否可用
     */
    override fun isAvailable(): Boolean {
        return isInitialized
    }

    /**
     * 获取所有可用传感器类型
     *
     * @return 可用传感器类型列表
     */
    override suspend fun getAvailableSensors(): List<SensorService.SensorType> {
        return withContext(Dispatchers.Default) {
            try {
                val sensorList = yanSensorService.getSensorsListValue()
                val result = mutableListOf<SensorService.SensorType>()

                // 根据YAN返回的传感器列表映射到SensorService.SensorType
                if (sensorList.any { it.contains("temperature", ignoreCase = true) }) {
                    result.add(SensorService.SensorType.TEMPERATURE)
                }
                if (sensorList.any { it.contains("humidity", ignoreCase = true) }) {
                    result.add(SensorService.SensorType.HUMIDITY)
                }
                if (sensorList.any { it.contains("pressure", ignoreCase = true) }) {
                    result.add(SensorService.SensorType.PRESSURE)
                }
                if (sensorList.any { it.contains("ultrasonic", ignoreCase = true) }) {
                    result.add(SensorService.SensorType.ULTRASONIC)
                }
                if (sensorList.any { it.contains("infrared", ignoreCase = true) }) {
                    result.add(SensorService.SensorType.INFRARED)
                }
                if (sensorList.any { it.contains("touch", ignoreCase = true) }) {
                    result.add(SensorService.SensorType.TOUCH)
                }
                if (sensorList.any { it.contains("gyro", ignoreCase = true) }) {
                    result.add(SensorService.SensorType.GYROSCOPE)
                }
                if (sensorList.any { it.contains("accel", ignoreCase = true) }) {
                    result.add(SensorService.SensorType.ACCELEROMETER)
                }
                if (sensorList.any { it.contains("light", ignoreCase = true) }) {
                    result.add(SensorService.SensorType.LIGHT)
                }

                result
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    /**
     * 获取传感器数据
     *
     * @param type 传感器类型
     * @return 传感器数据，失败返回null
     */
    override suspend fun getSensorData(type: SensorService.SensorType): SensorService.SensorData? {
        return withContext(Dispatchers.Default) {
            try {
                val value = when (type) {
                    SensorService.SensorType.TEMPERATURE -> {
                        val envData = yanSensorService.getSensorsEnvironmentValue()
                        envData["temperature"]?.toString()?.toDoubleOrNull() ?: 0.0
                    }

                    SensorService.SensorType.HUMIDITY -> {
                        val envData = yanSensorService.getSensorsEnvironmentValue()
                        envData["humidity"]?.toString()?.toDoubleOrNull() ?: 0.0
                    }

                    SensorService.SensorType.PRESSURE -> {
                        val pressureData = yanSensorService.getSensorsPressureValue()
                        pressureData.toDouble() ?: 0.0
                    }

                    SensorService.SensorType.ULTRASONIC -> {
                        val ultrasonicData = yanSensorService.getSensorsUltrasonicValue()
                        ultrasonicData.toDouble() ?: 0.0
                    }

                    SensorService.SensorType.INFRARED -> {
                        val infraredData = yanSensorService.getSensorsInfraredValue()
                        infraredData.toDouble() ?: 0.0
                    }

                    SensorService.SensorType.TOUCH -> {
                        val touchData = yanSensorService.getSensorsTouchValue()
                        touchData.toDouble() ?: 0.0
                    }
                    SensorService.SensorType.GYROSCOPE, SensorService.SensorType.ACCELEROMETER -> {
                        val gyroData = yanSensorService.getSensorsGyro()
                        @Suppress("UNCHECKED_CAST")
                        val gyroMap = gyroData["gyro"] as? Map<String, Any>
                        if (type == SensorService.SensorType.GYROSCOPE) {
                            gyroMap?.get("x")?.toString()?.toDoubleOrNull() ?: 0.0
                        } else {
                            gyroMap?.get("y")?.toString()?.toDoubleOrNull() ?: 0.0
                        }
                    }

                    SensorService.SensorType.LIGHT -> {
                        val envData = yanSensorService.getSensorsEnvironmentValue()
                        envData["light"]?.toString()?.toDoubleOrNull() ?: 0.0
                    }

                    SensorService.SensorType.CUSTOM -> 0.0
                }

                val timestamp = Clock.System.now().toEpochMilliseconds()
                val unit = when (type) {
                    SensorService.SensorType.TEMPERATURE -> "°C"
                    SensorService.SensorType.HUMIDITY -> "%"
                    SensorService.SensorType.PRESSURE -> "Pa"
                    SensorService.SensorType.ULTRASONIC -> "cm"
                    SensorService.SensorType.INFRARED -> "cm"
                    SensorService.SensorType.TOUCH -> "boolean"
                    SensorService.SensorType.GYROSCOPE -> "deg/s"
                    SensorService.SensorType.ACCELEROMETER -> "m/s²"
                    SensorService.SensorType.LIGHT -> "lux"
                    SensorService.SensorType.CUSTOM -> ""
                }

                SensorService.SensorData(type, value, timestamp, unit)
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * 获取传感器数据流
     *
     * @param type 传感器类型
     * @param interval 数据更新间隔(毫秒)
     * @return 传感器数据流
     */
    @OptIn(DelicateCoroutinesApi::class)
    override fun getSensorDataStream(
        type: SensorService.SensorType,
        interval: Long
    ): StateFlow<SensorService.SensorData?> {
        // 如果该类型的传感器数据流不存在，则创建一个
        if (!sensorDataFlows.containsKey(type)) {
            sensorDataFlows[type] = MutableStateFlow(null)

            // 启动协程定期更新传感器数据
            GlobalScope.launch(Dispatchers.Default) {
                while (isInitialized && sensorDataFlows.containsKey(type)) {
                    try {
                        val data = getSensorData(type)
                        sensorDataFlows[type]?.value = data
                    } catch (e: Exception) {
                        // 忽略异常，继续尝试获取数据
                    }
                    delay(interval)
                }
            }
        }

        return sensorDataFlows[type]!!
    }

    /**
     * 校准传感器
     *
     * @param type 传感器类型
     * @return 操作是否成功
     */
    override suspend fun calibrateSensor(type: SensorService.SensorType): Boolean {
        return withContext(Dispatchers.Default) {
            try {
                // 将SensorService.SensorType映射到YAN的传感器类型ID
                val sensorTypeId = when (type) {
                    SensorService.SensorType.GYROSCOPE -> 1
                    SensorService.SensorType.ACCELEROMETER -> 2
                    SensorService.SensorType.ULTRASONIC -> 3
                    SensorService.SensorType.INFRARED -> 4
                    SensorService.SensorType.TEMPERATURE -> 5
                    SensorService.SensorType.HUMIDITY -> 6
                    SensorService.SensorType.PRESSURE -> 7
                    SensorService.SensorType.TOUCH -> 8
                    SensorService.SensorType.LIGHT -> 9
                    SensorService.SensorType.CUSTOM -> 10
                }

                yanSensorService.sensorCalibration(sensorTypeId)
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * 设置传感器阈值
     *
     * @param type 传感器类型
     * @param min 最小阈值
     * @param max 最大阈值
     * @return 操作是否成功
     */
    override suspend fun setSensorThreshold(
        type: SensorService.SensorType,
        min: Double,
        max: Double
    ): Boolean {
        // YAN API没有直接提供设置传感器阈值的方法，这里简化实现
        return withContext(Dispatchers.Default) {
            true
        }
    }
}