# Vosk语音识别集成指南

本文档介绍如何在YAN机器人上使用Kotlin Native实现的ALSA麦克风音频采集和Vosk语音识别功能。

## 功能概述

该实现提供以下功能：

- 使用ALSA API直接访问麦克风硬件
- 使用Vosk进行离线语音识别
- 支持实时语音识别结果流
- 支持单次识别和持续识别模式
- 完全使用Kotlin Native实现，无需Python依赖

## 依赖项

要使用此功能，您需要安装以下依赖：

1. **ALSA库**：用于音频采集
   ```bash
   sudo apt-get install libasound2-dev
   ```

2. **Vosk模型**：用于语音识别
   ```bash
   # 创建模型目录
   sudo mkdir -p /usr/local/share/yanshee-model
   
   # 下载模型（以中文小型模型为例）
   wget https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip
   
   # 解压模型
   unzip vosk-model-small-cn-0.22.zip
   
   # 移动模型文件
   sudo mv vosk-model-small-cn-0.22/* /usr/local/share/yanshee-model/
   ```

## 使用方法

### 基本用法

```kotlin
// 创建语音服务实例
val speechService = YanVoskSpeechService()

// 初始化服务
if (!speechService.initialize()) {
    println("初始化语音服务失败")
    return
}

// 启动识别
speechService.startRecognition()

// 收集识别结果
launch {
    speechService.recognitionText.collect { text ->
        if (text != null) {
            println("识别结果: $text")
        }
    }
}

// 使用完毕后释放资源
speechService.release()
```

### 单次识别模式

```kotlin
runBlocking {
    val result = speechService.recognizeOnce(timeoutMs = 5000)
    if (result != null) {
        println("识别结果: $result")
    } else {
        println("未能识别语音或超时")
    }
}
```

## 配置选项

初始化时可以配置以下参数：

```kotlin
speechService.initialize(
    deviceName = "default",  // ALSA设备名称
    modelPath = "/usr/local/share/yanshee-model/",  // Vosk模型路径
    sampleRate = 16000  // 音频采样率
)
```

## 高级用法

如果需要更精细的控制，可以直接使用`YanVoskSpeechRecognizer`类：

```kotlin
val recognizer = YanVoskSpeechRecognizer()

// 配置和初始化
recognizer.initialize(
    deviceName = "default",
    modelPath = "/usr/local/share/yanshee-model",
    sampleRate = 16000
)

// 启动识别
recognizer.startRecognition()

// 收集识别结果，包括部分结果和详细信息
launch {
    recognizer.recognitionResult.collect { result ->
        result?.let {
            println("文本: ${it.text}")
            println("是否部分结果: ${it.isPartial}")
            println("置信度: ${it.confidence}")
            
            // 单词级别的结果
            it.words.forEach { word ->
                println("单词: ${word.word}, 开始时间: ${word.startTime}, 结束时间: ${word.endTime}")
            }
        }
    }
}

// 停止识别
recognizer.stopRecognition()

// 释放资源
recognizer.release()
```

## 故障排除

1. **麦克风访问问题**：确保当前用户有权限访问音频设备
   ```bash
   sudo usermod -a -G audio $USER
   ```

2. **模型加载失败**：检查模型路径是否正确，模型文件是否完整

3. **识别质量不佳**：尝试使用更大、更准确的模型，或调整麦克风位置减少环境噪音

## 示例程序

参考`VoskSpeechRecognitionExample.kt`获取完整的使用示例。

## 性能考虑

- 语音识别是计算密集型任务，在资源受限的设备上可能需要使用较小的模型
- 调整缓冲区大小和周期大小可以平衡延迟和CPU使用率
- 对于长时间运行的应用，建议定期释放和重新初始化识别器以避免内存泄漏