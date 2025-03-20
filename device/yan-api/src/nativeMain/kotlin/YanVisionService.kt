package com.airobot.device.yanapi

import com.airobot.pythoninterop.PyLong_AsLong
import com.airobot.pythoninterop.PyUnicode_AsUTF8
import com.airobot.pythoninterop.PyUnicode_FromString
import com.airobot.pythoninterop.get_visual_task_result
import com.airobot.pythoninterop.sync_do_face_recognition_value
import com.airobot.pythoninterop.sync_do_gesture_recognition
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString

/**
 * YAN设备视觉服务
 *
 * 提供机器人的视觉识别功能，如人脸识别、手势识别等
 */
@OptIn(ExperimentalForeignApi::class)
class YanVisionService {
    /**
     * 执行人脸识别并直接返回识别结果
     *
     * @return 识别到的人脸信息，失败返回null
     */
    fun syncDoFaceRecognitionValue(type: VisionFaceRecognitionType): String? {
        try {
            memScoped {
                val pyMode = PyUnicode_FromString(type.type)
                val result = sync_do_face_recognition_value(pyMode, 0)
                if (result != null) {
                    when (type) {
                        VisionFaceRecognitionType.QUANTITY, VisionFaceRecognitionType.AGE -> {
                            val pyInt = PyLong_AsLong(result)
                            return pyInt.toString()
                        }

                        else -> {
                            val pyStr = PyUnicode_AsUTF8(result)
                            return pyStr?.toKString()
                        }
                    }
                }
                return null
            }
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 执行手势识别
     *
     * @return 手势识别结果，包含识别状态和内容
     */
    fun syncDoGestureRecognition(): Map<String, Any>? {
        try {
            memScoped {
                val result = sync_do_gesture_recognition(0)
                if (result != null) {
                    return PyObjectToMap(result)
                }
                return null
            }
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 获取视觉任务结果
     *
     * @param taskType 任务类型
     * @param taskId 任务ID
     * @return 任务结果，包含任务状态和内容
     */
    fun getVisualTaskResult(taskType: VisionOption): Map<String, Any>? {
        try {
            memScoped {
                val pyTaskOption = PyUnicode_FromString(taskType.value)
                val pyTaskType = PyUnicode_FromString(taskType.childOption)
                val result = get_visual_task_result(pyTaskOption, pyTaskType, 0)
                if (result != null) {
                    return PyObjectToMap(result)
                }
                return null
            }
        } catch (e: Exception) {
            return null
        }
    }
}

sealed class VisionOption(val value: String, open val childOption: String) {
    sealed class FaceOption(override val childOption: String) : VisionOption("face", childOption) {
        object AGE : FaceOption("age")
        object GENDER : FaceOption("gender")
        object AGE_GROUP : FaceOption("age_group")
        object QUANTITY : FaceOption("quantity")
        object EXPRESSION : FaceOption("expression")
        object RECOGNITION : FaceOption("recognition")
        object TRACKING : FaceOption("tracking")
        object MASK : FaceOption("mask")
        object GLASS : FaceOption("glass")
    }

    sealed class OBJECT(override val childOption: String) : VisionOption("object", childOption) {
        object RECOGNITION : OBJECT("recognition")
    }

    sealed class COLOR(override val childOption: String) : VisionOption("color", childOption) {
        object COLOR_DETECT : COLOR("color_detect")
    }

    sealed class HAND(override val childOption: String) : VisionOption("hand", childOption) {
        object GESTURE : HAND("gesture")
    }
}

enum class VisionFaceRecognitionType(val type: String) {
    RECOGNITION("recognition"),
    QUANTITY("quantity"),
    AGE_GROUP("age_group"),
    GENDER("gender"),
    AGE("age"),
    EXPRESSION("expression"),
    MASK("mask"),
    GLASS("glass");
}