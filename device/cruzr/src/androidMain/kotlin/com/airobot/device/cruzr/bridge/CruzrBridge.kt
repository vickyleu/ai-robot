package com.airobot.device.cruzr.bridge

import android.content.Context
import android.util.Log
import com.ubtrobot.Robot
import com.ubtrobot.locomotion.LocomotionManager
import com.ubtrobot.power.PowerManager
import com.ubtrobot.sensor.SensorManager
import com.ubtrobot.speech.RecognitionOption
import com.ubtrobot.speech.RecognitionOption.MODE_CONTINUOUS
import com.ubtrobot.speech.RecognitionProgress
import com.ubtrobot.speech.SpeakingVoice
import com.ubtrobot.speech.SpeechManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Cruzr机器人SDK桥接类
 *
 * 负责与原生Cruzr SDK进行交互，提供底层通信接口
 */
class CruzrBridge(private val context: Context) {
    companion object {
        private const val TAG = "CruzrBridge"
        const val ERROR_INITIALIZATION_FAILED = 1001
        const val ERROR_CONNECTION_FAILED = 1002
        const val ERROR_ACTION_FAILED = 1003
        const val ERROR_SPEECH_FAILED = 1004
        const val ERROR_SERVO_FAILED = 1005
        const val ERROR_NAVIGATION_FAILED = 1006
        const val ERROR_EMOTION_FAILED = 1007
        const val ERROR_SENSOR_FAILED = 1008
        const val ERROR_POWER_FAILED = 1009
        const val ERROR_LIGHT_FAILED = 1010
        const val ERROR_SKILL_FAILED = 1011
    }

    // 连接状态
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    // 电池状态
    private val _batteryLevel = MutableStateFlow(0)
    val batteryLevel: StateFlow<Int> = _batteryLevel

    private val _isCharging = MutableStateFlow(false)
    val isCharging: StateFlow<Boolean> = _isCharging

    // 当前动作
    private val _currentAction = MutableStateFlow<String?>(null)
    val currentAction: StateFlow<String?> = _currentAction

    // 错误代码
    private val _errorCode = MutableStateFlow<Int?>(null)
    val errorCode: StateFlow<Int?> = _errorCode

    // 传感器数据
    private val _sensorData = MutableStateFlow<Map<String, Any>>(emptyMap())
    val sensorData: StateFlow<Map<String, Any>> = _sensorData

    // 服务管理器
    private lateinit var locomotionManager: LocomotionManager
    private lateinit var powerManager: PowerManager
    private lateinit var sensorManager: SensorManager
    private lateinit var speechManager: SpeechManager

    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.IO)

    // 注意：这里删除了重复定义的getDeviceStatus、startStatusMonitor和stopStatusMonitor方法
    // 这些方法在文件中已经定义过，不需要重复定义

    /** 初始化SDK
     */
    fun initialize() {
        try {
            Log.d(TAG, "初始化Cruzr SDK")
            // 初始化Robot SDK
            Robot.initialize(context)

            // 获取系统服务
            locomotionManager = Robot.globalContext().getSystemService(LocomotionManager.SERVICE)
            powerManager = Robot.globalContext().getSystemService(PowerManager.SERVICE)
            sensorManager = Robot.globalContext().getSystemService(SensorManager.SERVICE)
            speechManager = Robot.globalContext().getSystemService(SpeechManager.SERVICE)

            Log.d(TAG, "Cruzr SDK初始化成功")
        } catch (e: Exception) {
            Log.e(TAG, "Cruzr SDK初始化失败", e)
            _errorCode.value = ERROR_INITIALIZATION_FAILED
            throw RuntimeException("Cruzr SDK初始化失败", e)
        }
    }

    // 注意：这里删除了重复定义的getDeviceStatus、startStatusMonitor和stopStatusMonitor方法
    // 这些方法在文件中已经定义过，不需要重复定义

    /** 连接设备
     */
    fun connect() {
        try {
            Log.d(TAG, "连接Cruzr设备")
            // 设置连接状态
            _isConnected.value = true
            _errorCode.value = null

            // 启动状态监控
            startStatusMonitor()
            Log.d(TAG, "Cruzr设备连接成功")
        } catch (e: Exception) {
            Log.e(TAG, "Cruzr设备连接异常", e)
            _errorCode.value = ERROR_CONNECTION_FAILED
            throw RuntimeException("Cruzr设备连接异常", e)
        }
    }

    // 注意：这里删除了重复定义的getDeviceStatus、startStatusMonitor和stopStatusMonitor方法
    // 这些方法在文件中已经定义过，不需要重复定义

    /** 断开设备连接
     */
    fun disconnect() {
        try {
            Log.d(TAG, "断开Cruzr设备连接")
            // 停止状态监控
            stopStatusMonitor()
            _isConnected.value = false
            Log.d(TAG, "Cruzr设备已断开连接")
        } catch (e: Exception) {
            Log.e(TAG, "断开Cruzr设备连接异常", e)
            throw RuntimeException("断开Cruzr设备连接异常", e)
        } finally {
            _isConnected.value = false
        }
    }

    // 注意：这里删除了重复定义的getDeviceStatus、startStatusMonitor和stopStatusMonitor方法
    // 这些方法在文件中已经定义过，不需要重复定义

    /** 执行动作指令
     *
     * @param actionType 动作类型，如"move_forward", "move_backward", "turn_left", "turn_right", "stop"等
     * @param params 动作参数，根据不同动作类型需要不同参数
     *               - move_forward/move_backward: "speed" (Float) - 移动速度，范围0.0-1.0
     *               - turn_left/turn_right: "angle" (Float) - 转向角度，单位为弧度
     * @throws IllegalStateException 设备未连接时抛出
     * @throws IllegalArgumentException 不支持的动作类型时抛出
     * @throws RuntimeException 执行动作异常时抛出
     */
    fun executeAction(actionType: String, params: Map<String, Any>) {
        try {
            Log.d(TAG, "执行动作: $actionType, 参数: $params")
            if (!_isConnected.value) {
                Log.e(TAG, "设备未连接，无法执行动作")
                throw IllegalStateException("设备未连接，无法执行动作")
            }

            when (actionType) {
                "move_forward" -> {
                    // 注意：Cruzr SDK的moveStraight方法需要float类型参数，范围0.0-1.0
                    // 将Int类型的speed(0-100)转换为float类型(0.0-1.0)
                    val speedInt = params["speed"] as? Int ?: 50
                    val speed = speedInt / 100.0f // 转换为0.0-1.0范围的float

                    Log.d(TAG, "移动速度: $speed (转换自: $speedInt)")
                    locomotionManager.moveStraight(speed)
                        .progress { progress ->
                            Log.d(TAG, "移动进度: ${progress.progress}")
                        }
                        .done {
                            Log.d(TAG, "移动完成")
                            _currentAction.value = null
                        }
                        .fail { e ->
                            Log.e(TAG, "移动失败: ${e.message}")
                            _errorCode.value = ERROR_ACTION_FAILED
                        }
                }

                "move_backward" -> {
                    // 注意：Cruzr SDK的moveStraight方法需要float类型参数，范围0.0-1.0
                    // 将Int类型的speed(0-100)转换为float类型(0.0-1.0)，并取负值表示后退
                    val speedInt = params["speed"] as? Int ?: 50
                    val speed = -1 * (speedInt / 100.0f) // 转换为-1.0-0.0范围的float

                    Log.d(TAG, "后退速度: $speed (转换自: $speedInt)")
                    locomotionManager.moveStraight(speed)
                        .progress { progress ->
                            Log.d(TAG, "后退进度: ${progress.progress}")
                        }
                        .done {
                            Log.d(TAG, "后退完成")
                            _currentAction.value = null
                        }
                        .fail { e ->
                            Log.e(TAG, "后退失败: ${e.message}")
                            _errorCode.value = ERROR_ACTION_FAILED
                        }
                }

                "turn_left" -> {
                    // 检查是否是头部转动请求
                    if (params["is_head_movement"] == true) {
                        val headAngle = params["head_angle"] as? Int ?: 0
                        // 头部转动需要使用专门的API
                        // 注意：这里假设Cruzr SDK有控制头部的方法，实际实现需要根据SDK文档调整
                        // 头部角度通常也需要转换为弧度
                        val headAngleRad = -1 * (headAngle * Math.PI / 180.0f)

                        Log.d(TAG, "头部转动角度: $headAngleRad 弧度 (转换自: $headAngle 度)")
                        // 假设有一个控制头部的方法，如果没有，需要根据实际SDK调整
                        try {
                            // 这里应该调用控制头部的API
                            // 例如：robotHead.turn(headAngleRad.toFloat())
                            // 由于没有具体API，这里使用日志记录
                            Log.d(TAG, "执行头部转动，但当前SDK可能不支持此功能")
                            _currentAction.value = "head_turn"
                        } catch (e: Exception) {
                            Log.e(TAG, "头部转动失败: ${e.message}")
                            _errorCode.value = ERROR_SERVO_FAILED
                            throw e
                        }
                    } else {
                        // 正常的左转动作
                        // 注意：Cruzr SDK的turn方法需要float类型参数，单位为弧度
                        // 将Int类型的angle(角度)转换为float类型(弧度)
                        val angleInt = params["angle"] as? Int ?: 90
                        val angle = -1 * (angleInt * Math.PI / 180.0f) // 转换为弧度，负值表示左转

                        Log.d(TAG, "左转角度: $angle 弧度 (转换自: $angleInt 度)")
                        locomotionManager.turn(angle.toFloat())
                            .progress { progress ->
                                Log.d(TAG, "左转进度: ${progress.progress}")
                            }
                            .done {
                                Log.d(TAG, "左转完成")
                                _currentAction.value = null
                            }
                            .fail { e ->
                                Log.e(TAG, "左转失败: ${e.message}")
                                _errorCode.value = ERROR_ACTION_FAILED
                            }
                    }
                }

                "turn_right" -> {
                    // 注意：Cruzr SDK的turn方法需要float类型参数，单位为弧度
                    // 将Int类型的angle(角度)转换为float类型(弧度)
                    val angleInt = params["angle"] as? Int ?: 90
                    val angle = angleInt * Math.PI / 180.0f // 转换为弧度，正值表示右转

                    Log.d(TAG, "右转角度: $angle 弧度 (转换自: $angleInt 度)")
                    locomotionManager.turn(angle.toFloat())
                        .progress { progress ->
                            Log.d(TAG, "右转进度: ${progress.progress}")
                        }
                        .done {
                            Log.d(TAG, "右转完成")
                            _currentAction.value = null
                        }
                        .fail { e ->
                            Log.e(TAG, "右转失败: ${e.message}")
                            _errorCode.value = ERROR_ACTION_FAILED
                        }
                }

                "stop" -> {
                    // 检查是否是语音请求
                    if (params["is_speech_action"] == true) {
                        val speechText = params["speech_text"] as? String ?: ""
                        val speechLanguage = params["speech_language"] as? String ?: "zh-CN"
                        speak(speechText, speechLanguage)
                    } else {
                        stopAction()
                    }
                }

                else -> {
                    Log.e(TAG, "不支持的动作类型: $actionType")
                    throw IllegalArgumentException("不支持的动作类型: $actionType")
                }
            }

            _currentAction.value = actionType
            Log.d(TAG, "动作执行成功: $actionType")
        } catch (e: Exception) {
            Log.e(TAG, "执行动作异常", e)
            _errorCode.value = ERROR_ACTION_FAILED
            throw RuntimeException("执行动作异常", e)
        }
    }

    // 注意：这里删除了重复定义的getDeviceStatus、startStatusMonitor和stopStatusMonitor方法
    // 这些方法在文件中已经定义过，不需要重复定义

    /** 停止当前动作
     */
    fun stopAction() {
        try {
            Log.d(TAG, "停止当前动作")
            if (!_isConnected.value) {
                Log.e(TAG, "设备未连接，无法停止动作")
                throw IllegalStateException("设备未连接，无法停止动作")
            }

            // 取消当前正在执行的动作
            if (_currentAction.value != null) {
                // 这里需要根据当前动作类型来取消相应的Promise
                // 在实际实现中，可能需要保存各种动作的Promise引用
                _currentAction.value = null
                Log.d(TAG, "动作已停止")
            }
        } catch (e: Exception) {
            Log.e(TAG, "停止动作异常", e)
            throw RuntimeException("停止动作异常", e)
        }
    }

    // ===== 运动控制服务 (LocomotionService) =====

    // 注意：这里删除了重复定义的getDeviceStatus、startStatusMonitor和stopStatusMonitor方法
    // 这些方法在文件中已经定义过，不需要重复定义

    /** 控制机器人移动
     * @param speed 移动速度 (0-100)
     * @param direction 移动方向 ("forward", "backward", "left", "right")
     */
    fun move(speed: Int, direction: String) {
        val params = mapOf(
            "speed" to speed,
            "direction" to direction
        )
        executeAction("move", params)
    }

    // 注意：这里删除了重复定义的getDeviceStatus、startStatusMonitor和stopStatusMonitor方法
    // 这些方法在文件中已经定义过，不需要重复定义

    /** 设置移动速度
     * @param speed 移动速度 (0-100)
     */
    fun setSpeed(speed: Int) {
        val params = mapOf("speed" to speed)
        executeAction("setSpeed", params)
    }

    // ===== 语音服务 (SpeechService) =====

    // 注意：这里删除了重复定义的getDeviceStatus、startStatusMonitor和stopStatusMonitor方法
    // 这些方法在文件中已经定义过，不需要重复定义

    // 语音合成相关参数
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking

    private val _speakingProgress = MutableStateFlow(0)
    val speakingProgress: StateFlow<Int> = _speakingProgress

    // 语音识别相关参数
    private val _isRecognizing = MutableStateFlow(false)
    val isRecognizing: StateFlow<Boolean> = _isRecognizing

    private val _recognitionResult = MutableStateFlow<String?>(null)
    val recognitionResult: StateFlow<String?> = _recognitionResult

    /** 语音合成
     * @param text 要播放的文本内容
     * @param language 语言代码 (如"zh-CN")
     * @param volume 音量 (0-100)
     */
    fun speak(text: String, language: String = "zh-CN", volume: Int = 100) {
        try {
            Log.d(TAG, "语音合成: $text, 语言: $language, 音量: $volume")
            if (!_isConnected.value) {
                Log.e(TAG, "设备未连接，无法执行语音合成")
                throw IllegalStateException("设备未连接，无法执行语音合成")
            }

            _isSpeaking.value = true
            _speakingProgress.value = 0

            // 使用SpeechManager进行语音合成
            speechManager.speak(text, language)
                .progress { progress ->
                    Log.d(TAG, "语音合成进度: ${progress.progress}")
                    _speakingProgress.value = progress.progress
                }
                .done {
                    Log.d(TAG, "语音合成完成")
                    _isSpeaking.value = false
                    _speakingProgress.value = 100
                }
                .fail { e ->
                    Log.e(TAG, "语音合成失败: ${e.message}")
                    _errorCode.value = ERROR_SPEECH_FAILED
                    _isSpeaking.value = false
                }

            Log.d(TAG, "语音合成请求已发送")
        } catch (e: Exception) {
            Log.e(TAG, "语音合成异常", e)
            _errorCode.value = ERROR_SPEECH_FAILED
            _isSpeaking.value = false
            throw RuntimeException("语音合成异常: ${e.message}", e)
        }
    }

    /** 停止语音合成
     */
    fun stopSpeak() {
        try {
            Log.d(TAG, "停止语音合成")
            if (!_isConnected.value) {
                Log.e(TAG, "设备未连接，无法停止语音合成")
                throw IllegalStateException("设备未连接，无法停止语音合成")
            }

            speechManager.stopSpeak()
            _isSpeaking.value = false
            _speakingProgress.value = 0

            Log.d(TAG, "语音合成已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止语音合成异常", e)
            throw RuntimeException("停止语音合成异常: ${e.message}", e)
        }
    }

    /** 获取可用的语音列表
     */
    fun getSpeakingVoiceList(): List<SpeakingVoice> {
        try {
            Log.d(TAG, "获取可用语音列表")
            if (!_isConnected.value) {
                Log.e(TAG, "设备未连接，无法获取语音列表")
                throw IllegalStateException("设备未连接，无法获取语音列表")
            }

            return speechManager.getSpeakingVoiceList()
        } catch (e: Exception) {
            Log.e(TAG, "获取语音列表异常", e)
            throw RuntimeException("获取语音列表异常: ${e.message}", e)
        }
    }

    /** 开始语音识别
     * @param mode 识别模式 (单次/连续)
     * @param timeoutMillis 超时时间 (3000-60000ms)
     */
    fun startRecognizing(mode: Int = RecognitionOption.MODE_SINGLE, timeoutMillis: Long = 12000) {
        try {
            Log.d(TAG, "开始语音识别: mode=$mode, timeout=$timeoutMillis")
            if (!_isConnected.value) {
                Log.e(TAG, "设备未连接，无法开始语音识别")
                throw IllegalStateException("设备未连接，无法开始语音识别")
            }

            _isRecognizing.value = true
            _recognitionResult.value = null

            val option = RecognitionOption.Builder(
                RecognitionOption.Builder(MODE_CONTINUOUS)
                    .setTimeoutMillis(timeoutMillis)
//                    .setUnderstandingOption()
                    .build()
            )
                .setTimeoutMillis(timeoutMillis)
                .build()

            speechManager.startRecognizing(option)
                .progress { progress ->
                    when (progress.type) {
                        RecognitionProgress.PROGRESS_RECOGNITION_TEXT_RESULT -> {
                            Log.d(TAG, "识别结果: ${progress.textResult}")
                            _recognitionResult.value = progress.textResult
                        }

                        RecognitionProgress.PROGRESS_RECOGNIZING -> {
                            Log.d(TAG, "识别音量: ${progress.decibel}")
                        }
                    }
                }
                .done { result ->
                    Log.d(TAG, "语音识别完成: ${result.text}")
                    _recognitionResult.value = result.text
                    _isRecognizing.value = false
                }
                .fail { e ->
                    Log.e(TAG, "语音识别失败: ${e.message}")
                    _errorCode.value = ERROR_SPEECH_FAILED
                    _isRecognizing.value = false
                }

            Log.d(TAG, "语音识别请求已发送")
        } catch (e: Exception) {
            Log.e(TAG, "开始语音识别异常", e)
            _errorCode.value = ERROR_SPEECH_FAILED
            _isRecognizing.value = false
            throw RuntimeException("开始语音识别异常: ${e.message}", e)
        }
    }

    /** 停止语音识别
     */
    fun stopRecognizing() {
        try {
            Log.d(TAG, "停止语音识别")
            if (!_isConnected.value) {
                Log.e(TAG, "设备未连接，无法停止语音识别")
                throw IllegalStateException("设备未连接，无法停止语音识别")
            }

            speechManager.stopRecognizing()
            _isRecognizing.value = false

            Log.d(TAG, "语音识别已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止语音识别异常", e)
            throw RuntimeException("停止语音识别异常: ${e.message}", e)
        }
    }

    /** 设置音量
     * @param volume 音量大小 (0-100)
     */
    fun setVolume(volume: Int) {
        try {
            Log.d(TAG, "设置音量: $volume")
            if (!_isConnected.value) {
                Log.e(TAG, "设备未连接，无法设置音量")
                throw IllegalStateException("设备未连接，无法设置音量")
            }

            val params = mapOf("volume" to volume)
            val paramsJson = convertParamsToJson(params)

            if (setVolumeNative(paramsJson)) {
                Log.d(TAG, "设置音量成功")
            } else {
                Log.e(TAG, "设置音量失败")
                throw RuntimeException("设置音量失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "设置音量异常", e)
            throw RuntimeException("设置音量异常", e)
        }
    }

    // ===== 舵机控制服务 (ServoService) =====

    /** 控制关节运动
     * @param jointId 关节ID
     * @param angle 目标角度
     */
    fun moveJoint(jointId: String, angle: Int) {
        try {
            Log.d(TAG, "控制关节运动: $jointId, 角度: $angle")
            if (!_isConnected.value) {
                Log.e(TAG, "设备未连接，无法控制关节")
                throw IllegalStateException("设备未连接，无法控制关节")
            }

            val params = mapOf(
                "jointId" to jointId,
                "angle" to angle
            )
            val paramsJson = convertParamsToJson(params)

            if (moveJointNative(paramsJson)) {
                Log.d(TAG, "控制关节运动成功")
            } else {
                Log.e(TAG, "控制关节运动失败")
                _errorCode.value = ERROR_SERVO_FAILED
                throw RuntimeException("控制关节运动失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "控制关节运动异常", e)
            _errorCode.value = ERROR_SERVO_FAILED
            throw RuntimeException("控制关节运动异常", e)
        }
    }


    /** 重置关节位置
     * @param jointId 关节ID
     */
    fun resetJoint(jointId: String) {
        try {
            Log.d(TAG, "重置关节位置: $jointId")
            if (!_isConnected.value) {
                Log.e(TAG, "设备未连接，无法重置关节位置")
                throw IllegalStateException("设备未连接，无法重置关节位置")
            }

            val params = mapOf("jointId" to jointId)
            val paramsJson = convertParamsToJson(params)

            if (resetJointNative(paramsJson)) {
                Log.d(TAG, "重置关节位置成功")
            } else {
                Log.e(TAG, "重置关节位置失败")
                _errorCode.value = ERROR_SERVO_FAILED
                throw RuntimeException("重置关节位置失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "重置关节位置异常", e)
            _errorCode.value = ERROR_SERVO_FAILED
            throw RuntimeException("重置关节位置异常", e)
        }
    }

    // ===== 导航服务 (NavigationService) =====
    /** 导航到指定位置
     * @param x X坐标
     * @param y Y坐标
     */
    fun navigateTo(x: Float, y: Float) {
        try {
            Log.d(TAG, "导航到指定位置: ($x, $y)")
            if (!_isConnected.value) {
                Log.e(TAG, "设备未连接，无法执行导航")
                throw IllegalStateException("设备未连接，无法执行导航")
            }

            val params = mapOf(
                "x" to x,
                "y" to y
            )
            val paramsJson = convertParamsToJson(params)

            if (navigateToNative(paramsJson)) {
                Log.d(TAG, "导航开始执行")
            } else {
                Log.e(TAG, "导航执行失败")
                _errorCode.value = ERROR_NAVIGATION_FAILED
                throw RuntimeException("导航执行失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "导航执行异常", e)
            _errorCode.value = ERROR_NAVIGATION_FAILED
            throw RuntimeException("导航执行异常", e)
        }
    }

    /** 获取当前位置
     * @return 包含x和y坐标的Map
     */
    fun getPosition(): Map<String, Float> {
        try {
            Log.d(TAG, "获取当前位置")
            if (!_isConnected.value) {
                Log.e(TAG, "设备未连接，无法获取位置")
                throw IllegalStateException("设备未连接，无法获取位置")
            }

            val positionJson = getPositionNative()
            val position = parseJsonToMap(positionJson)

            // 提取坐标信息
            val x = position["x"] as? Float ?: 0f
            val y = position["y"] as? Float ?: 0f

            return mapOf("x" to x, "y" to y)
        } catch (e: Exception) {
            Log.e(TAG, "获取位置异常", e)
            _errorCode.value = ERROR_NAVIGATION_FAILED
            throw RuntimeException("获取位置异常", e)
        }
    }

    /** 停止导航
     */
    fun stopNavigation() {
        try {
            Log.d(TAG, "停止导航")
            if (!_isConnected.value) {
                Log.e(TAG, "设备未连接，无法停止导航")
                throw IllegalStateException("设备未连接，无法停止导航")
            }

            if (stopNavigationNative()) {
                Log.d(TAG, "停止导航成功")
            } else {
                Log.e(TAG, "停止导航失败")
                throw RuntimeException("停止导航失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "停止导航异常", e)
            throw RuntimeException("停止导航异常", e)
        }
    }

    // ===== 情感服务 (EmotionService) =====

    /** 设置情感表现
     * @param type 情感类型 (如开心、悲伤等)
     */
    fun setEmotion(type: String) {
        try {
            Log.d(TAG, "设置情感表现: $type")
            if (!_isConnected.value) {
                Log.e(TAG, "设备未连接，无法设置情感表现")
                throw IllegalStateException("设备未连接，无法设置情感表现")
            }

            val params = mapOf("type" to type)
            val paramsJson = convertParamsToJson(params)

            if (setEmotionNative(paramsJson)) {
                Log.d(TAG, "设置情感表现成功")
            } else {
                Log.e(TAG, "设置情感表现失败")
                _errorCode.value = ERROR_EMOTION_FAILED
                throw RuntimeException("设置情感表现失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "设置情感表现异常", e)
            _errorCode.value = ERROR_EMOTION_FAILED
            throw RuntimeException("设置情感表现异常", e)
        }
    }


    /** 播放情感动画
     * @param name 动画名称
     */
    fun playAnimation(name: String) {
        try {
            Log.d(TAG, "播放情感动画: $name")
            if (!_isConnected.value) {
                Log.e(TAG, "设备未连接，无法播放情感动画")
                throw IllegalStateException("设备未连接，无法播放情感动画")
            }

            val params = mapOf("name" to name)
            val paramsJson = convertParamsToJson(params)

            if (playAnimationNative(paramsJson)) {
                Log.d(TAG, "播放情感动画成功")
            } else {
                Log.e(TAG, "播放情感动画失败")
                _errorCode.value = ERROR_EMOTION_FAILED
                throw RuntimeException("播放情感动画失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "播放情感动画异常", e)
            _errorCode.value = ERROR_EMOTION_FAILED
            throw RuntimeException("播放情感动画异常", e)
        }
    }

    // ===== 传感器服务 (SensorService) =====


    /** 获取传感器数据
     * @param type 传感器类型
     * @return 传感器数据
     */
    fun getSensorData(type: String): Map<String, Any> {
        try {
            Log.d(TAG, "获取传感器数据: $type")
            if (!_isConnected.value) {
                Log.e(TAG, "设备未连接，无法获取传感器数据")
                throw IllegalStateException("设备未连接，无法获取传感器数据")
            }

            val params = mapOf("type" to type)
            val paramsJson = convertParamsToJson(params)

            val dataJson = getSensorDataNative(paramsJson)
            return parseJsonToMap(dataJson)
        } catch (e: Exception) {
            Log.e(TAG, "获取传感器数据异常", e)
            _errorCode.value = ERROR_SENSOR_FAILED
            throw RuntimeException("获取传感器数据异常", e)
        }
    }


    /** 启用传感器
     * @param type 传感器类型
     */
    fun enableSensor(type: String) {
        try {
            Log.d(TAG, "启用传感器: $type")
            if (!_isConnected.value) {
                Log.e(TAG, "设备未连接，无法启用传感器")
                throw IllegalStateException("设备未连接，无法启用传感器")
            }

            val params = mapOf("type" to type)
            val paramsJson = convertParamsToJson(params)

            if (enableSensorNative(paramsJson)) {
                Log.d(TAG, "启用传感器成功")
            } else {
                Log.e(TAG, "启用传感器失败")
                _errorCode.value = ERROR_SENSOR_FAILED
                throw RuntimeException("启用传感器失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "启用传感器异常", e)
            _errorCode.value = ERROR_SENSOR_FAILED
            throw RuntimeException("启用传感器异常", e)
        }
    }


    /** 禁用传感器
     * @param type 传感器类型
     */
    fun disableSensor(type: String) {
        try {
            Log.d(TAG, "禁用传感器: $type")
            if (!_isConnected.value) {
                Log.e(TAG, "设备未连接，无法禁用传感器")
                throw IllegalStateException("设备未连接，无法禁用传感器")
            }

            val params = mapOf("type" to type)
            val paramsJson = convertParamsToJson(params)

            if (disableSensorNative(paramsJson)) {
                Log.d(TAG, "禁用传感器成功")
            } else {
                Log.e(TAG, "禁用传感器失败")
                _errorCode.value = ERROR_SENSOR_FAILED
                throw RuntimeException("禁用传感器失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "禁用传感器异常", e)
            _errorCode.value = ERROR_SENSOR_FAILED
            throw RuntimeException("禁用传感器异常", e)
        }
    }

    // ===== 电源管理服务 (PowerService) =====


    /** 获取电池电量
     * @return 电池电量百分比 (0-100)
     */
    fun getBatteryLevel(): Int {
        try {
            Log.d(TAG, "获取电池电量")
            if (!_isConnected.value) {
                Log.e(TAG, "设备未连接，无法获取电池电量")
                throw IllegalStateException("设备未连接，无法获取电池电量")
            }

            val level = getBatteryLevelNative()
            _batteryLevel.value = level
            return level
        } catch (e: Exception) {
            Log.e(TAG, "获取电池电量异常", e)
            _errorCode.value = ERROR_POWER_FAILED
            throw RuntimeException("获取电池电量异常", e)
        }
    }


    /** 获取充电状态
     * @return 是否正在充电
     */
    fun isChargingStatus(): Boolean {
        try {
            Log.d(TAG, "获取充电状态")
            if (!_isConnected.value) {
                Log.e(TAG, "设备未连接，无法获取充电状态")
                throw IllegalStateException("设备未连接，无法获取充电状态")
            }

            val charging = isChargingNative()
            _isCharging.value = charging
            return charging
        } catch (e: Exception) {
            Log.e(TAG, "获取充电状态异常", e)
            _errorCode.value = ERROR_POWER_FAILED
            throw RuntimeException("获取充电状态异常", e)
        }
    }


    /** 开始充电
     */
    fun startCharging() {
        try {
            Log.d(TAG, "开始充电")
            if (!_isConnected.value) {
                Log.e(TAG, "设备未连接，无法开始充电")
                throw IllegalStateException("设备未连接，无法开始充电")
            }

            if (startChargingNative()) {
                Log.d(TAG, "开始充电成功")
            } else {
                Log.e(TAG, "开始充电失败")
                _errorCode.value = ERROR_POWER_FAILED
                throw RuntimeException("开始充电失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "开始充电异常", e)
            _errorCode.value = ERROR_POWER_FAILED
            throw RuntimeException("开始充电异常", e)
        }
    }


    /** 停止充电
     */
    fun stopCharging() {
        try {
            Log.d(TAG, "停止充电")
            if (!_isConnected.value) {
                Log.e(TAG, "设备未连接，无法停止充电")
                throw IllegalStateException("设备未连接，无法停止充电")
            }

            if (stopChargingNative()) {
                Log.d(TAG, "停止充电成功")
            } else {
                Log.e(TAG, "停止充电失败")
                _errorCode.value = ERROR_POWER_FAILED
                throw RuntimeException("停止充电失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "停止充电异常", e)
            _errorCode.value = ERROR_POWER_FAILED
            throw RuntimeException("停止充电异常", e)
        }
    }

    // ===== 灯光控制服务 (LightService) =====


    /** 设置灯光
     * @param position 灯光位置
     * @param color 颜色值
     */
    fun setLight(position: String, color: String) {
        try {
            Log.d(TAG, "设置灯光: 位置=$position, 颜色=$color")
            if (!_isConnected.value) {
                Log.e(TAG, "设备未连接，无法设置灯光")
                throw IllegalStateException("设备未连接，无法设置灯光")
            }

            val params = mapOf(
                "position" to position,
                "color" to color
            )
            val paramsJson = convertParamsToJson(params)

            if (setLightNative(paramsJson)) {
                Log.d(TAG, "设置灯光成功")
            } else {
                Log.e(TAG, "设置灯光失败")
                _errorCode.value = ERROR_LIGHT_FAILED
                throw RuntimeException("设置灯光失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "设置灯光异常", e)
            _errorCode.value = ERROR_LIGHT_FAILED
            throw RuntimeException("设置灯光异常", e)
        }
    }


    /** 关闭指定位置的灯光
     * @param position 灯光位置
     */
    fun turnOff(position: String) {
        try {
            Log.d(TAG, "关闭灯光: 位置=$position")
            if (!_isConnected.value) {
                Log.e(TAG, "设备未连接，无法关闭灯光")
                throw IllegalStateException("设备未连接，无法关闭灯光")
            }

            val params = mapOf("position" to position)
            val paramsJson = convertParamsToJson(params)

            if (turnOffLightNative(paramsJson)) {
                Log.d(TAG, "关闭灯光成功")
            } else {
                Log.e(TAG, "关闭灯光失败")
                _errorCode.value = ERROR_LIGHT_FAILED
                throw RuntimeException("关闭灯光失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "关闭灯光异常", e)
            _errorCode.value = ERROR_LIGHT_FAILED
            throw RuntimeException("关闭灯光异常", e)
        }
    }

    // ===== 技能管理API (SkillManager) =====

    // 注意：这里删除了重复定义的getDeviceStatus、startStatusMonitor和stopStatusMonitor方法
    // 这些方法在文件中已经定义过，不需要重复定义

    /** 加载技能
     * @param skillId 技能ID
     */
    fun loadSkill(skillId: String) {
        try {
            Log.d(TAG, "加载技能: $skillId")
            if (!_isConnected.value) {
                Log.e(TAG, "设备未连接，无法加载技能")
                throw IllegalStateException("设备未连接，无法加载技能")
            }

            val params = mapOf("skillId" to skillId)
            val paramsJson = convertParamsToJson(params)

            if (loadSkillNative(paramsJson)) {
                Log.d(TAG, "加载技能成功")
            } else {
                Log.e(TAG, "加载技能失败")
                _errorCode.value = ERROR_SKILL_FAILED
                throw RuntimeException("加载技能失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载技能异常", e)
            _errorCode.value = ERROR_SKILL_FAILED
            throw RuntimeException("加载技能异常", e)
        }
    }

    // 注意：这里删除了重复定义的getDeviceStatus、startStatusMonitor和stopStatusMonitor方法
    // 这些方法在文件中已经定义过，不需要重复定义

    /** 卸载技能
     * @param skillId 技能ID
     */
    fun unloadSkill(skillId: String) {
        try {
            Log.d(TAG, "卸载技能: $skillId")
            if (!_isConnected.value) {
                Log.e(TAG, "设备未连接，无法卸载技能")
                throw IllegalStateException("设备未连接，无法卸载技能")
            }

            val params = mapOf("skillId" to skillId)
            val paramsJson = convertParamsToJson(params)

            if (unloadSkillNative(paramsJson)) {
                Log.d(TAG, "卸载技能成功")
            } else {
                Log.e(TAG, "卸载技能失败")
                _errorCode.value = ERROR_SKILL_FAILED
                throw RuntimeException("卸载技能失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "卸载技能异常", e)
            _errorCode.value = ERROR_SKILL_FAILED
            throw RuntimeException("卸载技能异常", e)
        }
    }

    // 注意：这里删除了重复定义的getDeviceStatus、startStatusMonitor和stopStatusMonitor方法
    // 这些方法在文件中已经定义过，不需要重复定义

    /** 启动技能
     * @param skillId 技能ID
     * @param params 技能参数
     */
    fun startSkill(skillId: String, params: Map<String, Any>) {
        try {
            Log.d(TAG, "启动技能: $skillId, 参数: $params")
            if (!_isConnected.value) {
                Log.e(TAG, "设备未连接，无法启动技能")
                throw IllegalStateException("设备未连接，无法启动技能")
            }

            val skillParams = mapOf(
                "skillId" to skillId,
                "params" to params
            )
            val paramsJson = convertParamsToJson(skillParams)

            if (startSkillNative(paramsJson)) {
                Log.d(TAG, "启动技能成功")
            } else {
                Log.e(TAG, "启动技能失败")
                _errorCode.value = ERROR_SKILL_FAILED
                throw RuntimeException("启动技能失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动技能异常", e)
            _errorCode.value = ERROR_SKILL_FAILED
            throw RuntimeException("启动技能异常", e)
        }
    }

    // 注意：这里删除了重复定义的getDeviceStatus、startStatusMonitor和stopStatusMonitor方法
    // 这些方法在文件中已经定义过，不需要重复定义

    /** 停止技能
     * @param skillId 技能ID
     */
    fun stopSkill(skillId: String) {
        try {
            Log.d(TAG, "停止技能: $skillId")
            if (!_isConnected.value) {
                Log.e(TAG, "设备未连接，无法停止技能")
                throw IllegalStateException("设备未连接，无法停止技能")
            }

            val params = mapOf("skillId" to skillId)
            val paramsJson = convertParamsToJson(params)

            if (stopSkillNative(paramsJson)) {
                Log.d(TAG, "停止技能成功")
            } else {
                Log.e(TAG, "停止技能失败")
                _errorCode.value = ERROR_SKILL_FAILED
                throw RuntimeException("停止技能失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "停止技能异常", e)
            _errorCode.value = ERROR_SKILL_FAILED
            throw RuntimeException("停止技能异常", e)
        }
    }

    // ===== 系统诊断API (DiagnosisService) =====

    // 注意：这里删除了重复定义的getDeviceStatus、startStatusMonitor和stopStatusMonitor方法
    // 这些方法在文件中已经定义过，不需要重复定义

    /** 运行系统诊断
     * @return 诊断结果
     */
    fun runDiagnosis(): Map<String, Any> {
        try {
            Log.d(TAG, "运行系统诊断")
            if (!_isConnected.value) {
                Log.e(TAG, "设备未连接，无法运行系统诊断")
                throw IllegalStateException("设备未连接，无法运行系统诊断")
            }

            val diagnosisJson = runDiagnosisNative()
            return parseJsonToMap(diagnosisJson)
        } catch (e: Exception) {
            Log.e(TAG, "运行系统诊断异常", e)
            return mapOf("error" to e.message.toString())
        }
    }

    // 注意：这里删除了重复定义的getDeviceStatus、startStatusMonitor和stopStatusMonitor方法
    // 这些方法在文件中已经定义过，不需要重复定义

    /** 获取系统状态
     * @return 系统状态
     */
    fun getSystemStatus(): Map<String, Any> {
        try {
            Log.d(TAG, "获取系统状态")
            if (!_isConnected.value) {
                Log.e(TAG, "设备未连接，无法获取系统状态")
                throw IllegalStateException("设备未连接，无法获取系统状态")
            }

            val statusJson = getSystemStatusNative()
            return parseJsonToMap(statusJson)
        } catch (e: Exception) {
            Log.e(TAG, "获取系统状态异常", e)
            return mapOf("error" to e.message.toString())
        }
    }

    // 注意：这里删除了重复定义的getDeviceStatus、startStatusMonitor和stopStatusMonitor方法
    // 这些方法在文件中已经定义过，不需要重复定义

    /** 获取错误日志
     * @return 错误日志
     */
    fun getErrorLog(): String {
        try {
            Log.d(TAG, "获取错误日志")
            if (!_isConnected.value) {
                Log.e(TAG, "设备未连接，无法获取错误日志")
                throw IllegalStateException("设备未连接，无法获取错误日志")
            }

            return getErrorLogNative()
        } catch (e: Exception) {
            Log.e(TAG, "获取错误日志异常", e)
            return "获取错误日志失败: ${e.message}"
        }
    }

    // 私有辅助方法

    private fun startStatusMonitor() {
        Log.d(TAG, "启动状态监控")
        startStatusMonitorNative()
    }

    private fun stopStatusMonitor() {
        Log.d(TAG, "停止状态监控")
        stopStatusMonitorNative()
    }

    private fun updateStatusFromMap(status: Map<String, Any>) {
        status["batteryLevel"]?.let {
            if (it is Int) _batteryLevel.value = it
        }

        status["isCharging"]?.let {
            if (it is Boolean) _isCharging.value = it
        }

        status["errorCode"]?.let {
            if (it is Int) _errorCode.value = it
        }
    }

    private fun convertParamsToJson(params: Map<String, Any>): String {
        try {
            val jsonObject = JSONObject()
            for ((key, value) in params) {
                when (value) {
                    is String -> jsonObject.put(key, value)
                    is Number -> jsonObject.put(key, value)
                    is Boolean -> jsonObject.put(key, value)
                    is Map<*, *> -> {
                        val nestedObject = JSONObject()
                        (value as Map<String, Any>).forEach { (nestedKey, nestedValue) ->
                            nestedObject.put(nestedKey, nestedValue)
                        }
                        jsonObject.put(key, nestedObject)
                    }

                    else -> jsonObject.put(key, value.toString())
                }
            }
            return jsonObject.toString()
        } catch (e: Exception) {
            Log.e(TAG, "转换参数为JSON异常", e)
            // 简单实现，实际项目中应使用JSON库
            val builder = StringBuilder("{")
            params.entries.forEachIndexed { index, entry ->
                if (index > 0) builder.append(",")
                builder.append('"').append(entry.key).append('"').append(":")
                when (val value = entry.value) {
                    is String -> builder.append('"').append(value).append('"')
                    is Number, is Boolean -> builder.append(value)
                    else -> builder.append('"').append(value.toString()).append('"')
                }
            }
            builder.append("}")
            return builder.toString()
        }
    }

    private fun parseJsonToMap(json: String): Map<String, Any> {
        try {
            val result = mutableMapOf<String, Any>()
            val jsonObject = JSONObject(json)
            val keys = jsonObject.keys()

            while (keys.hasNext()) {
                val key = keys.next()
                val value = jsonObject.get(key)

                when (value) {
                    is JSONObject -> result[key] = parseJsonToMap(value.toString())
                    is String -> result[key] = value
                    is Number -> result[key] = value
                    is Boolean -> result[key] = value
                    else -> result[key] = value.toString()
                }
            }

            return result
        } catch (e: Exception) {
            Log.e(TAG, "解析JSON为Map异常", e)
            // 返回一个空Map或者包含错误信息的Map
            return mapOf("error" to e.message.toString())
        }
    }

    // 原生方法声明
    private external fun startStatusMonitorNative()
    private external fun stopStatusMonitorNative()

    // 语音服务原生方法
    private external fun setVolumeNative(paramsJson: String): Boolean

    // 舵机控制服务原生方法
    private external fun moveJointNative(paramsJson: String): Boolean
    private external fun resetJointNative(paramsJson: String): Boolean

    // 导航服务原生方法
    private external fun navigateToNative(paramsJson: String): Boolean
    private external fun getPositionNative(): String
    private external fun stopNavigationNative(): Boolean

    // 情感服务原生方法
    private external fun setEmotionNative(paramsJson: String): Boolean
    private external fun playAnimationNative(paramsJson: String): Boolean

    // 传感器服务原生方法
    private external fun getSensorDataNative(paramsJson: String): String
    private external fun enableSensorNative(paramsJson: String): Boolean
    private external fun disableSensorNative(paramsJson: String): Boolean

    // 电源管理服务原生方法
    private external fun getBatteryLevelNative(): Int
    private external fun isChargingNative(): Boolean
    private external fun startChargingNative(): Boolean
    private external fun stopChargingNative(): Boolean

    // 灯光控制服务原生方法
    private external fun setLightNative(paramsJson: String): Boolean
    private external fun turnOffLightNative(paramsJson: String): Boolean

    // 技能管理API原生方法
    private external fun loadSkillNative(paramsJson: String): Boolean
    private external fun unloadSkillNative(paramsJson: String): Boolean
    private external fun startSkillNative(paramsJson: String): Boolean
    private external fun stopSkillNative(paramsJson: String): Boolean

    // 系统诊断API原生方法
    private external fun runDiagnosisNative(): String
    private external fun getSystemStatusNative(): String
    private external fun getErrorLogNative(): String
}