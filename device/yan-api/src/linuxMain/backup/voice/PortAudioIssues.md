# PortAudio API 调用问题与解决方案

## 问题描述

在使用PortAudio库时，发现了一些函数名称不一致的问题，特别是以下函数：

```kotlin
// 这个函数不存在于PortAudio API中
Pa_GetNumDevices() // ❌错误 - 不存在的函数

// 正确的函数名称是
Pa_GetDeviceCount() // ✅正确
```

这导致了编译错误和运行时异常，因为代码尝试调用一个不存在的函数。

## 解决方案

我们采用了两种解决方案：

### 1. 直接使用正确的C函数名

将所有`Pa_GetNumDevices()`调用替换为`Pa_GetDeviceCount()`：

```kotlin
// 错误用法
val numDevices = Pa_GetNumDevices()

// 正确用法
val numDevices = Pa_GetDeviceCount()
```

### 2. 使用PortAudioBindings.kt封装库

为了提供更好的错误处理和类型安全，我们创建了`PortAudioBindings.kt`文件，它封装了所有PortAudio函数：

```kotlin
// 使用封装后的API
val numDevices = PortAudio.getDeviceCount()
```

这种方法有以下优势：
- 统一的错误处理
- 更好的类型安全
- 简化的API调用
- 一致的命名约定

## 重要的PortAudio函数对应表

| C API 函数 | PortAudio对象封装方法 | 描述 |
|-----------|---------------------|-----|
| Pa_GetDeviceCount() | PortAudio.getDeviceCount() | 获取设备数量 |
| Pa_GetDefaultInputDevice() | PortAudio.getDefaultInputDevice() | 获取默认输入设备 |
| Pa_GetDefaultOutputDevice() | PortAudio.getDefaultOutputDevice() | 获取默认输出设备 |
| Pa_GetDeviceInfo() | PortAudio.getDeviceInfo() | 获取设备信息 |
| Pa_Initialize() | PortAudio.initialize() | 初始化库 |
| Pa_Terminate() | PortAudio.terminate() | 终止库 |
| Pa_OpenStream() | PortAudio.openStream() | 打开音频流 |
| Pa_StartStream() | PortAudio.startStream() | 启动音频流 |
| Pa_StopStream() | PortAudio.stopStream() | 停止音频流 |
| Pa_CloseStream() | PortAudio.closeStream() | 关闭音频流 |
| Pa_WriteStream() | PortAudio.writeStream() | 写入音频数据 |
| Pa_ReadStream() | PortAudio.readStream() | 读取音频数据 |

## 如何检查代码是否存在此问题

搜索代码库中的`Pa_GetNumDevices`即可找到所有需要修改的地方，然后：

1. 将`Pa_GetNumDevices`替换为`Pa_GetDeviceCount`，或者
2. 使用`PortAudio.getDeviceCount()`封装方法（推荐）

## 完整修复示例

```kotlin
// 修改前
private fun initializeAudio() {
    val error = Pa_Initialize()
    if (error != 0) {
        println("初始化失败")
        return
    }
    
    val numDevices = Pa_GetNumDevices() // 错误的函数名
    println("发现 $numDevices 个设备")
}

// 修改后（方法1：使用正确的C函数）
private fun initializeAudio() {
    val error = Pa_Initialize()
    if (error != 0) {
        println("初始化失败")
        return
    }
    
    val numDevices = Pa_GetDeviceCount() // 修正的函数名
    println("发现 $numDevices 个设备")
}

// 修改后（方法2：使用PortAudio封装 - 推荐）
private fun initializeAudio() {
    val error = PortAudio.initialize()
    if (error != PortAudio.paNoError) {
        println("初始化失败：" + PortAudio.getErrorText(error))
        return
    }
    
    val numDevices = PortAudio.getDeviceCount()
    println("发现 $numDevices 个设备")
}
``` 