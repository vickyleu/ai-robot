#ifndef PYTHON_EXPORT_H
#define PYTHON_EXPORT_H
#include <Python.h>

int my_PyBool_Check(PyObject* op);
int my_PyDict_Check(PyObject* op);
int my_PyUnicode_Check(PyObject* op);
int my_PyLong_Check(PyObject* op);
int my_PyFloat_Check(PyObject* op);
int my_PyList_Check(PyObject* op);
PyObject *  my_Py_True();
PyObject *  my_Py_False();

#endif