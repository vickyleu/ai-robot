@file:OptIn(ExperimentalForeignApi::class, ExperimentalStdlibApi::class)
@file:Suppress("FunctionName", "unused", "UNUSED_PARAMETER", "RedundantSuspendModifier")

package com.airobot.device.yanapi

import androidx.annotation.IntRange
import com.airobot.core.device.Device
import com.airobot.core.device.DeviceCommand
import com.airobot.core.device.DeviceStatus
import com.airobot.core.device.DeviceType
import com.airobot.core.device.isConnected
import com.airobot.device.yanapi.Servo.ServoMode
import com.airobot.device.yanapi.Servo.ServoName
import com.airobot.device.yanapi.python.PyObject
import com.airobot.device.yanapi.python.PyTuple_Check
import com.airobot.device.yanapi.python.Py_XDECREF
import com.airobot.device.yanapi.python.getDictValue
import com.airobot.device.yanapi.python.toFuncHexPointer
import com.airobot.device.yanapi.python.toKString
import com.airobot.pythoninterop.PyErr_Clear
import com.airobot.pythoninterop.PyErr_Occurred
import com.airobot.pythoninterop.PyErr_Print
import com.airobot.pythoninterop.PyEval_InitThreads
import com.airobot.pythoninterop.PyEval_RestoreThread
import com.airobot.pythoninterop.PyEval_SaveThread
import com.airobot.pythoninterop.PyGILState_Ensure
import com.airobot.pythoninterop.PyGILState_Release
import com.airobot.pythoninterop.PyImport_AppendInittab
import com.airobot.pythoninterop.PyImport_ImportModule
import com.airobot.pythoninterop.PyList_Append
import com.airobot.pythoninterop.PyObject_GetAttrString
import com.airobot.pythoninterop.PyRun_SimpleStringFlags
import com.airobot.pythoninterop.PyUnicode_FromString
import com.airobot.pythoninterop.Py_DecRef
import com.airobot.pythoninterop.Py_Finalize
import com.airobot.pythoninterop.Py_IncRef
import com.airobot.pythoninterop.Py_Initialize
import com.airobot.pythoninterop.Py_SetProgramName
import com.airobot.pythoninterop._object
import com.airobot.pythoninterop._ts
import com.airobot.pythoninterop.my_PyInit_YanAPI
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import platform.posix.LC_ALL
import platform.posix.fprintf
import platform.posix.setlocale
import platform.posix.stderr

/**
 * YAN设备实现类
 *
 * 该类实现了Device接口，提供与YAN机器人的通信和控制功能。
 * 主要职责包括：
 * 1. 初始化和管理Python解释器环境
 * 2. 加载和维护必要的Python模块
 * 3. 管理全局解释器锁(GIL)状态
 * 4. 提供设备连接、断开和命令发送功能
 * 5. 协调各种服务组件的调用
 *
 * 该类采用单例模式设计，确保全局只有一个YanDevice实例。
 */
class YanDevice : Device {
    // 系统服务组件
    internal val locomotionService = YanLocomotionService()
    private val speechService = YanSpeechService()
    private val servoService = YanServoService()
    private val sensorService = YanSensorService()
    private val powerService = YanPowerService()
    private val lightService = YanLightService()
    private val visionService = YanVisionService()
    private val skillManager = YanSkillManager()
    private val diagnosisService = YanDiagnosisService()
    
    // Device接口实现
    override val deviceId: String get() = ""
    override val name: String get() = ""
    override val type: DeviceType = DeviceType.YAN

    // 设备状态管理
    private val _status = MutableStateFlow(DeviceStatus.DISCONNECTED)
    override val status: StateFlow<DeviceStatus> = _status

    // 协议和状态管理
    private val protocol = YanProtocol
    private val statusManager = YanStatusManager()

    // 设备连接
    private lateinit var connection: YanConnection

    // 初始化状态追踪
    private val innerDeferred = CompletableDeferred<Boolean>()
    internal val completableDeferred = CompletableDeferred<Boolean>()
    
    // Python主线程状态
    internal lateinit var mainThreadState: CPointer<_ts>

    /**
     * 私有构造函数，防止外部直接实例化
     */
    @Suppress("UnUsed")
    private constructor()

    companion object {
        // 单例实例
        private var _yan: YanDevice? = null
        
        /**
         * 获取YanDevice单例实例
         * 
         * 如果实例不存在，则创建新实例并初始化Python环境
         * 
         * @return YanDevice单例实例
         */
        val instance: YanDevice
            get() = _yan ?: run {
                _yan = YanDevice().apply {
                    initPythonEnv()
                }
                _yan!!
            }

        /**
         * 彩色日志模板
         * 
         * 用于配置Python日志输出的颜色格式
         */
        @Suppress("SpellCheckingInspection")
        private val colorLogTemplate = """
                    |import colorlog;
                    |logger = logging.getLogger();
                    |logger.setLevel(logging.INFO);
                    |console_handler = logging.StreamHandler();
                    |console_handler.setLevel(logging.INFO);
                    |log_format = '%(log_color)s[%(asctime)s] [%(levelname)s] - %(message)s';
                    |formatter = colorlog.ColoredFormatter(log_format, log_colors={
                    |    'DEBUG': 'cyan',
                    |    'INFO': 'green',
                    |    'WARNING': 'yellow',
                    |    'ERROR': 'red',
                    |   'CRITICAL': 'red,bg_white'
                    |});
                    |console_handler.setFormatter(formatter);
                    |if logger.hasHandlers():
                    |    logger.handlers.clear();
                    |logger.addHandler(console_handler)
            """.trimMargin()
    }

    // YanAPI Python模块引用
    private var yanAPIModule: CPointer<PyObject>? = null

    /**
     * 初始化Python解释器环境
     * 
     * 该方法完成以下任务：
     * 1. 注册内置模块
     * 2. 初始化Python解释器
     * 3. 配置日志系统
     * 4. 导入必要的Python模块
     * 5. 初始化线程和GIL管理
     */
    @Suppress("SpellCheckingInspection")
    private fun initPythonEnv() {

        /**
         *
         */
//        com.airobot.voskinterop.vosk_gpu_init()
        com.airobot.alsainterop.SND_PCM_TSTAMP_ENABLE

        println("初始化 Python 解释器...\n")
        runBlocking {
            withContext(Dispatchers.Unconfined) {
                async {
                    memScoped {
                        // 获取YanAPI初始化函数
                        val pyInitFunc = my_PyInit_YanAPI()!!
                        if (PyErr_Occurred() != null) {
                            PyErr_Clear()
                        }
                        
                        // 输出函数地址用于调试
                        val addrHex = pyInitFunc.toFuncHexPointer()
                        println("PyInit_Yanshee 函数地址: $addrHex")
                        
                        // 注册内置模块
                        if (PyImport_AppendInittab("Yanshee", pyInitFunc) == -1) {
                            fprintf(
                                stderr,
                                "[FATAL] 模块注册失败: %s\n",
                                PyErr_Occurred()?.toKString()
                            )
                            innerDeferred.complete(false)
                            return@async
                        }
                        
                        // 检查错误
                        if (PyErr_Occurred() != null) {
                            PyErr_Print()
                            fprintf(stderr, "[FATAL] PyImport_AppendInittab 出问题了\n")
                            innerDeferred.complete(false)
                            return@async
                        }
                        
                        // 设置区域和程序名
                        setlocale(LC_ALL, "")
                        val programName = PyUnicode_FromString("Yanshee")
                        Py_SetProgramName(programName)

                        // 初始化解释器
                        Py_Initialize()
                        if (PyErr_Occurred() != null) {
                            PyErr_Print()
                            fprintf(stderr, "[FATAL] 初始化出问题了\n")
                            innerDeferred.complete(false)
                            return@async
                        }

                        // 获取sys模块并配置
                        val sysModule = PyImport_ImportModule("sys")
                        if (sysModule == null) {
                            PyErr_Print()
                            fprintf(stderr, "[ERROR] 无法导入 sys 模块\n")
                            innerDeferred.complete(false)
                            return@async
                        }
                        
                        // 添加彩色日志支持
                        if (!appendColorLog(sysModule)) {
                            innerDeferred.complete(false)
                            return@async
                        }
                        
                        // 检查错误
                        if (PyErr_Occurred() != null) {
                            PyErr_Print()
                            fprintf(stderr, "[FATAL] 初始化出问题了\n")
                            innerDeferred.complete(false)
                            return@async
                        }
                        
                        // 初始化线程和GIL
                        PyEval_InitThreads()
                        val sysModulesList = PyObject_GetAttrString(sysModule, "builtin_module_names")
                        if (sysModulesList == null) {
                            PyErr_Print()
                            fprintf(stderr, "[ERROR] 无法获取 sys.sysModules\n")
                            innerDeferred.complete(false)
                            return@async
                        }
                        
                        // 检查类型
                        if (!PyTuple_Check(sysModulesList)) {
                            fprintf(stderr, "[ERROR] sys.builtin_module_names 不是Tuple类型\n")
                            Py_DecRef(sysModulesList)
                            Py_DecRef(sysModule)
                            innerDeferred.complete(false)
                            return@async
                        }
                        
                        // 导入必要模块
                        val loggingModule = PyImport_ImportModule("logging")
                        checkEnv()
                        val ioModule = PyImport_ImportModule("io")  // 强制初始化 io 模块
                        val libUkitModule = PyImport_ImportModule("lib_ukit")  // 强制初始化 lib_ukit

                        // 检查模块导入状态
                        if (loggingModule == null ||
                            ioModule == null ||
                            libUkitModule == null ||
                            PyErr_Occurred() != null
                        ) {
                            PyErr_Print()
                            fprintf(stderr, "[FATAL] 依赖模块导入失败，请检查依赖和路径\n")
                            innerDeferred.complete(false)
                            return@async
                        }

                        // 导入YanAPI模块
                        @Suppress("LocalVariableName")
                        val yanAPIModule_ = PyImport_ImportModule("Yanshee")
                        if (yanAPIModule_ == null || PyErr_Occurred() != null) {
                            PyErr_Print()
                            fprintf(stderr, "[FATAL] 模块导入失败\n")
                            innerDeferred.complete(false)
                            return@async
                        }
                        
                        // 检查模块信息
                        val moduleFile = yanAPIModule_.getDictValue("__file__")
                        if (moduleFile != null) {
                            if (moduleFile == "built-in") {
                                println("[DEBUG] YanAPI模块是内建模块")
                            } else {
                                println("[DEBUG] YanAPI模块文件路径: $moduleFile")
                                fprintf(stderr, "[WARNING] 找到了文件路径，这可能不是内置模块\n")
                            }
                        } else {
                            println("[DEBUG] 没有找到__file__属性，这可能是内置模块")
                        }
                        
                        // 输出模块信息
                        println("YanApi模块basic_url: ${yanAPIModule_.getDictValue("basic_url")}")
                        println("logging模块版本: ${loggingModule.getDictValue("__version__")}")
                        println("io模块版本: ${ioModule.getDictValue("__version__")}")
                        println("libUkit模块版本: ${libUkitModule.getDictValue("__version__")}")
                        
                        // 增加引用计数并保存模块引用
                        Py_IncRef(yanAPIModule_)
                        yanAPIModule = yanAPIModule_
                        Py_IncRef(loggingModule)
                        Py_IncRef(ioModule)
                        Py_IncRef(libUkitModule)
                        
                        println("[DEBUG] YanAPI加载成功\n")
                        innerDeferred.complete(true)
                    }
                }
            }
        }
    }

    /**
     * 检查Python环境配置
     * 
     * 验证YanAPI模块是否正确加载为内置模块
     */
    @Suppress("SpellCheckingInspection")
    private fun checkEnv() {
        /*PyRun_SimpleStringFlags(
            """
                            |import sys
                            |import logging
                            |import pprint
                            |$colorLogTemplate
                            |logging.info("Yanshee builtin_module_names:\n%s", pprint.pformat(sys.builtin_module_names))
                            |# 获取输出内容
                            |output = s.getvalue()
                            |print(output)
                        """.trimMargin(), null
        )*/
        println("")
        // 检查YanAPI是否在内置模块中
        PyRun_SimpleStringFlags(
            """
                                |import io
                                |import sys
                                |import logging
                                |import pprint
                                |$colorLogTemplate
                                |logging.info("Yanshee 是否在内置模块中:\n%s", 'Yanshee' in sys.builtin_module_names)
                            """.trimMargin(),
            null // arg1: CValuesRef<PyCompilerFlags>?
        )
        println("")
    }

    /**
     * 添加彩色日志支持
     * 
     * 将colorlog模块路径添加到Python系统路径中，并导入模块
     * 
     * @param sysModule Python sys模块引用
     * @return 操作是否成功
     */
    @Suppress("SpellCheckingInspection")
    private fun appendColorLog(sysModule: CPointer<_object>): Boolean {
        // 获取sys.path列表
        val sysPath = PyObject_GetAttrString(sysModule, "path")
        if (sysPath == null) {
            PyErr_Print()
            fprintf(stderr, "[ERROR] 无法获取 sys.path\n")
            return false
        }
        
        // 创建路径对象
        val path = PyUnicode_FromString("/usr/share/yanshee/python")
        if (path == null) {
            PyErr_Print()
            fprintf(stderr, "[ERROR] 无法创建路径对象\n")
            return false
        }
        
        // 添加路径到sys.path
        if (PyList_Append(sysPath, path) != 0) {
            PyErr_Print()
            fprintf(stderr, "[ERROR] 无法将路径添加到 sys.path\n")
            return false
        }
        
        // 导入colorlog模块
        val colorlogModule = PyImport_ImportModule("colorlog")
        if (colorlogModule == null || PyErr_Occurred() != null) {
            PyErr_Print()
            fprintf(stderr, "[FATAL] 依赖模块导入失败，请检查依赖和路径\n")
            return false
        }
        
        return true
    }

    /**
     * 清理Python环境
     * 
     * 释放模块引用并终止Python解释器
     */
    @Suppress("SpellCheckingInspection")
    private fun unattachEnv() {
        Py_XDECREF(yanAPIModule)
        Py_Finalize()
    }

    /**
     * 初始化设备
     * 
     * 等待Python解释器初始化完成，并释放GIL
     */
    suspend fun initDevice() {
        withContext(Dispatchers.Unconfined) {
            memScoped {
                async {
                    println("[DEBUG] 模块读取完成, 等待Python解释器初始化\n")
                    val complete = innerDeferred.await()
                    if (complete) {
                        if (yanAPIModule != null) {
                            println("[DEBUG] 解释器已完成\n")
                            releaseGil()
                            completableDeferred.complete(true)
                            return@async
                        }
                    }
                    completableDeferred.complete(false)
                }.invokeOnCompletion { throwable ->
                    throwable?.printStackTrace()
                    completableDeferred.complete(false)
                }
            }
        }
    }

    /**
     * 释放主线程的GIL
     * 
     * 允许其他Python线程执行
     */
    private fun releaseGil() {
        mainThreadState = PyEval_SaveThread()!! // 释放主线程 GIL
    }

    /**
     * 重新获取主线程的GIL
     * 
     * 在需要执行Python代码时调用
     */
    private fun requireMainGil() {
        if (::mainThreadState.isInitialized) {
            PyEval_RestoreThread(mainThreadState) // 重新获取GIL
        }
    }

    /**
     * 连接设备
     * 
     * 建立与YAN设备的连接，并设置状态监控
     * 
     * @throws ConnectionException 连接失败时抛出
     */
    override suspend fun connect() {
        try {
            _status.value = DeviceStatus.CONNECTING
            // 创建连接
            connection = YanConnection()
            println("开始连接\n")
            connection.connect()
            requireMainGil()
            // 设置状态更新回调
            onStatusUpdate { status ->
                statusManager.updateStatus(status)
            }
            // 设置错误处理回调
            connection.onError { error ->
                _status.value = DeviceStatus.ERROR
                statusManager.notifyEvent("error: $error")
            }
            // 启动状态监控
            statusManager.monitorStatus()
            _status.value = DeviceStatus.CONNECTED
            releaseGil()
        } catch (e: Exception) {
            println("连接失败: ${e.message}\n")
            _status.value = DeviceStatus.ERROR
            throw ConnectionException("连接失败: ${e.message}", e)
        }
    }

    /**
     * 断开设备连接
     * 
     * @throws ConnectionException 断开连接失败时抛出
     */
    override suspend fun disconnect() {
        if (!::connection.isInitialized) {
            _status.value = DeviceStatus.DISCONNECTED
            return
        }
        if(status.value.isConnected.not())return
        try {
            connection.disconnect()
            statusManager.stopMonitor()
            _status.value = DeviceStatus.DISCONNECTED
        } catch (e: Exception) {
            // 即使断开连接失败，也将状态设置为断开
            _status.value = DeviceStatus.DISCONNECTED
            throw ConnectionException("断开连接失败: ${e.message}", e)
        }
    }

    /**
     * 发送命令到设备
     * 
     * @param command 要发送的设备命令
     * @throws CommandException 发送命令失败时抛出
     * @throws IllegalStateException 设备未连接时抛出
     */
    override suspend fun sendCommand(command: DeviceCommand) {
        if (!::connection.isInitialized) {
            _status.value = DeviceStatus.DISCONNECTED
            return
        }
        if(status.value.isConnected.not())return
        try {
            if (_status.value != DeviceStatus.CONNECTED) {
                throw IllegalStateException("设备未连接")
            }
            
            // 序列化命令
            val message = protocol.serialize(command)
            // 加密数据
            val encryptedMessage = protocol.encrypt(message)
            // 发送命令
            connection.send(encryptedMessage)
        } catch (e: Exception) {
            throw CommandException("发送命令失败: ${e.message}", e)
        }
    }

    /**
     * 获取设备当前状态
     * 
     * @return 包含设备状态信息的Map
     */
    fun getStatus(): Map<String, Any> {
        if(status.value.isConnected.not())return emptyMap()
        return statusManager.getCurrentStatus()
    }

    /**
     * 设置状态更新回调
     * 
     * @param callback 状态更新时调用的回调函数
     */
    fun onStatusUpdate(callback: (Map<String, Any>) -> Unit) {

        statusManager.setStatusCallback(callback)
    }

    // ===== 运动控制功能 =====
    
    /**
     * 控制机器人移动
     * 
     * @param speed 移动速度(0-100)
     * @param steps 移动步数
     * @param direction 移动方向("forward", "backward", "left", "right")
     * @param wave 是否使用波浪步态
     */
    suspend fun move(
        @IntRange(from = 0, to = 100)
        speed: Int,
        steps: Int,
        direction: String,
        wave: Boolean = true
    ) {
        if(status.value.isConnected.not())return
        when (direction) {
            "forward" -> locomotionService.move(speedVertical = speed, steps = steps, wave = wave)
            "backward" -> locomotionService.move(speedVertical = -speed, steps = steps, wave = wave)
            "left" -> locomotionService.move(speedHorizontal = speed, steps = steps, wave = wave)
            "right" -> locomotionService.move(speedHorizontal = -speed, steps = steps, wave = wave)
        }
    }

    /**
     * 停止机器人移动
     */
    suspend fun stop() {
        if(status.value.isConnected.not())return
        locomotionService.stop()
    }

    // ===== 语音功能 =====
    
    /**
     * 文本转语音
     * 
     * @param text 要转换为语音的文本
     */
    suspend fun speak(text: String) {
        if(status.value.isConnected.not())return
        try {
            val state = speechService.getVoiceTtsState()
            println("state:[${state}]")
            speechService.createLoop()

            if(speechService.startVoiceTts(text)){
                if(speechService.syncDoVoiceTts(text)){
                    speechService.stopVoiceTts()
                }
            }else{
                println("语音合成失败")
            }
        }finally {
//            PyGILState_Release(gstate)
        }
    }

    /**
     * 停止语音播放
     */
    suspend fun stopSpeak() {
        if(status.value.isConnected.not())return
        speechService.stopVoiceTts()
    }

    // ===== 舵机控制功能 =====
    
    /**
     * 移动单个舵机
     * 
     * @param servo 舵机对象
     */
    suspend fun moveJoint(servo: Servo) {
        if(status.value.isConnected.not())return
        servoService.setServoAngle(*arrayOf(servo))
    }

    /**
     * 移动单个舵机
     * 
     * @param jointId 舵机ID
     * @param angle 目标角度
     */
    suspend fun moveJoint(jointId: ServoName, angle: Int) {
        moveJoints(jointId to angle)
    }

    /**
     * 移动多个舵机
     * 
     * @param servos 舵机ID和角度的键值对
     */
    suspend fun moveJoints(vararg servos: Pair<ServoName, Int>) {
        if(status.value.isConnected.not())return
        servoService.setServoAngle(*servos.map { (jointId: ServoName, angle: Int) ->
            Servo(
                jointId,
                angel = angle
            )
        }.toTypedArray())
    }

    // ===== 视觉功能 =====
    
    /**
     * 异步人脸识别
     * 
     * @param type 人脸识别类型
     * @return 操作是否成功
     */
    suspend fun startFaceDetection(type: VisionFaceRecognitionType): Boolean {
        if(status.value.isConnected.not())return false
        return visionService.doFaceRecognitionValue(type)
    }

    /**
     * 同步人脸识别
     * 
     * @param type 人脸识别类型
     * @return 识别结果
     */
    suspend fun faceDetection(type: VisionFaceRecognitionType): String? {
        if(status.value.isConnected.not())return null
        return visionService.syncDoFaceRecognitionValue(type)
    }

    /**
     * 获取手势识别结果
     * 
     * @param option 视觉选项
     * @return 识别结果
     */
    suspend fun handDetection(option: VisionOption): Map<String, Any>? {
        if(status.value.isConnected.not())return null
        return visionService.getVisualTaskResult(option)
    }

    /**
     * 停止人脸识别
     * 
     * @param type 人脸识别类型
     */
    suspend fun stopFaceDetection(type: VisionFaceRecognitionType) {
        if(status.value.isConnected.not())return
        visionService.stopFaceRecognition(type)
    }

    /**
     * 设置舵机模式
     * 
     * @param jointId 舵机ID
     * @param mode 舵机模式
     */
    suspend fun setJointMode(jointId: ServoName, mode: ServoMode) {
        if(status.value.isConnected.not())return
        servoService.setServosMode(listOf(jointId), mode)
    }

    /**
     * 设置多个舵机模式
     * 
     * @param jointId 舵机ID列表
     * @param mode 舵机模式
     */
    suspend fun setJointModes(jointId: List<ServoName>, mode: ServoMode) {
        if(status.value.isConnected.not())return
        servoService.setServosMode(jointId, mode)
    }

    // ===== 传感器功能 =====
    
    /**
     * 获取传感器数据
     * 
     * @param type 传感器类型
     * @return 传感器数据
     */
    suspend fun getSensorData(type: String): Map<String, Any> {
        if(status.value.isConnected.not())return emptyMap()
        return sensorService.getSensorData(type)
    }

    // ===== 电源管理功能 =====
    
    /**
     * 获取电池电量
     * 
     * @return 电池电量百分比(0-100)
     */
    suspend fun getBatteryLevel(): Int {
        if(status.value.isConnected.not())return 0
        return powerService.getBatteryLevel()
    }

    /**
     * 检查设备是否正在充电
     * 
     * @return 是否正在充电
     */
    suspend fun isCharging(): Boolean {
        if(status.value.isConnected.not())return false
        return powerService.isCharging()
    }

    /**
     * 获取设备版本信息
     * 
     * @param type 版本类型
     * @return 版本信息字符串
     */
    suspend fun version(type: VersionType): String? {
        return diagnosisService.getRobotVersionInfo(type)
    }

    // ===== 灯光控制功能 =====
    
    /**
     * 设置设备灯光
     * 
     * @param type 灯光类型
     * @param color 灯光颜色
     */
    suspend fun setLight(type: LightType, color: LightColor) {
        lightService.setLight(type, color)
    }

    /**
     * 关闭所有灯光
     */
    suspend fun turnOffLight() {
        lightService.turnOff()
    }
}

/**
 * 连接异常
 * 
 * 当设备连接过程中发生错误时抛出
 */
class ConnectionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 命令异常
 * 
 * 当发送命令过程中发生错误时抛出
 */
class CommandException(message: String, cause: Throwable? = null) : Exception(message, cause)
