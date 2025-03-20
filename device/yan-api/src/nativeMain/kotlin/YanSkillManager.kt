package com.airobot.device.yanapi

import com.airobot.pythoninterop.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.*

/**
 * YAN设备技能管理器
 *
 * 提供机器人的技能管理功能，如加载技能、启动技能等
 */
@OptIn(ExperimentalForeignApi::class)
class YanSkillManager {
    /**
     * 上传动作文件
     *
     * @param filePath 动作文件路径
     * @return 操作是否成功
     */
    fun uploadMotion(filePath: String): Boolean {
        try {
            memScoped {
                val pyFilePath = PyUnicode_FromString(filePath)
                val result = upload_motion(pyFilePath,0)
                return result != null && PyObject_IsTrue(result) == 1
            }
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 删除动作文件
     *
     * @param name 动作文件名称
     * @return 操作是否成功
     */
    fun deleteMotion(name: String): Boolean {
        try {
            memScoped {
                val pyName = PyUnicode_FromString(name)
                val result = delete_motion(pyName,0)
                return result != null && PyObject_IsTrue(result) == 1
            }
        } catch (e: Exception) {
            return false
        }
    }
    
    /**
     * 获取动作列表
     *
     * @return 动作列表
     */
    fun getMotionList(): Map<String, Any> {
        try {
            val result = get_motion_list(0)
            if (result != null) {
                return PyObjectToMap(result)
            }
            return emptyMap()
        } catch (e: Exception) {
            return emptyMap()
        }
    }

    /**
     * 获取当前动作播放状态
     *
     * @return 动作播放状态
     */
    fun getCurrentMotionPlayState(): Map<String, Any> {
        try {
            val result = get_current_motion_play_state(0)
            if (result != null) {
                return PyObjectToMap(result)
            }
            return emptyMap()
        } catch (e: Exception) {
            return emptyMap()
        }
    }
    
    /**
     * 获取当前层动作播放状态
     *
     * @return 层动作播放状态
     */
    fun getCurrentLayerMotionPlayState(): Map<String, Any> {
        try {
            val result = get_current_layer_motion_play_state(0)
            if (result != null) {
                return PyObjectToMap(result)
            }
            return emptyMap()
        } catch (e: Exception) {
            return emptyMap()
        }
    }
    
    /**
     * 退出动作步态
     *
     * @return 操作是否成功
     */
    fun exitMotionGait(): Boolean {
        try {
            val result = exit_motion_gait(0)
            return result != null && PyObject_IsTrue(result) == 1
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 获取动作步态状态
     *
     * @return 动作步态状态
     */
    fun getMotionGaitState(): Map<String, Any> {
        try {
            val result = get_motion_gait_state(0)
            if (result != null) {
                return PyObjectToMap(result)
            }
            return emptyMap()
        } catch (e: Exception) {
            return emptyMap()
        }
    }
    
    /**
     * 获取动作列表值
     *
     * @return 动作列表值
     */
    fun getMotionListValue(): Map<String, Any> {
        try {
            val result = get_motion_list_value(0)
            if (result != null) {
                return PyObjectToMap(result)
            }
            return emptyMap()
        } catch (e: Exception) {
            return emptyMap()
        }
    }

    /**
     * 获取技能详情
     *
     * 注意：get_skill_info函数在YanAPI.h中不存在，已移除相关方法
     * 此方法保留为空实现，以便将来可能的扩展
     *
     * @param skillId 技能ID
     * @return 技能详情，当前返回空Map
     */
    fun getSkillInfo(skillId: String): Map<String, Any> {
        // 由于YanAPI.h中不存在get_skill_info函数，返回空Map
        return emptyMap()
    }

    /**
     * 将Map转换为Python字典
     */
    private fun mapToPyDict(map: Map<String, Any>): CPointer<PyObject>? {
        val pyDict = PyDict_New() ?: return null
        
        try {
            map.forEach { (key, value) ->
                memScoped {
                    val pyKey = PyUnicode_FromString(key)
                    val pyValue = when (value) {
                        is String -> PyUnicode_FromString(value)
                        is Int -> PyLong_FromLong(value.toLong())
                        is Long -> PyLong_FromLong(value)
                        is Float -> PyFloat_FromDouble(value.toDouble())
                        is Double -> PyFloat_FromDouble(value)
                        is Boolean -> if (value) my_Py_True() else my_Py_False()
                        else -> null
                    }
                    
                    if (pyValue != null) {
                        PyDict_SetItem(pyDict, pyKey, pyValue)
                    }
                }
            }
            return pyDict
        } catch (e: Exception) {
            return null
        }
    }
}