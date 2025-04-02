@file:OptIn(ExperimentalForeignApi::class, ExperimentalStdlibApi::class)
@file:Suppress("UNCHECKED_CAST")

package com.airobot.device.yanapi

import com.airobot.device.yanapi.python.toKString
import com.airobot.device.yanapi.python.typeName
import com.airobot.pythoninterop.*
import kotlinx.cinterop.*


/**
 * YAN设备连接类
 *
 * 负责与底层Python/C++通信，提供设备连接和命令发送功能
 */
class YanConnection {
    private var statusCallback: ((Map<String, Any>) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null
    private var isConnected = false


    companion object{
        init {
            println("初始化 Python 解释器...")
            // 完全初始化 Python
            Py_Initialize()
            if (Py_IsInitialized() == 0) {
                throw ConnectionException("Python 解释器初始化失败")
            }
            // 初始化线程支持
            PyEval_InitThreads()
            // 手动导入 yan_api_init 可能需要的所有模块
            val sysModule = PyImport_ImportModule("sys")
            if (sysModule == null) {
                println("错误：无法导入 sys 模块")
                PyErr_Print()
            }
            val loggingModule = PyImport_ImportModule("logging")
            if (loggingModule == null) {
                println("错误：无法导入 logging 模块")
                PyErr_Print()
            }
            // 导入 YanAPI 模块
            val yanApiModule = PyImport_ImportModule("YanAPI")
            if (yanApiModule == null) {
                println("错误：无法导入 YanAPI 模块")
                PyErr_Print()
                throw ConnectionException("无法导入 YanAPI 模块")
            }
            // 初始化完成后释放 GIL
            PyEval_SaveThread()
            println("Python 初始化成功完成")
        }
    }
    /**
     * 连接设备
     *
     */
    @OptIn(ExperimentalForeignApi::class)
    fun connect() {
        try {
            isConnected = true
            memScoped {
                val ip = "192.168.1.1"
                println("开始连接过程...")

                val gstate = PyGILState_Ensure()

                try {
                    val pyIp = PyUnicode_FromString(ip) // 或者使用 PyUnicode_FromStringAndSize
                    if (pyIp == null) {
                        PyErr_Print()
                        throw ConnectionException("无法创建 Python 字符串对象")
                    }
                    pyIp.usePinned {
                        // 直接使用 pinnedPointer 来获取指针
                        val pyIpPtr = it.get()
                        val tpName = pyIpPtr.typeName
                        println("Python 字符串对象tpName：$tpName  ${pyIpPtr.toKString()}")
                        yan_api_init(pyIpPtr) // 直接传递PyObject*指针
                        println("Python 字符串对象地址：$pyIpPtr")
                    }
                    /*println("已创建 Python 字符串对象：${pyIpUtf8.pointed.ob_type!!.pointed.readValue().toString() }")
                    Py_IncRef(pyIpUtf8)
                    // 正确传递指针到API函数
                    val typeStr = PyObject_Str(PyObject_Type(pyIpUtf8.reinterpret()))
                    val cTypeStr = PyUnicode_AsUTF8(typeStr)
                    println("参数类型：$cTypeStr")

                    val pyRepr = PyObject_Repr(pyIpUtf8.reinterpret())
                    if (pyRepr != null) {
                        val reprStr = PyUnicode_AsUTF8(pyRepr.reinterpret())
                        println("对象表示：$reprStr")
                        Py_DecRef(pyRepr)
                    }

                    val isUnicode = my_PyUnicode_Check(pyIpUtf8.reinterpret())
                    val isBytes = my_PyBytes_Check(pyIpUtf8.reinterpret())
                    if(isUnicode==1){
                        println("是 Unicode 字符串")
                    }
                    if(isBytes==1){
                        println("是 字节字符串")
                    }
                    val pySize = PyObject_Size(pyIpUtf8)
                    println("对象长度：$pySize")

                    if (PyErr_Occurred() != null) {
                        println("在调用 yan_api_init 之前发生错误：")
                        PyErr_Print()
                        PyErr_Clear()
                    }
                    try {
                        println("Python 解释器状态：${if (Py_IsInitialized() != 0) "已初始化" else "未初始化"}")
                        val result = PyRun_SimpleStringFlags("print('Python 测试成功')", null)
                        if(result==0){
                            println("Python 代码 PyRun_SimpleStringFlags 执行成功")
                        }else{
                            println("Python 代码 PyRun_SimpleStringFlags 执行失败")
                        }
                        println("调用 yan_api_init...")
                        yan_api_init(pyIpUtf8.reinterpret()) // 直接传递PyObject*指针
                        println("yan_api_init 成功完成")
                    } catch (e: Exception) {
                        println("调用 yan_api_init 时捕获到异常：${e.message}")
                        e.printStackTrace()
                    }

                    if (PyErr_Occurred() != null) {
                        println("在调用 yan_api_init 后发生错误：")
                        PyErr_Print()
                        PyErr_Clear()
                    }*/

                } finally {
                    PyGILState_Release(gstate)
                }
            }
        } catch (e: Exception) {
            isConnected = false
            throw ConnectionException("连接设备失败：${e.message}", e)
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
        val type = params["type"] as? String ?: return
        val color = params["color"] as? String ?: return
        val mode = params["mode"] as? String ?: return
        set_robot_led(PyUnicode_FromString(type), PyUnicode_FromString(color), PyUnicode_FromString(mode), 0,)
    }

    /**
     * 处理音量控制命令
     */
    private fun handleVolumeCommand(command: Map<String, Any>) {
        val params = command["params"] as? Map<String, Any> ?: return
        val volume = params["value"] as? Number ?: return
        set_robot_volume_value(PyLong_FromLong(volume.toInt()),0)
    }



    /**
     * 处理语言设置命令
     */
    private fun handleLanguageCommand(command: Map<String, Any>) {
        val params = command["params"] as? Map<String, Any> ?: return
        val language = params["value"] as? String ?: return
        memScoped {
            // 调用需要 CValuesRef<PyObject> 参数的 C 函数
            set_robot_language(PyUnicode_FromString(language),0)
        }

    }

    /**
     * 监控设备状态
     */
    private fun monitorDeviceStatus() {
        // 定期获取设备状态
        val status = mutableMapOf<String, Any>()

        try {
            // 初始化Python解释器
            Py_Initialize()
            PyEval_InitThreads()
            val mainThreadState = PyEval_SaveThread()
            // 添加GIL状态管理，确保线程安全
            val gstate = PyGILState_Ensure()
            try {
                // 获取电池信息
                try {
                    get_robot_battery_info(0)?.let { batteryInfo ->
                        // 安全地将Python对象转换为Kotlin Map
                        try {
                            status["battery"] = PyObjectToMap(batteryInfo)
                            Py_DecRef(batteryInfo) // 显式减少引用计数
                        } catch (e: Exception) {
                            println("Error converting battery info to map: ${e.message}")
                            Py_DecRef(batteryInfo) // 异常时也要减少引用计数
                        }
                    }
                } catch (e: Exception) {
                    println("Error getting battery info: ${e.message}")
                }

                // 获取LED状态
                try {
                    get_robot_led(0)?.let { ledInfo ->
                        // 安全地将Python对象转换为Kotlin Map
                        try {
                            status["led"] = PyObjectToMap(ledInfo)
                            Py_DecRef(ledInfo)
                        } catch (e: Exception) {
                            println("Error converting LED info to map: ${e.message}")
                            Py_DecRef(ledInfo)
                        }
                    }
                } catch (e: Exception) {
                    println("Error getting LED info: ${e.message}")
                }

                // 获取音量
                try {
                    get_robot_volume(0)?.let { volume ->
                        // 安全地将Python对象转换为Kotlin Map
                        try {
                            status["volume"] = PyObjectToMap(volume)
                            Py_DecRef(volume)
                        } catch (e: Exception) {
                            println("Error converting volume info to map: ${e.message}")
                            Py_DecRef(volume)
                        }
                    }
                } catch (e: Exception) {
                    println("Error getting volume info: ${e.message}")
                }
            } finally {
                // 释放GIL，确保不会导致死锁
                PyGILState_Release(gstate)
                PyEval_RestoreThread(mainThreadState)
            }
        } catch (e: Exception) {
            println("Error monitoring device status: ${e.message}")
        }

        // 通知状态更新
        statusCallback?.invoke(status)
    }
}
