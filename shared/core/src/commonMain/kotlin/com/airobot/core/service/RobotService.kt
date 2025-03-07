package com.airobot.core.service

/**
 * 机器人服务接口
 * 
 * 所有机器人功能服务的基础接口，定义了服务的基本生命周期方法
 */
interface RobotService {
    /**
     * 初始化服务
     * 
     * @return 初始化是否成功
     */
    suspend fun initialize(): Boolean
    
    /**
     * 关闭服务
     * 
     * @return 关闭是否成功
     */
    suspend fun shutdown(): Boolean
    
    /**
     * 获取服务名称
     * 
     * @return 服务名称
     */
    fun getServiceName(): String
    
    /**
     * 检查服务是否可用
     * 
     * @return 服务是否可用
     */
    fun isAvailable(): Boolean
}