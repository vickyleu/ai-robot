package com.airobot.device.yanapi

import com.airobot.pythoninterop.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.*

/**
 * YAN设备视觉服务
 *
 * 提供机器人的视觉识别功能，如人脸识别、手势识别等
 */
@OptIn(ExperimentalForeignApi::class)
class YanVisionService {
    /**
     * 执行人脸识别
     *
     * @param mode 识别模式，如"single"、"continuous"等
     * @return 人脸识别结果，包含识别状态和内容
     */
    fun syncDoFaceRecognition(mode: String): Map<String, Any>? {
        try {
            memScoped {
                val pyMode = PyUnicodeObject(mode.cstr.ptr.rawValue)
                val result = sync_do_face_recognition(pyMode.reinterpret<PyObject>().ptr,0)
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
     * 执行人脸识别并直接返回识别结果
     *
     * @param mode 识别模式，如"single"、"continuous"等
     * @return 识别到的人脸信息，失败返回null
     */
    fun syncDoFaceRecognitionValue(mode: String): String? {
        try {
            memScoped {
                val pyMode = PyUnicodeObject(mode.cstr.ptr.rawValue)
                val result = sync_do_face_recognition_value(pyMode.reinterpret<PyObject>().ptr,0)
                if (result != null) {
                    val pyStr = PyUnicode_AsUTF8(result)
                    return pyStr?.toKString()
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
     * @param mode 识别模式，如"single"、"continuous"等
     * @return 手势识别结果，包含识别状态和内容
     */
    fun syncDoGestureRecognition(mode: String): Map<String, Any>? {
        try {
            memScoped {
//                val pyMode = PyUnicodeObject(mode.cstr.ptr.rawValue)
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
    fun getVisualTaskResult(taskType: String, taskId: String): Map<String, Any>? {
        try {
            memScoped {
                val pyTaskType = PyUnicodeObject(taskType.cstr.ptr.rawValue)
                val pyTaskId = PyUnicodeObject(taskId.cstr.ptr.rawValue)
                val result = get_visual_task_result(
                    pyTaskType.reinterpret<PyObject>().ptr,
                    pyTaskId.reinterpret<PyObject>().ptr,
                    0
                )
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