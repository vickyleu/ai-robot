package com.airobot.device.yanapi

import com.airobot.core.command.Action
import com.airobot.core.command.ActionRegistry
import com.airobot.core.command.DeviceAdapter
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * YAN设备适配器
 * 
 * 负责注册YAN机器人支持的各种动作，并将这些动作映射到具体的设备操作
 */
class YanDeviceAdapter(private val device: YanDevice, override val deviceName: String) : DeviceAdapter {
    override fun getSupportedActions(): List<String> {
        TODO("Not yet implemented")
    }

    override suspend fun initialize() {
        TODO("Not yet implemented")
    }

    override suspend fun shutdown() {
        TODO("Not yet implemented")
    }

    /**
     * 注册YAN机器人支持的所有动作
     */
    override fun registerActions() {
        // 基础动作
        registerBasicActions()
        
        // 头部动作
        registerHeadActions()
        
        // 手臂动作
        registerArmActions()
        
        // 组合动作
        registerCombinedActions()
    }
    
    /**
     * 注册基础动作
     */
    private fun registerBasicActions() {
        // 前进动作
        ActionRegistry.register("move_forward", object : Action {
            override suspend fun execute(params: Map<String, JsonElement>) {
                val speed = params["speed"]?.jsonPrimitive?.int ?: 50
                val steps = params["steps"]?.jsonPrimitive?.int ?: 0
                val wave = params["wave"]?.jsonPrimitive?.boolean ?: true
                device.locomotionService.move(speedVertical = speed, steps = steps, wave = wave)
            }
        })
        
        // 后退动作
        ActionRegistry.register("move_backward", object : Action {
            override suspend fun execute(params: Map<String, JsonElement>) {
                val speed = params["speed"]?.jsonPrimitive?.int ?: 50
                val steps = params["steps"]?.jsonPrimitive?.int ?: 0
                val wave = params["wave"]?.jsonPrimitive?.boolean ?: true
                device.locomotionService.move(speedVertical = -speed, steps = steps, wave = wave)
            }
        })
        
        // 左转动作
        ActionRegistry.register("turn_left", object : Action {
            override suspend fun execute(params: Map<String, JsonElement>) {
                val angle = params["angle"]?.jsonPrimitive?.int ?: 90
                device.locomotionService.turnLeft(angle.coerceIn(0, 360))
            }
        })
        
        // 右转动作
        ActionRegistry.register("turn_right", object : Action {
            override suspend fun execute(params: Map<String, JsonElement>) {
                val angle = params["angle"]?.jsonPrimitive?.int ?: 90
                device.locomotionService.turnRight(angle.coerceIn(0, 360))
            }
        })
        
        // 停止动作
        ActionRegistry.register("stop", object : Action {
            override suspend fun execute(params: Map<String, JsonElement>) {
                device.locomotionService.stop()
            }
        })
        
        // 语音动作
        ActionRegistry.register("speak", object : Action {
            override suspend fun execute(params: Map<String, JsonElement>) {
                val content = params["content"]?.jsonPrimitive?.content ?: return
                device.speak(content)
            }
        })
    }
    
    /**
     * 注册头部动作
     */
    private fun registerHeadActions() {
        // 头部转动动作
        ActionRegistry.register("head_turn", object : Action {
            override suspend fun execute(params: Map<String, JsonElement>) {
                val angle = params["angle"]?.jsonPrimitive?.int ?: 90
                // 确保角度在安全范围内 (15-165)
                val safeAngle = angle.coerceIn(15, 165)
                device.moveJoint(Servo.ServoName.颈部水平, safeAngle)
            }
        })
        
        // 点头动作
        ActionRegistry.register("nodding", object : Action {
            override suspend fun execute(params: Map<String, JsonElement>) {
                val times = params["times"]?.jsonPrimitive?.int ?: 2
                val speed = params["speed"]?.jsonPrimitive?.int ?: 500
                
                // 执行点头动作（如果有颈部上下舵机，这里需要修改）
                repeat(times) {
                    // 向下点头
                    device.moveJoint(Servo.ServoName.颈部水平, 135)
                    delay(speed.toLong())
                    // 回到中间
                    device.moveJoint(Servo.ServoName.颈部水平, 90)
                    delay(speed.toLong())
                }
            }
        })
        
        // 摇头动作
        ActionRegistry.register("shaking", object : Action {
            override suspend fun execute(params: Map<String, JsonElement>) {
                val times = params["times"]?.jsonPrimitive?.int ?: 2
                val speed = params["speed"]?.jsonPrimitive?.int ?: 500
                val angle = params["angle"]?.jsonPrimitive?.int ?: 30
                
                // 确保角度在安全范围内
                val safeAngle = angle.coerceIn(15, 45)
                val leftAngle = 90 - safeAngle
                val rightAngle = 90 + safeAngle
                
                // 执行摇头动作
                repeat(times) {
                    // 向左摇头
                    device.moveJoint(Servo.ServoName.颈部水平, leftAngle)
                    delay(speed.toLong())
                    // 向右摇头
                    device.moveJoint(Servo.ServoName.颈部水平, rightAngle)
                    delay(speed.toLong())
                }
                
                // 回到中间位置
                device.moveJoint(Servo.ServoName.颈部水平, 90)
            }
        })
    }
    
    /**
     * 注册手臂动作
     */
    private fun registerArmActions() {
        // 举手动作
        ActionRegistry.register("hands_up", object : Action {
            override suspend fun execute(params: Map<String, JsonElement>) {
                val leftArm = params["left"]?.jsonPrimitive?.boolean ?: true
                val rightArm = params["right"]?.jsonPrimitive?.boolean ?: true
                
                val servoPairs = mutableListOf<Pair<Servo.ServoName, Int>>()
                
                if (leftArm) {
                    servoPairs.add(Servo.ServoName.左肩上下 to 180)
                    servoPairs.add(Servo.ServoName.左肘 to 90)
                }
                
                if (rightArm) {
                    servoPairs.add(Servo.ServoName.右肩上下 to 180)
                    servoPairs.add(Servo.ServoName.右肘 to 90)
                }
                
                device.moveJoints(*servoPairs.toTypedArray())
            }
        })
        
        // 放手动作
        ActionRegistry.register("hands_down", object : Action {
            override suspend fun execute(params: Map<String, JsonElement>) {
                val leftArm = params["left"]?.jsonPrimitive?.boolean ?: true
                val rightArm = params["right"]?.jsonPrimitive?.boolean ?: true
                
                val servoPairs = mutableListOf<Pair<Servo.ServoName, Int>>()
                
                if (leftArm) {
                    servoPairs.add(Servo.ServoName.左肩上下 to 0)
                    servoPairs.add(Servo.ServoName.左肘 to 0)
                }
                
                if (rightArm) {
                    servoPairs.add(Servo.ServoName.右肩上下 to 0)
                    servoPairs.add(Servo.ServoName.右肘 to 0)
                }
                
                device.moveJoints(*servoPairs.toTypedArray())
            }
        })
        
        // 舵机移动动作
        ActionRegistry.register("servo_move", object : Action {
            override suspend fun execute(params: Map<String, JsonElement>) {
                val jointName = params["joint"]?.jsonPrimitive?.content ?: return
                val angle = params["angle"]?.jsonPrimitive?.int ?: 90
                
                try {
                    val servoName = Servo.ServoName.valueOf(jointName)
                    // 确保角度在0-180范围内
                    val safeAngle = angle.coerceIn(0, 180)
                    device.moveJoint(servoName, safeAngle)
                } catch (e: Exception) {
                    // 无效的舵机名称
                    println("无效的舵机名称: $jointName")
                }
            }
        })
    }
    
    /**
     * 注册组合动作
     */
    private fun registerCombinedActions() {
        // 鞠躬动作
        ActionRegistry.register("bowing", object : Action {
            override suspend fun execute(params: Map<String, JsonElement>) {
                val depth = params["depth"]?.jsonPrimitive?.int ?: 45
                val duration = params["duration"]?.jsonPrimitive?.int ?: 1000
                
                // 确保角度在安全范围内
                val safeDepth = depth.coerceIn(30, 60)
                
                // 向前倾斜上半身（通过髋部舵机实现）
                device.moveJoints(
                    Servo.ServoName.左髋前后 to (90 + safeDepth),
                    Servo.ServoName.右髋前后 to (90 + safeDepth)
                )
                
                // 保持鞠躬姿势
                delay(duration.toLong())
                
                // 恢复直立姿势
                device.moveJoints(
                    Servo.ServoName.左髋前后 to 90,
                    Servo.ServoName.右髋前后 to 90
                )
            }
        })
        
        // 挥手动作
        ActionRegistry.register("waving", object : Action {
            override suspend fun execute(params: Map<String, JsonElement>) {
                val isRight = params["right"]?.jsonPrimitive?.boolean ?: true
                val times = params["times"]?.jsonPrimitive?.int ?: 3
                val speed = params["speed"]?.jsonPrimitive?.int ?: 400
                
                if (isRight) {
                    // 右手臂抬起
                    device.moveJoints(
                        Servo.ServoName.右肩上下 to 180,
                        Servo.ServoName.右肩水平 to 135,
                        Servo.ServoName.右肘 to 90
                    )
                    
                    // 挥动手臂
                    repeat(times) {
                        device.moveJoint(Servo.ServoName.右肩水平, 120)
                        delay(speed.toLong())
                        device.moveJoint(Servo.ServoName.右肩水平, 150)
                        delay(speed.toLong())
                    }
                    
                    // 恢复初始位置
                    device.moveJoints(
                        Servo.ServoName.右肩上下 to 0,
                        Servo.ServoName.右肩水平 to 90,
                        Servo.ServoName.右肘 to 0
                    )
                } else {
                    // 左手臂抬起
                    device.moveJoints(
                        Servo.ServoName.左肩上下 to 180,
                        Servo.ServoName.左肩水平 to 45,
                        Servo.ServoName.左肘 to 90
                    )
                    
                    // 挥动手臂
                    repeat(times) {
                        device.moveJoint(Servo.ServoName.左肩水平, 60)
                        delay(speed.toLong())
                        device.moveJoint(Servo.ServoName.左肩水平, 30)
                        delay(speed.toLong())
                    }
                    
                    // 恢复初始位置
                    device.moveJoints(
                        Servo.ServoName.左肩上下 to 0,
                        Servo.ServoName.左肩水平 to 90,
                        Servo.ServoName.左肘 to 0
                    )
                }
            }
        })
    }
}