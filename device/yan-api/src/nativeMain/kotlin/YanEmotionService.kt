package com.airobot.device.yanapi

import com.airobot.pythoninterop.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.*
import com.airobot.pythoninterop.*
/**
 * YAN设备情感服务
 *
 * 提供机器人的情感表达功能，如表情、动作等
 */
@OptIn(ExperimentalForeignApi::class)
class YanEmotionService {
    /**
     * 设置表情
     *
     * @param emotion 表情名称，如"happy", "sad", "angry"等
     * @return 操作是否成功
     */
    fun setEmotion(emotion: String): Boolean {
        try {
            memScoped {
                val pyEmotion = PyUnicodeObject(emotion.cstr.ptr.rawValue)
                val result = set_robot_emotion(pyEmotion.reinterpret<PyObject>().ptr)
                return result != null && PyObject_IsTrue(result) == 1
            }
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 获取当前表情
     *
     * @return 当前表情名称，失败返回null
     */
    fun getCurrentEmotion(): String? {
        try {
            val result = get_robot_emotion()
            if (result != null) {
                val pyStr = PyUnicode_AsUTF8(result)
                return pyStr?.toKString()
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 播放动作
     *
     * @param action 动作名称
     * @return 操作是否成功
     */
    fun playAction(action: String): Boolean {
        try {
            memScoped {
                val pyAction = PyUnicodeObject(action.cstr.ptr.rawValue)
                val result = play_robot_action(pyAction.reinterpret<PyObject>().ptr)
                return result != null && PyObject_IsTrue(result) == 1
            }
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 停止当前动作
     *
     * @return 操作是否成功
     */
    fun stopAction(): Boolean {
        try {
            val result = stop_robot_action()
            return result != null && PyObject_IsTrue(result) == 1
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 获取可用表情列表
     *
     * @return 表情列表，失败返回空列表
     */
    fun getAvailableEmotions(): List<String> {
        try {
            val result = get_available_emotions()
            if (result != null) {
                val emotions = mutableListOf<String>()
                val size = PyList_Size(result)
                
                for (i in 0 until size) {
                    val item = PyList_GetItem(result, i)
                    if (item != null) {
                        val pyStr = PyUnicode_AsUTF8(item)
                        pyStr?.toKString()?.let { emotions.add(it) }
                    }
                }
                
                return emotions
            }
            return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }
    }
}