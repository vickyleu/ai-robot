@file:OptIn(ExperimentalForeignApi::class, ExperimentalStdlibApi::class)
@file:Suppress("UNCHECKED_CAST")

package com.airobot.device.yanapi

import com.airobot.core.utils.thread.getThreadName
import com.airobot.pythoninterop.PyErr_Clear
import com.airobot.pythoninterop.PyErr_Occurred
import com.airobot.pythoninterop.PyErr_Print
import com.airobot.pythoninterop.PyGILState_Check
import com.airobot.pythoninterop.PyGILState_Ensure
import com.airobot.pythoninterop.PyGILState_Release
import com.airobot.pythoninterop.PyLong_FromLong
import com.airobot.pythoninterop.PyUnicode_FromString
import com.airobot.pythoninterop.Py_DecRef
import com.airobot.pythoninterop.Py_IsInitialized
import com.airobot.pythoninterop.get_robot_battery_info
import com.airobot.pythoninterop.get_robot_led
import com.airobot.pythoninterop.get_robot_volume
import com.airobot.pythoninterop.set_robot_language
import com.airobot.pythoninterop.set_robot_led
import com.airobot.pythoninterop.set_robot_volume_value
import com.airobot.pythoninterop.yan_api_init
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlin.experimental.ExperimentalNativeApi


/**
 * YAN设备连接类
 *
 * 负责与底层Python/C++通信，提供设备连接和命令发送功能
 */
class YanConnection {
    private var statusCallback: ((Map<String, Any>) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null
    private var isConnected = false





    /**
     * 连接设备
     *
     */
    @OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
    suspend fun connect(ip: String="127.0.0.1"
    /*默认连接机器人本机,这是为了测试api访问是否正常*/
    ) {
          println("[YanConnection] 开始连接设备...\n")
          println("[YanConnection] 当前线程: ${getThreadName()}\n")
          try {
              withContext(Dispatchers.Unconfined) {
                  async {
                      println("[YanConnection] 当前线程: ${getThreadName()}\n")
                      println("[YanConnection] 等待YanDevice初始化完成...\n")
                      if(YanDevice.instance.completableDeferred.isCompleted.not()){
                          YanDevice.instance.completableDeferred.await()
                      }
                      println("[YanConnection] YanDevice状态: ${if (Py_IsInitialized() != 0) "已初始化" else "未初始化"}\n")
                      if(Py_IsInitialized()==0){
                          println("[YanConnection] YanDevice初始化失败")
                          return@async
                      }
                      println("[YanConnection] YanDevice初始化完成")
                      memScoped {
                          println("[YanConnection] 开始连接过程，目标IP: $ip\n")
                          println("[YanConnection] 获取Python GIL...\n")
                          val gstate = PyGILState_Ensure()
                          // 下面的代码没有作用, 已经注释了
//                          PyEval_AcquireLock()
//                          val threadState = PyThreadState_New(PyInterpreterState_Head())
//                          PyEval_ReleaseLock()
                          println("[YanConnection] 成功获取Python GIL\n")
                          try {
                              val pyIp = PyUnicode_FromString(ip)
                              if (pyIp == null) {
                                  println("[YanConnection] 错误：无法创建Python字符串对象\n")
                                  println("[YanConnection] 检查Python错误...\n")
                                  PyErr_Print()
                                  throw ConnectionException("无法创建 Python 字符串对象\n")
                              }
                              if (PyErr_Occurred() != null) {
                                  PyErr_Print()
                                  PyErr_Clear()
                              }
                              println("[YanConnection] 调用yan_api_init初始化API...\n")
                              if (PyGILState_Check() == 0) {
                                  throw IllegalStateException("[ERROR] 未持有 GIL")
                              }
                              yan_api_init(pyIp, 0)
                              println("[YanConnection] yan_api_init调用成功\n")
                              Py_DecRef(pyIp)
                              if (PyErr_Occurred() != null) {
                                  println("[YanConnection] 在调用yan_api_init后发现Python错误:\n")
                                  PyErr_Print()
                                  PyErr_Clear()
                                  throw IllegalStateException("在调用yan_api_init后发现Python错误")
                              }
                              isConnected = true
                              println("[YanConnection] 设备连接成功，状态已更新为: $isConnected\n")
                          }catch (e: Exception) {
                              println("[YanConnection] 连接过程中发生异常: ${e.message}\n")
                              println("[YanConnection] 异常堆栈: ${e.stackTraceToString()}\n")
                              throw e
                          }
                          finally {
                              // 释放GIL
                              println("[YanConnection] 释放Python GIL...\n")
                              // 清理
                              PyGILState_Release(gstate)
                              println("[YanConnection] Python GIL已释放\n")
                          }
                      }
                  }
              }
          } catch (e: Exception) {
              isConnected = false
              println("[YanConnection] 连接失败，状态已更新为: $isConnected\n")
              println("[YanConnection] 连接失败异常: ${e.message}\n")
              println("[YanConnection] 异常堆栈: ${e.stackTraceToString()}\n")
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
            // 添加GIL状态管理，确保线程安全
            val gstate = PyGILState_Ensure()
            try {
                // 获取电池信息
                try {
                    get_robot_battery_info(0)?.let { batteryInfo ->
                        // 安全地将Python对象转换为Kotlin Map
                        try {
                            status["battery"] = PyObjectToKoltinMap(batteryInfo)
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
                            status["led"] = PyObjectToKoltinMap(ledInfo)
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
                            status["volume"] = PyObjectToKoltinMap(volume)
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
            }
        } catch (e: Exception) {
            println("Error monitoring device status: ${e.message}")
        }

        // 通知状态更新
        statusCallback?.invoke(status)
    }
}
