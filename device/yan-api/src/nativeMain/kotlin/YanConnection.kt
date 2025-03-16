@file:OptIn(ExperimentalForeignApi::class)

package com.airobot.device.yanapi

import com.airobot.pythoninterop.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.*


typealias PyObject = _object
/**
 * YAN设备连接类
 *
 * 负责与底层Python/C++通信，提供设备连接和命令发送功能
 */
class YanConnection {
    private var statusCallback: ((Map<String, Any>) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null
    private var isConnected = false

    private var initResult: CPointer<PyObject>?=null
    /**
     * 连接设备
     *
     * @param deviceId 设备ID
     */
    @OptIn(ExperimentalForeignApi::class)
    fun connect(deviceId: String) {
        try {
            // 初始化YAN API
            isConnected = true
//            PyInit_YanAPI()
            initResult = yan_api_init(null)  // 根据接口签名传入正确参数
            println("yan_api_init result: $initResult")
            // 启动状态监控
            monitorDeviceStatus()
        } catch (e: Exception) {
            throw ConnectionException("连接设备失败: ${e.message}", e)
        }
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        isConnected = false
    }

    /**
     * 发送数据
     *
     * @param data 要发送的数据
     */
    fun send(data: ByteArray) {
        if (!isConnected) {
            throw IllegalStateException("设备未连接")
        }

        try {
            // 根据数据类型调用相应的API
            when (val command = YanProtocol.deserialize(data)) {
                is Map<*, *> -> handleCommand(command as Map<String, Any>)
                else -> throw IllegalArgumentException("不支持的数据类型")
            }
        } catch (e: Exception) {
            errorCallback?.invoke(e.message ?: "发送数据失败")
            throw e
        }
    }

    /**
     * 设置状态更新回调
     */
    fun onStatusUpdate(callback: (Map<String, Any>) -> Unit) {
        statusCallback = callback
    }

    /**
     * 设置错误处理回调
     */
    fun onError(callback: (String) -> Unit) {
        errorCallback = callback
    }

    /**
     * 处理命令
     */
    private fun handleCommand(command: Map<String, Any>) {
        when (command["type"] as? String) {
            "led" -> handleLedCommand(command)
            "volume" -> handleVolumeCommand(command)
            "language" -> handleLanguageCommand(command)
            else -> throw IllegalArgumentException("未知的命令类型")
        }
    }

    /**
     * 处理LED控制命令
     */
    private fun handleLedCommand(command: Map<String, Any>) {
        val params = command["params"] as? Map<String, Any> ?: return
        val color = params["color"] as? String ?: return
        val mode = params["mode"] as? String ?: return
        set_robot_led(color, mode, null)
    }

    /**
     * 处理音量控制命令
     */
    private fun handleVolumeCommand(command: Map<String, Any>) {
        val params = command["params"] as? Map<String, Any> ?: return
        val volume = params["value"] as? Number ?: return
        set_robot_volume_value(volume.toInt())
    }
    fun createPythonString(value: String): CPointer<PyObject>? {
        return memScoped {
            val cString = value.cstr.ptr
            // 调用 Python C API 创建 PyObject
            Py_BuildValue
            pyApi.Py_BuildValue("s", cString)
        }
    }
    /**
     * 处理语言设置命令
     */
    private fun handleLanguageCommand(command: Map<String, Any>) {
        val params = command["params"] as? Map<String, Any> ?: return
        val language = params["value"] as? String ?: return
        memScoped {
            // 将 CPointer<PyObject>? 转为 CValuesRef<PyObject>
            val cValuesRef: CValuesRef<PyObject> = createPythonString(language)
            // 调用需要 CValuesRef<PyObject> 参数的 C 函数
            set_robot_language(cValuesRef,0)
        }

    }

    /**
     * 监控设备状态
     */
    private fun monitorDeviceStatus() {
        // 定期获取设备状态
        val status = mutableMapOf<String, Any>()

        // 获取电池信息
        get_robot_battery_info(0)?.let { batteryInfo ->
            status["battery"] = batteryInfo
        }

        // 获取LED状态
        get_robot_led(0)?.let { ledInfo ->
            status["led"] = ledInfo
        }

        // 获取音量
        get_robot_volume(0)?.let { volume ->
            status["volume"] = volume
        }

        // 通知状态更新
        statusCallback?.invoke(status)
    }
}
