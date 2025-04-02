@file:OptIn(ExperimentalForeignApi::class)
@file:Suppress(
    "FunctionName",
    "SpellCheckingInspection",
    "RedundantNullableReturnType",
    "unused",
    "UNUSED_PARAMETER"
)

package com.airobot.device.yanapi.python

import com.airobot.pythoninterop.PyBytes_FromObject
import com.airobot.pythoninterop.PyBytes_FromString
import com.airobot.pythoninterop.PyDict_GetItemString
import com.airobot.pythoninterop.PyDict_Keys
import com.airobot.pythoninterop.PyDict_Values
import com.airobot.pythoninterop.PyGILState_STATE
import com.airobot.pythoninterop.PyList_GetItem
import com.airobot.pythoninterop.PyList_Size
import com.airobot.pythoninterop.PyModule_GetDict
import com.airobot.pythoninterop.PyTuple_GetItem
import com.airobot.pythoninterop.PyTuple_Size
import com.airobot.pythoninterop.PyUnicode_AsUTF8
import com.airobot.pythoninterop._object
import com.airobot.pythoninterop._typeobject
import com.airobot.pythoninterop.my_PyBool_Check
import com.airobot.pythoninterop.my_PyBytes_Check
import com.airobot.pythoninterop.my_PyDict_Check
import com.airobot.pythoninterop.my_PyFloat_Check
import com.airobot.pythoninterop.my_PyList_Check
import com.airobot.pythoninterop.my_PyLong_Check
import com.airobot.pythoninterop.my_PyModule_Check
import com.airobot.pythoninterop.my_PyTuple_Check
import com.airobot.pythoninterop.my_PyUnicode_Check
import com.airobot.pythoninterop.my_Py_REFCNT
import com.airobot.pythoninterop.my_Py_SIZE
import com.airobot.pythoninterop.my_Py_TYPE
import com.airobot.pythoninterop.my_Py_XDECREF
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import kotlinx.cinterop.readValue
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toKStringFromUtf8


typealias PyObject = _object

fun PyTuple_Check(op: CPointer<PyObject>?): Boolean {
    return my_PyTuple_Check(op) != 0
}

fun Py_XDECREF(op: CPointer<PyObject>?) {
    my_Py_XDECREF(op)
}

fun PyUnicode_Check(op: CPointer<PyObject>?): Boolean {
    return my_PyUnicode_Check(op) != 0
}

fun PyBool_Check(op: CPointer<PyObject>?): Boolean {
    return my_PyBool_Check(op) != 0
}

fun PyLong_Check(op: CPointer<PyObject>?): Boolean {
    return my_PyLong_Check(op) != 0
}

fun PyFloat_Check(op: CPointer<PyObject>?): Boolean {
    return my_PyFloat_Check(op) != 0
}

fun PyBytes_Check(op: CPointer<PyObject>?): Boolean {
    return my_PyBytes_Check(op) != 0
}

fun PyDict_Check(op: CPointer<PyObject>?): Boolean {
    return my_PyDict_Check(op) != 0
}

fun PyModule_Check(op: CPointer<PyObject>?): Boolean {
    return my_PyModule_Check(op) != 0
}

fun PyList_Check(op: CPointer<PyObject>?): Boolean {
    return my_PyList_Check(op) != 0
}


fun Py_TYPE(op: CPointer<PyObject>?): CPointer<_typeobject>? {
    return my_Py_TYPE(op)
}

fun Py_REFCNT(op: CPointer<PyObject>?): Int {
    return my_Py_REFCNT(op)
}

fun Py_SIZE(op: CPointer<PyObject>?): Int {
    return my_Py_SIZE(op)
}

val CPointer<PyObject>.typeName: String
    get() {
        val tpName = Py_TYPE(this)?.pointed?.tp_name?.toKStringFromUtf8()
        return (tpName ?: (this.pointed.readValue())).toString()
    }

val CPointer<PyObject>.refCount: Int
    get() {
        val refCount = Py_REFCNT(this)
        return refCount
    }

val CPointer<PyObject>.objectSize: Int
    get() {
        val refCount = Py_SIZE(this).toInt()
        return refCount
    }

fun CPointer<PyObject>.isInstanceOf(typeName: String): Boolean {
    return this.typeName == typeName
}

fun CPointer<PyObject>.isInstanceOf(type: CPointer<PyObject>): Boolean {
    return this.pointed.ob_type == type
}

fun CPointer<PyObject>.toKString(): String? {
    val isUnicode = PyUnicode_Check(this)
    val isBytes = PyBytes_Check(this)
    return if (isUnicode) {
        PyUnicode_AsUTF8(this)?.toKString()
    } else if (isBytes) {
        PyBytes_FromObject(this)?.toKString()
    } else null
}

fun CPointer<PyObject>.toPyBytes(): CPointer<PyObject>? {
    val isUnicode = PyUnicode_Check(this)
    val isBytes = PyBytes_Check(this)
    return if (isUnicode) {
        PyBytes_FromString(PyUnicode_AsUTF8(this)?.toKString())
    } else if (isBytes) {
        this
    } else null
}

fun CPointer<CFunction<() -> CPointer<PyObject>?>>.toFuncHexPointer(): String {
    val addrHex = this.pointed.rawPtr.toLong().toULong().toString(16).padStart(8, '0')
    return "0x${addrHex}"
}

fun CPointer<PyObject>.toHexPointer(): String {
    val addrHex = this.pointed.rawPtr.toLong().toULong().toString(16).padStart(8, '0')
    return "0x${addrHex}"
}

fun CPointer<PyObject>.getDictValue(key: String): String? {
    if (PyModule_Check(this)) {
        val moduleDict = PyModule_GetDict(this)
        val value = PyDict_GetItemString(moduleDict, key)
        return value?.toKString()
    } else return null
}

fun CPointer<PyObject>.getAllDictKeys(): List<String> {
    if (!PyDict_Check(this)) {
        val list = mutableListOf<String>()
        val keys = PyDict_Keys(this)
        if (PyList_Check(keys)) {
            val size = PyList_Size(keys)
            for (i in 0 until size) {
                val item = PyList_GetItem(keys, i)
                val key = (PyUnicode_AsUTF8(item)?.toKString() ?: "")
                list.add(key)
            }
        }
        return list
    } else return emptyList()
}

fun CPointer<PyObject>.getAllDictKeyValues(): Map<String, String> {
    if (PyDict_Check(this)) {
        val map = mutableMapOf<String, String>()
        val keys = PyDict_Keys(this)
        val values = PyDict_Values(this)
        if (PyList_Check(keys) && PyList_Check(values)) {
            val size = PyList_Size(keys)
            val valueSize = PyList_Size(values)
            if (size == valueSize) {
                for (i in 0 until size) {
                    val item = PyList_GetItem(keys, i)
                    val itemValue = PyList_GetItem(keys, i)
                    val key = (PyUnicode_AsUTF8(item)?.toKString() ?: "")
                    val value = (PyUnicode_AsUTF8(itemValue)?.toKString() ?: "")
                    map.put(key, value)
                }
            }
        }
        return map
    } else return emptyMap()
}

fun CPointer<PyObject>.getAllListValues(): List<String> {
    if (PyList_Check(this)) {
        val list = mutableListOf<String>()
        val size = PyList_Size(this)
        for (i in 0 until size) {
            val item = PyList_GetItem(this, i)
            val key = (PyUnicode_AsUTF8(item)?.toKString() ?: "")
            list.add(key)
        }
        return list
    } else return emptyList()
}

fun CPointer<PyObject>.getAllTupleValues(): List<String> {
    if (PyTuple_Check(this)) {
        val list = mutableListOf<String>()
        val size = PyTuple_Size(this)
        for (i in 0 until size) {
            val item = PyTuple_GetItem(this, i)
            val key = (PyUnicode_AsUTF8(item)?.toKString() ?: "")
            list.add(key)
        }
        return list
    } else return emptyList()
}

fun PyGILState_STATE.use(call: PyGILState_STATE.() -> Unit) {
    call(this)
}