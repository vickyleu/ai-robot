package com.airobot.device.yanapi

import com.airobot.pythoninterop.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.*

/**
 * YAN设备传感器服务
 *
 * 提供机器人的传感器数据获取功能，如温度、湿度、距离等
 */
@OptIn(ExperimentalForeignApi::class)
class YanSensorService {
    /**
     * 传感器校准
     *
     * @param type 传感器类型
     * @return 操作是否成功
     */
    fun sensorCalibration(type: String): Boolean {
        try {
            memScoped {
                val pyType = PyUnicodeObject(type.cstr.ptr.rawValue)
                val result = sensor_calibration(pyType.reinterpret<PyObject>().ptr, 0)
                return result != null && PyObject_IsTrue(result) == 1
            }
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 获取传感器列表值
     *
     * @return 传感器列表
     */
    fun getSensorsListValue(): List<String> {
        try {
            val result = get_sensors_list_value(0)
            if (result != null) {
                val sensorList = mutableListOf<String>()
                val size = PyList_Size(result)
                
                for (i in 0 until size) {
                    val item = PyList_GetItem(result, i)
                    if (item != null) {
                        val pyStr = PyUnicode_AsUTF8(item)
                        pyStr?.toKString()?.let { sensorList.add(it) }
                    }
                }
                
                return sensorList
            }
            return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }
    }

    /**
     * 获取传感器列表
     *
     * @return 传感器列表信息
     */
    fun getSensorsList(): Map<String, Any> {
        try {
            val result = get_sensors_list(0)
            if (result != null) {
                return PyObjectToMap(result)
            }
            return emptyMap()
        } catch (e: Exception) {
            return emptyMap()
        }
    }

    /**
     * 获取环境传感器数据值
     *
     * @return 环境传感器数据
     */
    fun getSensorsEnvironmentValue(): Map<String, Any> {
        try {
            val result = get_sensors_environment_value(0)
            if (result != null) {
                return PyObjectToMap(result)
            }
            return emptyMap()
        } catch (e: Exception) {
            return emptyMap()
        }
    }

    /**
     * 获取环境传感器数据
     *
     * @return 环境传感器数据信息
     */
    fun getSensorsEnvironment(): Map<String, Any> {
        try {
            val result = get_sensors_environment(0)
            if (result != null) {
                return PyObjectToMap(result)
            }
            return emptyMap()
        } catch (e: Exception) {
            return emptyMap()
        }
    }

    /**
     * 获取陀螺仪传感器数据
     *
     * @return 陀螺仪传感器数据
     */
    fun getSensorsGyro(): Map<String, Any> {
        try {
            val result = get_sensors_gyro(0)
            if (result != null) {
                return PyObjectToMap(result)
            }
            return emptyMap()
        } catch (e: Exception) {
            return emptyMap()
        }
    }

    /**
     * 获取红外传感器数据值
     *
     * @return 红外传感器数据
     */
    fun getSensorsInfraredValue(): Map<String, Any> {
        try {
            val result = get_sensors_infrared_value(0)
            if (result != null) {
                return PyObjectToMap(result)
            }
            return emptyMap()
        } catch (e: Exception) {
            return emptyMap()
        }
    }

    /**
     * 获取压力传感器数据值
     *
     * @return 压力传感器数据
     */
    fun getSensorsPressureValue(): Map<String, Any> {
        try {
            val result = get_sensors_pressure_value(0)
            if (result != null) {
                return PyObjectToMap(result)
            }
            return emptyMap()
        } catch (e: Exception) {
            return emptyMap()
        }
    }

    /**
     * 获取触摸传感器数据值
     *
     * @return 触摸传感器数据
     */
    fun getSensorsTouchValue(): Map<String, Any> {
        try {
            val result = get_sensors_touch_value(0)
            if (result != null) {
                return PyObjectToMap(result)
            }
            return emptyMap()
        } catch (e: Exception) {
            return emptyMap()
        }
    }

    /**
     * 获取超声波传感器数据值
     *
     * @return 超声波传感器数据
     */
    fun getSensorsUltrasonicValue(): Map<String, Any> {
        try {
            val result = get_sensors_ultrasonic_value(0)
            if (result != null) {
                return PyObjectToMap(result)
            }
            return emptyMap()
        } catch (e: Exception) {
            return emptyMap()
        }
    }

    /**
     * 获取传感器数据
     *
     * @param type 传感器类型，如"temperature"、"humidity"、"distance"等
     * @return 传感器数据，包含传感器类型和对应的值
     */
    fun getSensorData(type: String): Map<String, Any> {
        try {
            memScoped {
                val pyType = PyUnicodeObject(type.cstr.ptr.rawValue)
                val result = get_sensor_data(pyType.reinterpret<PyObject>().ptr)
                
                if (result != null) {
                    val sensorData = mutableMapOf<String, Any>()
                    
                    // 获取字典的键列表
                    val keys = PyDict_Keys(result)
                    if (keys != null) {
                        val size = PyList_Size(keys)
                        
                        for (i in 0 until size) {
                            val key = PyList_GetItem(keys, i)
                            val value = PyDict_GetItem(result, key)
                            
                            if (key != null && value != null) {
                                val keyStr = PyUnicode_AsUTF8(key)?.toKString() ?: continue
                                
                                // 根据值的类型进行相应的转换
                                when {
                                    PyFloat_Check(value) != 0 -> {
                                        sensorData[keyStr] = PyFloat_AsDouble(value)
                                    }
                                    PyLong_Check(value) != 0 -> {
                                        sensorData[keyStr] = PyLong_AsLong(value)
                                    }
                                    PyUnicode_Check(value) != 0 -> {
                                        val valueStr = PyUnicode_AsUTF8(value)?.toKString() ?: continue
                                        sensorData[keyStr] = valueStr
                                    }
                                }
                            }
                        }
                    }
                    
                    return sensorData
                }
            }
            return emptyMap()
        } catch (e: Exception) {
            return emptyMap()
        }
    }
    
    /**
     * 获取所有可用的传感器类型
     *
     * @return 传感器类型列表
     */
    fun getAvailableSensorTypes(): List<String> {
        try {
            val result = get_available_sensor_types()
            if (result != null) {
                val sensorTypes = mutableListOf<String>()
                val size = PyList_Size(result)
                
                for (i in 0 until size) {
                    val item = PyList_GetItem(result, i)
                    if (item != null) {
                        val pyStr = PyUnicode_AsUTF8(item)
                        pyStr?.toKString()?.let { sensorTypes.add(it) }
                    }
                }
                
                return sensorTypes
            }
            return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }
    }
}