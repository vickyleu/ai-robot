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
     * 加载技能
     *
     * @param skillId 技能ID
     * @return 操作是否成功
     */
    fun loadSkill(skillId: String): Boolean {
        try {
            memScoped {
                val pySkillId = PyUnicodeObject(skillId.cstr.ptr.rawValue)
                val result = load_skill(pySkillId.reinterpret<PyObject>().ptr)
                return result != null && PyObject_IsTrue(result) == 1
            }
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 启动技能
     *
     * @param skillId 技能ID
     * @param params 技能参数
     * @return 操作是否成功
     */
    fun startSkill(skillId: String, params: Map<String, Any>): Boolean {
        try {
            memScoped {
                val pySkillId = PyUnicodeObject(skillId.cstr.ptr.rawValue)
                val pyParams = mapToPyDict(params)
                val result = start_skill(pySkillId.reinterpret<PyObject>().ptr, pyParams)
                return result != null && PyObject_IsTrue(result) == 1
            }
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 停止技能
     *
     * @param skillId 技能ID
     * @return 操作是否成功
     */
    fun stopSkill(skillId: String): Boolean {
        try {
            memScoped {
                val pySkillId = PyUnicodeObject(skillId.cstr.ptr.rawValue)
                val result = stop_skill(pySkillId.reinterpret<PyObject>().ptr)
                return result != null && PyObject_IsTrue(result) == 1
            }
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 获取已安装技能列表
     *
     * @return 技能列表
     */
    fun getInstalledSkills(): List<String> {
        try {
            val result = get_installed_skills()
            if (result != null) {
                val skillList = mutableListOf<String>()
                val size = PyList_Size(result)
                
                for (i in 0 until size) {
                    val item = PyList_GetItem(result, i)
                    if (item != null) {
                        val pyStr = PyUnicode_AsUTF8(item)
                        pyStr?.toKString()?.let { skillList.add(it) }
                    }
                }
                
                return skillList
            }
            return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }
    }

    /**
     * 获取技能详情
     *
     * @param skillId 技能ID
     * @return 技能详情
     */
    fun getSkillInfo(skillId: String): Map<String, Any> {
        try {
            memScoped {
                val pySkillId = PyUnicodeObject(skillId.cstr.ptr.rawValue)
                val result = get_skill_info(pySkillId.reinterpret<PyObject>().ptr)
                if (result != null) {
                    return PyObjectToMap(result)
                }
                return emptyMap()
            }
        } catch (e: Exception) {
            return emptyMap()
        }
    }

    /**
     * 将Map转换为Python字典
     */
    private fun mapToPyDict(map: Map<String, Any>): CPointer<PyObject>? {
        val pyDict = PyDict_New() ?: return null
        
        try {
            map.forEach { (key, value) ->
                memScoped {
                    val pyKey = PyUnicodeObject(key.cstr.ptr.rawValue).reinterpret<PyObject>().ptr
                    val pyValue = when (value) {
                        is String -> PyUnicodeObject(value.cstr.ptr.rawValue).reinterpret<PyObject>().ptr
                        is Int -> PyLong_FromLong(value.toLong())
                        is Long -> PyLong_FromLong(value)
                        is Float -> PyFloat_FromDouble(value.toDouble())
                        is Double -> PyFloat_FromDouble(value)
                        is Boolean -> if (value) Py_True() else Py_False()
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