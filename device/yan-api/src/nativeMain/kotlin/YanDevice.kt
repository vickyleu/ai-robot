package com.airobot.device.yanapi

import androidx.annotation.IntRange
import com.airobot.core.device.Device
import com.airobot.core.device.DeviceCommand
import com.airobot.core.device.DeviceStatus
import com.airobot.core.device.DeviceType
import com.airobot.device.yanapi.Servo.ServoMode
import com.airobot.device.yanapi.Servo.ServoName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * YAN设备实现类
 *
 * 实现了Device接口，提供与YAN协议兼容的设备控制功能
 */
class YanDevice(
    override val deviceId: String,
    override val name: String
) : Device {
    // 系统服务
    private val locomotionService = YanLocomotionService()
    private val speechService = YanSpeechService()
    private val servoService = YanServoService()
    private val sensorService = YanSensorService()
    private val powerService = YanPowerService()
    private val lightService = YanLightService()
    private val visionService = YanVisionService()
    private val skillManager = YanSkillManager()
    private val diagnosisService = YanDiagnosisService()
    override val type: DeviceType = DeviceType.YAN

    private val _status = MutableStateFlow(DeviceStatus.DISCONNECTED)
    override val status: StateFlow<DeviceStatus> = _status

    private val protocol = YanProtocol
    private val statusManager = YanStatusManager()

    private var connection: YanConnection? = null

    override suspend fun connect() {
        try {
            _status.value = DeviceStatus.CONNECTING
            // 创建连接
            connection = YanConnection().apply {
                connect(deviceId)

                // 设置状态更新回调
                onStatusUpdate { status ->
                    statusManager.updateStatus(status)
                }

                // 设置错误处理回调
                onError { error ->
                    _status.value = DeviceStatus.ERROR
                    statusManager.notifyEvent("error: $error")
                }
            }

            // 启动状态监控
            statusManager.monitorStatus()

            _status.value = DeviceStatus.CONNECTED
        } catch (e: Exception) {
            _status.value = DeviceStatus.ERROR
            throw ConnectionException("连接失败: ${e.message}", e)
        }
    }

    override suspend fun disconnect() {
        try {
            connection?.disconnect()
            statusManager.stopMonitor()
            _status.value = DeviceStatus.DISCONNECTED
        } catch (e: Exception) {
            // 即使断开连接失败，也将状态设置为断开
            _status.value = DeviceStatus.DISCONNECTED
            throw ConnectionException("断开连接失败: ${e.message}", e)
        } finally {
            connection = null
        }
    }

    override suspend fun sendCommand(command: DeviceCommand) {
        try {
            if (_status.value != DeviceStatus.CONNECTED) {
                throw IllegalStateException("设备未连接")
            }

            // 序列化命令
            val message = protocol.serialize(command)

            // 加密数据
            val encryptedMessage = protocol.encrypt(message)

            // 发送命令
            connection?.send(encryptedMessage)
        } catch (e: Exception) {
            throw CommandException("发送命令失败: ${e.message}", e)
        }
    }

    /**
     * 获取设备状态
     */
    fun getStatus(): Map<String, Any> {
        return statusManager.getCurrentStatus()
    }

    /**
     * 设置状态更新回调
     */
    fun onStatusUpdate(callback: (Map<String, Any>) -> Unit) {
        statusManager.setStatusCallback(callback)
    }

    // 前后左右控制
    suspend fun move(
        @IntRange(from = 0, to = 100)
        speed: Int,
        steps: Int,
        direction: String,
        wave: Boolean = true
    ) {
        when (direction) {
            "forward" -> locomotionService.move(speedVertical = speed, steps = steps, wave = wave)
            "backward" -> locomotionService.move(speedVertical = -speed, steps = steps, wave = wave)
            "left" -> locomotionService.move(speedHorizontal = speed, steps = steps, wave = wave)
            "right" -> locomotionService.move(speedHorizontal = -speed, steps = steps, wave = wave)
        }
    }

    suspend fun stop() {
        locomotionService.stop()
    }

    // 语音服务
    suspend fun speak(text: String) {
        speechService.startVoiceTts(text)
    }

    suspend fun stopSpeak() {
        speechService.stopVoiceTts()
    }

    // 移动单个舵机
    suspend fun moveJoint(servo: Servo) {
        servoService.setServoAngle(*arrayOf(servo))
    }

    // 移动单个舵机
    suspend fun moveJoint(jointId: ServoName, angle: Int) {
        moveJoints(jointId to angle)
    }

    // 移动多个舵机
    suspend fun moveJoints(vararg servos: Pair<ServoName, Int>) {
        servoService.setServoAngle(*servos.map { (jointId: ServoName, angle: Int) ->
            Servo(
                jointId,
                angel = angle
            )
        }.toTypedArray())
    }

    /**
     * 异步人脸识别
     */
    suspend fun startFaceDetection(type: VisionFaceRecognitionType): Boolean {
        return visionService.doFaceRecognitionValue(type)
    }

    /**
     * 同步人脸识别
     */
    suspend fun faceDetection(type: VisionFaceRecognitionType): String? {
        return visionService.syncDoFaceRecognitionValue(type)
    }

    /**
     * 获取识别结果
     */
    suspend fun handDetection(option: VisionOption): Map<String, Any>? {
        return visionService.getVisualTaskResult(option)
    }

    suspend fun stopFaceDetection(type: VisionFaceRecognitionType) {
        visionService.stopFaceRecognition(type)
    }

    // 设置舵机模式
    suspend fun setJointMode(jointId: ServoName, mode: ServoMode) {
        servoService.setServosMode(listOf(jointId), mode)
    }

    // 设置多个舵机模式
    suspend fun setJointModes(jointId: List<ServoName>, mode: ServoMode) {
        servoService.setServosMode(jointId, mode)
    }

//    suspend fun resetJoint(jointId: String) {
//         servoService.resetJoint(jointId)
//    }

    // 传感器服务
    suspend fun getSensorData(type: String): Map<String, Any> {
        return sensorService.getSensorData(type)
    }

    // 电源管理
    suspend fun getBatteryLevel(): Int {
        return powerService.getBatteryLevel()
    }

    suspend fun isCharging(): Boolean {
        return powerService.isCharging()
    }

    suspend fun version(type: VersionType): String? {
        return diagnosisService.getRobotVersionInfo(type)
    }

    // 灯光控制
    suspend fun setLight(type: LightType, color: LightColor) {
        lightService.setLight(type, color)
    }

    suspend fun turnOffLight() {
        lightService.turnOff()
    }

//    // 技能管理
//    suspend fun loadSkill(skillId: String) {
//        skillManager.loadSkill(skillId)
//    }
//
//    suspend fun startSkill(skillId: String, params: Map<String, Any>) {
//        skillManager.startSkill(skillId, params)
//    }
}


/**
 * 连接异常
 */
class ConnectionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 命令异常
 */
class CommandException(message: String, cause: Throwable? = null) : Exception(message, cause)