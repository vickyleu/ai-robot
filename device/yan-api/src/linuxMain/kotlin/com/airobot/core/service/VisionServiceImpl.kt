package com.airobot.core.service

import com.airobot.device.yanapi.VisionFaceRecognitionType
import com.airobot.device.yanapi.VisionOption
import com.airobot.device.yanapi.YanVisionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

/**
 * 视觉服务接口的YAN机器人实现
 * 
 * 将YanVisionService适配到VisionService接口
 */
class VisionServiceImpl : VisionService {
    private val yanVisionService = YanVisionService()
    private var isInitialized = false
    
    // 存储各类型识别结果的状态流
    private val recognitionResultFlows = mutableMapOf<VisionService.RecognitionType, MutableStateFlow<VisionService.RecognitionResult?>>()
    
    // 存储视频保存路径
    private var videoSavePath: String? = null
    
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
        return "YAN视觉服务"
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
     * 将VisionService.RecognitionType转换为YAN的VisionFaceRecognitionType
     */
    /**
     * 将VisionService.RecognitionType转换为YAN的VisionFaceRecognitionType
     * 注意：AGE_GENDER类型需要分别处理年龄和性别信息
     */
    private fun mapRecognitionType(type: VisionService.RecognitionType): VisionFaceRecognitionType {
        return when (type) {
            VisionService.RecognitionType.FACE -> VisionFaceRecognitionType.RECOGNITION
            VisionService.RecognitionType.EMOTION -> VisionFaceRecognitionType.EXPRESSION
            VisionService.RecognitionType.AGE_GENDER -> VisionFaceRecognitionType.AGE
            VisionService.RecognitionType.GESTURE -> VisionFaceRecognitionType.RECOGNITION
            VisionService.RecognitionType.OBJECT -> VisionFaceRecognitionType.RECOGNITION
            VisionService.RecognitionType.QR_CODE -> VisionFaceRecognitionType.RECOGNITION
            VisionService.RecognitionType.CUSTOM -> VisionFaceRecognitionType.RECOGNITION
//            else -> VisionFaceRecognitionType.RECOGNITION // 默认使用人脸识别
        }
    }
    
    /**
     * 获取性别识别结果
     * 用于AGE_GENDER类型的性别信息获取
     */
    private suspend fun getGenderRecognitionResult(): String? {
        return withContext(Dispatchers.Default) {
            try {
                yanVisionService.syncDoFaceRecognitionValue(VisionFaceRecognitionType.GENDER)
            } catch (e: Exception) {
                null
            }
        }
    }
    
    /**
     * 获取支持的识别类型
     * 
     * @return 支持的识别类型列表
     */
    override suspend fun getSupportedRecognitionTypes(): List<VisionService.RecognitionType> {
        return withContext(Dispatchers.Default) {
            listOf(
                VisionService.RecognitionType.FACE,
                VisionService.RecognitionType.GESTURE,
                VisionService.RecognitionType.EMOTION,
                VisionService.RecognitionType.AGE_GENDER
            )
        }
    }
    
    /**
     * 开始视觉识别
     * 
     * @param type 识别类型
     * @return 操作是否成功
     */
    override suspend fun startRecognition(type: VisionService.RecognitionType): Boolean {
        return withContext(Dispatchers.Default) {
            try {
                val yanType = mapRecognitionType(type)
                yanVisionService.doFaceRecognitionValue(yanType)
            } catch (e: Exception) {
                false
            }
        }
    }
    
    /**
     * 停止视觉识别
     * 
     * @param type 识别类型
     * @return 操作是否成功
     */
    override suspend fun stopRecognition(type: VisionService.RecognitionType): Boolean {
        return withContext(Dispatchers.Default) {
            try {
                val yanType = mapRecognitionType(type)
                yanVisionService.stopFaceRecognition(yanType)
            } catch (e: Exception) {
                false
            }
        }
    }
    
    /**
     * 获取识别结果
     * 
     * @param type 识别类型
     * @return 识别结果，失败返回null
     */
    override suspend fun getRecognitionResult(type: VisionService.RecognitionType): VisionService.RecognitionResult? {
        return withContext(Dispatchers.Default) {
            try {
                val yanType = mapRecognitionType(type)
                val result = yanVisionService.syncDoFaceRecognitionValue(yanType)
                
                if (result != null) {
                    val confidence = 0.9f // YAN API没有提供置信度，这里使用默认值
                    val timestamp = Clock.System.now().toEpochMilliseconds()
                    
                    when (type) {
                        VisionService.RecognitionType.FACE -> {
                            VisionService.RecognitionResult(
                                type = type,
                                confidence = confidence,
                                timestamp = timestamp,
                                label = result,
                                boundingBox = null
                            )
                        }
                        VisionService.RecognitionType.EMOTION -> {
                            VisionService.RecognitionResult(
                                type = type,
                                confidence = confidence,
                                timestamp = timestamp,
                                label = result,
                                boundingBox = null
                            )
                        }
                        VisionService.RecognitionType.AGE_GENDER -> {
                            // 获取性别信息
                            val genderResult = getGenderRecognitionResult()
                            
                            VisionService.RecognitionResult(
                                type = type,
                                confidence = confidence,
                                timestamp = timestamp,
                                label = "${genderResult ?: "未知"} ${result}",
                                boundingBox = null,
                                extraData = mapOf(
                                    "age" to (result.toIntOrNull() ?: 0),
                                    "gender" to (genderResult ?: "未知")
                                )
                            )
                        }
                        VisionService.RecognitionType.GESTURE -> {
                            val gestureResult = yanVisionService.syncDoGestureRecognition()
                            if (gestureResult != null) {
                                VisionService.RecognitionResult(
                                    type = type,
                                    confidence = confidence,
                                    timestamp = timestamp,
                                    label = gestureResult["gesture"]?.toString() ?: "",
                                    boundingBox = null
                                )
                            } else {
                                null
                            }
                        }
                        else -> null
                    }
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
    
    /**
     * 获取识别结果流
     * 
     * @param type 识别类型
     * @return 识别结果流
     */
    override fun getRecognitionResultStream(type: VisionService.RecognitionType): StateFlow<VisionService.RecognitionResult?> {
        // 如果该类型的识别结果流不存在，则创建一个
        if (!recognitionResultFlows.containsKey(type)) {
            recognitionResultFlows[type] = MutableStateFlow(null)
        }
        
        return recognitionResultFlows[type]!!
    }
    
//    /**
//     * 拍照
//     *
//     * @param savePath 保存路径
//     * @return 操作是否成功，成功返回图片路径
//     */
//    override suspend fun takePhoto(savePath: String): String? {
//        return withContext(Dispatchers.Default) {
//            try {
//                val option = VisionOption.TAKE_PHOTO
//                val result = yanVisionService.getVisualTaskResult(option)
//
//                if (result != null && result["code"]?.toString() == "0") {
//                    // 如果YAN API返回了图片路径，则使用该路径
//                    val photoPath = result["path"]?.toString()
//
//                    if (photoPath != null) {
//                        try {
//                            // 创建源文件和目标文件对象
//                            val sourceFile = java.io.File(photoPath)
//                            val targetFile = java.io.File(savePath)
//
//                            // 确保目标目录存在
//                            targetFile.parentFile?.mkdirs()
//
//                            // 复制文件
//                            sourceFile.inputStream().use { input ->
//                                targetFile.outputStream().use { output ->
//                                    input.copyTo(output)
//                                }
//                            }
//
//                            // 返回用户指定的保存路径
//                            savePath
//                        } catch (e: Exception) {
//                            // 如果复制失败，返回原始路径
//                            photoPath
//                        }
//                    } else {
//                        null
//                    }
//                } else {
//                    null
//                }
//            } catch (e: Exception) {
//                null
//            }
//        }
//    }
    
    /**
     * 录制视频
     * 
     * @param savePath 保存路径
     * @param duration 录制时长(秒)，0表示持续录制直到调用stopVideoRecording
     * @return 操作是否成功
     */
//    override suspend fun startVideoRecording(savePath: String, duration: Int): Boolean {
//        return withContext(Dispatchers.Default) {
//            try {
//                // 存储用户指定的保存路径，以便在stopVideoRecording中使用
//                videoSavePath = savePath
//
//                val option = VisionOption.START_RECORD
//                val result = yanVisionService.getVisualTaskResult(option)
//
//                result != null && result["code"]?.toString() == "0"
//            } catch (e: Exception) {
//                false
//            }
//        }
//    }
    
    /**
     * 停止视频录制
     * 
     * @return 录制的视频路径，失败返回null
     */
//    override suspend fun stopVideoRecording(): String? {
//        return withContext(Dispatchers.Default) {
//            try {
//                val option = VisionOption.STOP_RECORD
//                val result = yanVisionService.getVisualTaskResult(option)
//
//                if (result != null && result["code"]?.toString() == "0") {
//                    // 如果YAN API返回了视频路径，则使用该路径
//                    val videoPath = result["path"]?.toString()
//
//                    if (videoPath != null && videoSavePath != null) {
//                        try {
//                            // 创建源文件和目标文件对象
//                            val sourceFile = java.io.File(videoPath)
//                            val targetFile = java.io.File(videoSavePath!!)
//
//                            // 确保目标目录存在
//                            targetFile.parentFile?.mkdirs()
//
//                            // 复制文件
//                            sourceFile.inputStream().use { input ->
//                                targetFile.outputStream().use { output ->
//                                    input.copyTo(output)
//                                }
//                            }
//
//                            // 返回用户指定的保存路径
//                            videoSavePath
//                        } catch (e: Exception) {
//                            // 如果复制失败，返回原始路径
//                            videoPath
//                        } finally {
//                            // 清除保存的路径
//                            videoSavePath = null
//                        }
//                    } else {
//                        videoPath
//                    }
//                } else {
//                    null
//                }
//            } catch (e: Exception) {
//                null
//            }
//        }
//    }
}