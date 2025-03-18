@file:OptIn(ExperimentalForeignApi::class)

package com.airobot.device.yanapi

import com.airobot.pythoninterop.Py_BuildValue
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CStructVar
import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValue
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped

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
