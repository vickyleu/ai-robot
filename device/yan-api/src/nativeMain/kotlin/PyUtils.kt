@file:OptIn(ExperimentalForeignApi::class)

package com.airobot.device.yanapi

import com.airobot.device.yanapi.pojo.AnySerializer
import com.airobot.device.yanapi.pojo.HashMapStringAnySerializer
import com.airobot.device.yanapi.pojo.SmartBooleanSerializer
import com.airobot.device.yanapi.python.PyBool_Check
import com.airobot.device.yanapi.python.PyDict_Check
import com.airobot.device.yanapi.python.PyFloat_Check
import com.airobot.device.yanapi.python.PyList_Check
import com.airobot.device.yanapi.python.PyLong_Check
import com.airobot.device.yanapi.python.PyObject
import com.airobot.device.yanapi.python.PyUnicode_Check
import com.airobot.pythoninterop.In
import com.airobot.pythoninterop.PyDict_GetItem
import com.airobot.pythoninterop.PyDict_Keys
import com.airobot.pythoninterop.PyErr_Clear
import com.airobot.pythoninterop.PyErr_Occurred
import com.airobot.pythoninterop.PyErr_Print
import com.airobot.pythoninterop.PyFloat_AsDouble
import com.airobot.pythoninterop.PyList_GetItem
import com.airobot.pythoninterop.PyList_Size
import com.airobot.pythoninterop.PyLong_AsLong
import com.airobot.pythoninterop.PyObject_IsTrue
import com.airobot.pythoninterop.PyObject_Str
import com.airobot.pythoninterop.PyUnicode_AsUTF8
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CStructVar
import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValue
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.rpc.internal.utils.InternalRpcApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlin.reflect.KClass
import kotlin.reflect.typeOf

private inline fun <reified T : CStructVar> CPointer<T>.toRef(): CValuesRef<T>? {
    memScoped {
        return cValue<T> {
            this@toRef
        }
    }
}

/*
@Suppress("UNUSED")
fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is String -> JsonPrimitive(this)
    is Array<*> -> JsonArray(map { it.toJsonElement() })
    is List<*> -> JsonArray(map { it.toJsonElement() })
    is Map<*, *> -> JsonObject(map { it.key.toString() to it.value.toJsonElement() }.toMap())
    else -> {
        try {
            // 如果不行，使用反射API
            val clazz = this::class
            @Suppress("UNCHECKED_CAST")
            // Cannot use 'CapturedType(out kotlin. Any)' as reified type parameter.
            val serializer = Json.serializersModule.serializer(clazz.createType())
            Json.encodeToJsonElement(serializer, this)
        } catch (e: Exception) {
            // 如果序列化失败，返回对象的字符串表示
            JsonPrimitive(this.toString())
        }
    }
}

@Suppress("UNUSED")
fun Any?.toJsonString(): String = Json.encodeToString(this.toJsonElement())
*/

@OptIn(InternalRpcApi::class)
val json = Json {
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
    coerceInputValues = true
    ignoreUnknownKeys = true
    prettyPrint = true
    allowStructuredMapKeys = true
    serializersModule = SerializersModule {
        try {
            contextual(Any::class, AnySerializer)
            val type = typeOf<HashMap<String, Any>>()
            @Suppress("UNCHECKED_CAST")
            val kclass = type.classifier as KClass<HashMap<String, Any>>
            contextual(kclass, HashMapStringAnySerializer)
//            contextual(Int::class, SmartIntBoolSerializer)
            contextual(Boolean::class, SmartBooleanSerializer)
        } catch (e: Exception) {
            e.printStackTrace()
            println("[FATAL] SerializersModule:${e.message}")
        }
    }
}

inline fun <reified T> Map<String, Any>.toJson(): T? {
    try {
        // 将 Map 转换为 JSON 字符串
        val jsonString = json.encodeToString(this)
        // 反序列化为目标类型
        return json.decodeFromString(jsonString)
    } catch (e: Exception) {
        e.printStackTrace()
        println("toJson:${e.message}")
    }
    return null
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
@Suppress("UNCHECKED_CAST", "FunctionName")
fun PyObjectToKoltinMap(pyObject: CPointer<PyObject>?): Map<String, Any> {
    memScoped {
        if (PyErr_Occurred() != null) {
            PyErr_Print()
            PyErr_Clear()
        }
        val result = mutableMapOf<String, Any>()
        if (pyObject == null) return emptyMap()
        try {
            // 检查是否为字典类型
            if (PyDict_Check(pyObject)) {
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
            e.printStackTrace()
            // 转换失败时返回空Map
        }
        return result
    }
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
        PyBool_Check(pyObject) -> {
            PyObject_IsTrue(pyObject) == 1
        }
        // 字符串类型
        PyUnicode_Check(pyObject) -> {
            PyUnicode_AsUTF8(pyObject)?.toKString() ?: ""
        }
        // 整数类型
        PyLong_Check(pyObject) -> {
            PyLong_AsLong(pyObject)
        }
        // 浮点数类型
        PyFloat_Check(pyObject) -> {
            PyFloat_AsDouble(pyObject)
        }
        // 字典类型
        PyDict_Check(pyObject) -> {
            PyObjectToKoltinMap(pyObject)
        }
        // 列表类型
        PyList_Check(pyObject) -> {
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