package com.airobot.core.service

/**
 * 运动服务接口
 * 
 * 定义机器人运动相关功能，包括行走、转向、关节控制等
 */
interface MotionService : RobotService {
    /**
     * 移动机器人
     * 
     * @param speedVertical 前后速度，范围-100~100, 0表示停止, 正值表示前进，负值表示后退
     * @param speedHorizontal 左右速度，范围-100~100, 0表示停止, 正值表示右转，负值表示左转
     * @param duration 持续时间(毫秒)，0表示持续运动直到调用stop
     * @return 操作是否成功
     */
    suspend fun move(speedVertical: Int, speedHorizontal: Int, duration: Long = 0): Boolean
    
    /**
     * 停止移动
     * 
     * @return 操作是否成功
     */
    suspend fun stop(): Boolean
    
    /**
     * 转向
     * 
     * @param angle 角度，范围0-360
     * @param direction 方向，true表示右转，false表示左转
     * @return 操作是否成功
     */
    suspend fun turn(angle: Int, direction: Boolean): Boolean
    
    /**
     * 控制关节
     * 
     * @param jointId 关节ID
     * @param angle 角度值
     * @param speed 速度值，范围0-100
     * @return 操作是否成功
     */
    suspend fun moveJoint(jointId: String, angle: Int, speed: Int = 50): Boolean
    
    /**
     * 获取关节角度
     * 
     * @param jointId 关节ID
     * @return 当前角度值，失败返回null
     */
    suspend fun getJointAngle(jointId: String): Int?
    
    /**
     * 执行预定义动作
     * 
     * @param actionName 动作名称
     * @param params 动作参数
     * @return 操作是否成功
     */
    suspend fun performAction(actionName: String, params: Map<String, Any> = emptyMap()): Boolean
}