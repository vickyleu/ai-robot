package com.airobot.core.service

import kotlinx.coroutines.flow.StateFlow

/**
 * 视觉服务接口
 * 
 * 定义机器人视觉相关功能，包括人脸识别、物体检测等
 */
interface VisionService : RobotService {
    /**
     * 视觉识别类型
     */
    enum class RecognitionType {
        FACE,           // 人脸识别
        OBJECT,         // 物体检测
        QR_CODE,        // 二维码识别
        GESTURE,        // 手势识别
        EMOTION,        // 情绪识别
        AGE_GENDER,     // 年龄性别识别
        CUSTOM          // 自定义识别
    }

    /**
     * 识别结果类
     */
    data class RecognitionResult(
        val type: RecognitionType,
        val confidence: Float,
        val timestamp: Long,
        val label: String = "",
        val boundingBox: BoundingBox? = null,
        val extraData: Map<String, Any> = emptyMap()
    )

    /**
     * 边界框类
     */
    data class BoundingBox(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int
    )

    /**
     * 获取支持的识别类型
     *
     * @return 支持的识别类型列表
     */
    suspend fun getSupportedRecognitionTypes(): List<RecognitionType>

    /**
     * 开始视觉识别
     *
     * @param type 识别类型
     * @return 操作是否成功
     */
    suspend fun startRecognition(type: RecognitionType): Boolean

    /**
     * 停止视觉识别
     *
     * @param type 识别类型
     * @return 操作是否成功
     */
    suspend fun stopRecognition(type: RecognitionType): Boolean

    /**
     * 获取识别结果
     *
     * @param type 识别类型
     * @return 识别结果，失败返回null
     */
    suspend fun getRecognitionResult(type: RecognitionType): RecognitionResult?

    /**
     * 获取识别结果流
     *
     * @param type 识别类型
     * @return 识别结果流
     */
    fun getRecognitionResultStream(type: RecognitionType): StateFlow<RecognitionResult?>

    /**
     * 拍照
     *
     * @param savePath 保存路径
     * @return 操作是否成功，成功返回图片路径
     */
}