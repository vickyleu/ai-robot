#include "include/python_export.h"

int my_PyBool_Check(PyObject* op) {
    return (Py_TYPE(op) == &PyBool_Type);
}
int my_PyDict_Check(PyObject* op) {
    return PyType_FastSubclass(Py_TYPE(op), Py_TPFLAGS_DICT_SUBCLASS);
}

int my_PyUnicode_Check(PyObject* op){
    return PyType_FastSubclass(Py_TYPE(op), Py_TPFLAGS_UNICODE_SUBCLASS);
}
int my_PyLong_Check(PyObject* op){
    return PyType_FastSubclass(Py_TYPE(op), Py_TPFLAGS_LONG_SUBCLASS);
}
int my_PyFloat_Check(PyObject* op){
    return PyObject_TypeCheck(op, &PyFloat_Type);
}
int my_PyList_Check(PyObject* op){
    return PyType_FastSubclass(Py_TYPE(op), Py_TPFLAGS_LIST_SUBCLASS);
}

PyObject *  my_Py_True(){
    return ((PyObject *) &_Py_TrueStruct);
}
PyObject * my_Py_False(){
    return ((PyObject *) &_Py_FalseStruct);
}