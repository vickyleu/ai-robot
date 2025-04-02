#ifndef PYTHON_EXPORT_H
#define PYTHON_EXPORT_H

#include <Python.h>

#ifdef __cplusplus
#define __PREFFIX extern "C"
#else
#define __PREFFIX
#endif


__PREFFIX int my_PyBool_Check(PyObject* op);

__PREFFIX int my_PyDict_Check(PyObject* op);

__PREFFIX int my_PyUnicode_Check(PyObject* op);

__PREFFIX int my_PyLong_Check(PyObject* op);

__PREFFIX int my_PyFloat_Check(PyObject* op);
__PREFFIX int my_PyBytes_Check(PyObject* op);
__PREFFIX int my_PyList_Check(PyObject *op);
__PREFFIX int my_PyModule_Check(PyObject *op);
__PREFFIX int my_PyTuple_Check(PyObject *op);

__PREFFIX PyObject *  my_Py_True();
__PREFFIX PyObject *  my_Py_False();


__PREFFIX void my_Py_CLEAR(PyObject*   op);
__PREFFIX void my_Py_XINCREF(PyObject* op);
__PREFFIX void my_Py_XDECREF(PyObject* op);

__PREFFIX Py_ssize_t my_Py_REFCNT(PyObject* op);
__PREFFIX _typeobject* my_Py_TYPE(PyObject* op);
__PREFFIX Py_ssize_t my_Py_SIZE(PyObject* op);

// 定义函数指针类型
typedef PyObject* (*PyInitFunc)(void);

// 桥接函数：返回 PyInit_YanAPI 的地址
__PREFFIX PyInitFunc my_PyInit_YanAPI(void);

#endif