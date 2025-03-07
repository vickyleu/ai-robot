package com.airobot.core.service

import com.airobot.device.yanapi.YanLocomotionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 运动服务接口的YAN机器人实现
 * 
 * 将YanLocomotionService和YanServoService适配到MotionService接口
 */
class MotionServiceImpl : MotionService {
    private val yanLocomotionService = YanLocomotionService()
    private var isInitialized = false
    
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
        return "YAN运动服务"
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
     * 移动机器人
     * 
     * @param speedVertical 前后速度，范围-100~100, 0表示停止, 正值表示前进，负值表示后退
     * @param speedHorizontal 左右速度，范围-100~100, 0表示停止, 正值表示右转，负值表示左转
     * @param duration 持续时间(毫秒)，0表示持续运动直到调用stop
     * @return 操作是否成功
     */
    override suspend fun move(speedVertical: Int, speedHorizontal: Int, duration: Long): Boolean {
        return withContext(Dispatchers.Default) {
            try {
                // 如果duration为0，则持续运动直到调用stop
                val steps = if (duration > 0) {
                    // 根据duration计算步数，这里简化处理，实际应根据速度和时间计算
                    (duration / 100).toInt().coerceAtLeast(1)
                } else {
                    0 // 0表示持续运动
                }
                
                yanLocomotionService.move(
                    speedVertical = speedVertical,
                    speedHorizontal = speedHorizontal,
                    steps = steps,
                    wave = true
                )
            } catch (e: Exception) {
                false
            }
        }
    }
    
    /**
     * 停止移动
     * 
     * @return 操作是否成功
     */
    override suspend fun stop(): Boolean {
        return withContext(Dispatchers.Default) {
            try {
                yanLocomotionService.stop()
            } catch (e: Exception) {
                false
            }
        }
    }
    
    /**
     * 转向
     * 
     * @param angle 角度，范围0-360
     * @param direction 方向，true表示右转，false表示左转
     * @return 操作是否成功
     */
    override suspend fun turn(angle: Int, direction: Boolean): Boolean {
        return withContext(Dispatchers.Default) {
            try {
                if (direction) {
                    yanLocomotionService.turnRight(angle)
                } else {
                    yanLocomotionService.turnLeft(angle)
                }
            } catch (e: Exception) {
                false
            }
        }
    }
    
    /**
     * 控制关节
     * 
     * @param jointId 关节ID
     * @param angle 角度值
     * @param speed 速度值，范围0-100
     * @return 操作是否成功
     */
    override suspend fun moveJoint(jointId: String, angle: Int, speed: Int): Boolean {
        return withContext(Dispatchers.Default) {
            try {
                // 将jointId转换为YAN的ServoName
                val servoName = try {
                    com.airobot.device.yanapi.Servo.ServoName.valueOf(jointId)
                } catch (e: Exception) {
                    return@withContext false
                }
                
                // 创建Servo对象并设置舵机角度
                val servo = com.airobot.device.yanapi.Servo(
                    servoName = servoName,
                    angel = angle,
                    runtime = speed.coerceIn(200, 4000), // 将speed映射到runtime范围
                    isNeedBessel = true
                )
                
                // 使用YanServoService设置舵机角度
                val yanServoService = com.airobot.device.yanapi.YanServoService()
                yanServoService.setServoAngle(servo)
            } catch (e: Exception) {
                false
            }
        }
    }
    
    /**
     * 获取关节角度
     * 
     * @param jointId 关节ID
     * @return 当前角度值，失败返回-1
     */
    override suspend fun getJointAngle(jointId: String): Int {
        return withContext(Dispatchers.Default) {
            try {
                // 将jointId转换为YAN的ServoName
                val servoName = try {
                    com.airobot.device.yanapi.Servo.ServoName.valueOf(jointId)
                } catch (e: Exception) {
                    return@withContext -1
                }
                
                // 使用YanServoService获取舵机角度
                val yanServoService = com.airobot.device.yanapi.YanServoService()
                yanServoService.getServoAngle(servoName)
            } catch (e: Exception) {
                -1
            }
        }
    }
    
    /**
     * 执行预定义动作
     * 
     * @param actionName 动作名称
     * @param params 动作参数
     * @return 操作是否成功
     */
    override suspend fun performAction(actionName: String, params: Map<String, Any>): Boolean {
        return withContext(Dispatchers.Default) {
            try {
                yanLocomotionService.performAction(actionName, params)
            } catch (e: Exception) {
                false
            }
        }
    }
}