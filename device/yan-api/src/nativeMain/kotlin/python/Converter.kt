@file:OptIn(ExperimentalForeignApi::class)

package com.airobot.device.yanapi.python

import com.airobot.pythoninterop.PyBytes_FromObject
import com.airobot.pythoninterop.PyBytes_FromString
import com.airobot.pythoninterop.PyUnicode_AsUTF8
import com.airobot.pythoninterop._object
import com.airobot.pythoninterop.my_PyBytes_Check
import com.airobot.pythoninterop.my_PyUnicode_Check
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toKStringFromUtf8


typealias PyObject = _object

val  CPointer<PyObject>.typeName: String
    get() {
        val tpName = this.pointed.ob_type?.pointed?.tp_name?.toKStringFromUtf8()
        return (tpName?:(this.pointed.readValue())).toString()
    }

fun CPointer<PyObject>.isInstanceOf(typeName: String): Boolean {
    return this.typeName == typeName
}

fun CPointer<PyObject>.isInstanceOf(type: CPointer<PyObject>): Boolean {
    return this.pointed.ob_type == type
}

fun CPointer<PyObject>.toKString(): String? {
    val isUnicode = my_PyUnicode_Check(this)
    val isBytes = my_PyBytes_Check(this)
    if(isUnicode==1){
        return PyUnicode_AsUTF8(this)?.toKString()
    }else if (isBytes==1){
        return PyBytes_FromObject(this)?.toKString()
    }else return null
}
fun CPointer<PyObject>.toPyBytes(): CPointer<PyObject>? {
    val isUnicode = my_PyUnicode_Check(this)
    val isBytes = my_PyBytes_Check(this)
    if(isUnicode==1){
        return PyBytes_FromString(PyUnicode_AsUTF8(this)?.toKString())
    }else if (isBytes==1){
        return this
    }else return null
}