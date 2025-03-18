# YAN机器人API接口文档

本文档详细说明了YAN机器人API的多层接口结构，包括Python层、C++层和Kotlin层的函数定义和参数说明。

## 语音控制

### sync_do_voice_iat
同步执行语音听写。

#### Python接口
```python
def sync_do_voice_iat()
```

#### C++接口
```cpp
__PYX_EXTERN_C PyObject *sync_do_voice_iat(int __pyx_skip_dispatch);
```

#### Kotlin接口
```kotlin
public external fun sync_do_voice_iat(
    __pyx_skip_dispatch: kotlin.Int
): kotlinx.cinterop.CPointer<PyObject>?
```

### stop_voice_tts
停止语音合成。

#### Python接口
```python
def stop_voice_tts()
```

#### C++接口
```cpp
__PYX_EXTERN_C PyObject *stop_voice_tts(int __pyx_skip_dispatch);
```

#### Kotlin接口
```kotlin
public external fun stop_voice_tts(
    __pyx_skip_dispatch: kotlin.Int
): kotlinx.cinterop.CPointer<PyObject>?
```

## 视觉识别

### get_visual_task_result
获取视觉任务结果。

#### Python接口
```python
def get_visual_task_result(task_type: str, task_id: str)
```

#### C++接口
```cpp
__PYX_EXTERN_C PyObject *get_visual_task_result(PyObject *, PyObject *, int __pyx_skip_dispatch);
```

#### Kotlin接口
```kotlin
public external fun get_visual_task_result(
    arg0: kotlinx.cinterop.CValuesRef<PyObject>?,
    arg1: kotlinx.cinterop.CValuesRef<PyObject>?,
    __pyx_skip_dispatch: kotlin.Int
): kotlinx.cinterop.CPointer<PyObject>?
```

### sync_do_face_recognition_value
同步执行人脸识别并获取值。

#### Python接口
```python
def sync_do_face_recognition_value(timeout: int)
```

#### C++接口
```cpp
__PYX_EXTERN_C PyObject *sync_do_face_recognition_value(PyObject *, int __pyx_skip_dispatch);
```

#### Kotlin接口
```kotlin
public external fun sync_do_face_recognition_value(
    arg0: kotlinx.cinterop.CValuesRef<PyObject>?,
    __pyx_skip_dispatch: kotlin.Int
): kotlinx.cinterop.CPointer<PyObject>?
```

### sync_do_face_recognition
同步执行人脸识别。

#### Python接口
```python
def sync_do_face_recognition(timeout: int)
```

#### C++接口
```cpp
__PYX_EXTERN_C PyObject *sync_do_face_recognition(PyObject *, int __pyx_skip_dispatch);
```

#### Kotlin接口
```kotlin
public external fun sync_do_face_recognition(
    arg0: kotlinx.cinterop.CValuesRef<PyObject>?,
    __pyx_skip_dispatch: kotlin.Int
): kotlinx.cinterop.CPointer<PyObject>?
```

### sync_do_gesture_recognition
同步执行手势识别。

#### Python接口
```python
def sync_do_gesture_recognition(timeout: int)
```

#### C++接口
```cpp
__PYX_EXTERN_C PyObject *sync_do_gesture_recognition(PyObject *, int __pyx_skip_dispatch);
```

#### Kotlin接口
```kotlin
public external fun sync_do_gesture_recognition(
    arg0: kotlinx.cinterop.CValuesRef<PyObject>?,
    __pyx_skip_dispatch: kotlin.Int
): kotlinx.cinterop.CPointer<PyObject>?
```