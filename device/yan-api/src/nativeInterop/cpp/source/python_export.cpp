#include "../include/python/python_export.h"
#include "YanAPI.h"


int my_PyBool_Check(PyObject *op) {
    return (Py_TYPE(op) == &PyBool_Type);
}

int my_PyDict_Check(PyObject *op) {
    return PyType_FastSubclass(Py_TYPE(op), Py_TPFLAGS_DICT_SUBCLASS);
}

int my_PyUnicode_Check(PyObject *op) {
    return PyType_FastSubclass(Py_TYPE(op), Py_TPFLAGS_UNICODE_SUBCLASS);
}

int my_PyLong_Check(PyObject *op) {
    return PyType_FastSubclass(Py_TYPE(op), Py_TPFLAGS_LONG_SUBCLASS);
}

int my_PyFloat_Check(PyObject *op) {
    return PyObject_TypeCheck(op, &PyFloat_Type);
}

int my_PyBytes_Check(PyObject *op) {
    return PyType_FastSubclass(Py_TYPE(op), Py_TPFLAGS_BYTES_SUBCLASS);
}
int my_PyList_Check(PyObject *op) {
    return PyType_FastSubclass(Py_TYPE(op), Py_TPFLAGS_LIST_SUBCLASS);
}
int my_PyTuple_Check(PyObject *op) {
    return PyType_FastSubclass(Py_TYPE(op), Py_TPFLAGS_TUPLE_SUBCLASS);
}

PyObject *my_Py_True() {
    return ((PyObject *) &_Py_TrueStruct);
}

PyObject *my_Py_False() {
    return ((PyObject *) &_Py_FalseStruct);
}
int my_PyModule_Check(PyObject *op){
    return  PyModule_Check(op);
}

void my_Py_CLEAR(PyObject* op) {
    do {
        PyObject * _py_tmp = (PyObject *) (op);
        if (_py_tmp != NULL) {
            (op) = NULL;
            Py_DECREF(_py_tmp);
        }
    } while (0);
}
void my_Py_XINCREF(PyObject* op) {
    do {
        PyObject * _py_xincref_tmp = (PyObject *) (op);
        if (_py_xincref_tmp != NULL)
            Py_INCREF(_py_xincref_tmp);
    } while (0);
}
void my_Py_XDECREF(PyObject* op) {
    do {
        PyObject * _py_xdecref_tmp = (PyObject *) (op);
        if (_py_xdecref_tmp != NULL)
            Py_DECREF(_py_xdecref_tmp);
    } while (0);
}


Py_ssize_t my_Py_REFCNT(PyObject* ob){
    return (((PyObject*)(ob))->ob_refcnt);
}
_typeobject* my_Py_TYPE(PyObject* ob){
    return (((PyObject*)(ob))->ob_type);
}
Py_ssize_t my_Py_SIZE(PyObject* ob){
    return (((PyVarObject*)(ob))->ob_size);
}
// 桥接函数：返回 PyInit_YanAPI 的地址
PyInitFunc my_PyInit_YanAPI(void) {
    return &PyInit_Yanshee;
}

