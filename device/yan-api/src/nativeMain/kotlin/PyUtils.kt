@file:OptIn(ExperimentalForeignApi::class)

package com.airobot.device.yanapi

import com.airobot.pythoninterop.*
import kotlinx.cinterop.*

private  inline fun <reified T : CStructVar> CPointer<T>.toRef(): CValuesRef<T>? {
    memScoped {
        return cValue<T>{
            this@toRef
        }
    }
}

fun String.toRef(): CValuesRef<PyObject>? {
    memScoped {
        return toPyObject()?.toRef()
    }
}

private fun String.toPyObject(): CPointer<PyObject>? {
    memScoped {
        val cString = this@toPyObject.cstr.ptr
        // 调用 Python C API 创建 PyObject
        return Py_BuildValue("s", cString)
    }
}
fun Int.toRef(): CValuesRef<PyObject>? {
    memScoped {
        return toPyObject()?.toRef()
    }
}
private fun Int.toPyObject(): CPointer<PyObject>? {
    memScoped {
        return  Py_BuildValue("i",this@toPyObject)
    }
}



/**
 * 将Python对象转换为Kotlin的Map对象
 *
 * 支持递归转换嵌套的字典和列表结构
 * 支持的Python类型：字典、列表、字符串、数字、布尔值
 *
 * @param pyObject Python对象指针
 * @return 转换后的Kotlin Map对象，如果转换失败则返回空Map
 */
@OptIn(ExperimentalForeignApi::class)
fun PyObjectToMap(pyObject: CPointer<PyObject>?): Map<String, Any> {
    if (pyObject == null) return emptyMap()

    val result = mutableMapOf<String, Any>()

    try {
        // 检查是否为字典类型
        if (my_PyDict_Check(pyObject) != 0) {
            val keys = PyDict_Keys(pyObject)
            if (keys != null) {
                val size = PyList_Size(keys)

                for (i in 0 until size) {
                    val key = PyList_GetItem(keys, i)
                    val value = PyDict_GetItem(pyObject, key)

                    if (key != null && value != null) {
                        val keyStr = PyUnicode_AsUTF8(key)?.toKString() ?: continue

                        // 根据值的类型进行相应的转换
                        result[keyStr] = convertPyObjectToKotlin(value)
                    }
                }
            }
        }
    } catch (e: Exception) {
        // 转换失败时返回空Map
    }

    return result
}

/**
 * 将Python对象转换为Kotlin对象
 *
 * @param pyObject Python对象指针
 * @return 转换后的Kotlin对象
 */
@OptIn(ExperimentalForeignApi::class)
private fun convertPyObjectToKotlin(pyObject: CPointer<PyObject>?): Any {
    if (pyObject == null) return Any()

    return when {
        // 布尔类型
        my_PyBool_Check(pyObject) != 0 -> {
            PyObject_IsTrue(pyObject) == 1
        }
        // 字符串类型
        my_PyUnicode_Check(pyObject) != 0 -> {
            PyUnicode_AsUTF8(pyObject)?.toKString() ?: ""
        }
        // 整数类型
        my_PyLong_Check(pyObject) != 0 -> {
            PyLong_AsLong(pyObject)
        }
        // 浮点数类型
        my_PyFloat_Check(pyObject) != 0 -> {
            PyFloat_AsDouble(pyObject)
        }
        // 字典类型
        my_PyDict_Check(pyObject) != 0 -> {
            PyObjectToMap(pyObject)
        }
        // 列表类型
        my_PyList_Check(pyObject) != 0 -> {
            val list = mutableListOf<Any>()
            val size = PyList_Size(pyObject)

            for (i in 0 until size) {
                val item = PyList_GetItem(pyObject, i)
                if (item != null) {
                    list.add(convertPyObjectToKotlin(item))
                }
            }

            list
        }
        // 其他类型，返回字符串表示
        else -> {
            val str = PyObject_Str(pyObject)
            if (str != null) {
                PyUnicode_AsUTF8(str)?.toKString() ?: "<unknown>"
            } else {
                "<unknown>"
            }
        }
    }
}