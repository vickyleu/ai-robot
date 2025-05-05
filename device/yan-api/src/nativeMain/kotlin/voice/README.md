# 增强型语音处理框架

这是一个专为机器人设计的增强型语音处理框架，重点解决了唤醒词检测中的误报问题，并提供了清晰的职责划分和模块化架构。

## 架构设计

该框架采用接口与实现分离的设计，遵循单一职责原则，各组件只负责自己的功能。主要分为以下几层：

1. **接口层**：定义各组件的抽象接口
   - AudioProcessor：音频处理基础接口
   - AudioRecorder：音频录制接口
   - AudioPlayer：音频播放接口
   - AudioAnalyzer：音频分析接口
   - WakewordDetector：唤醒词检测接口
   - SpeechRecognizer：语音识别接口
   - SpeechSynthesizer：语音合成接口
   - VoiceAssistant：语音助手顶层接口

2. **实现层**：提供具体实现
   - BasicAudioAnalyzer：基础音频分析实现
   - AdaptiveNoiseProfiler：自适应环境噪声分析器
   - PortAudioRecorder：基于PortAudio的录音实现
   - PortAudioPlayer：基于PortAudio的播放实现
   - SnowboyWakewordDetector：基础唤醒词检测实现
   - EnhancedWakewordDetector：增强型唤醒词检测实现
   - BasicVoiceAssistant：基础语音助手实现

3. **工具层**：提供底层工具和绑定
   - PortAudioBindings：PortAudio的Kotlin封装

## 关键特性

1. **模块化架构**：各组件通过接口隔离，可独立替换和测试
2. **增强型唤醒词检测**：
   - 多层验证机制，减少误触发
   - 自适应环境噪声模型，动态调整阈值
   - 信心值系统，要求连续多次确认
   - 去抖动设计，避免频繁触发
3. **高级音频分析**：
   - 音频特征提取（能量、ZCR等）
   - 语音活动检测
   - 噪声门限处理
   - 回声抑制
4. **完整的语音助手**：
   - 唤醒、识别、合成的完整流程
   - 状态管理和事件处理
   - 可配置参数

## PortAudio API 正确用法

本框架使用`PortAudioBindings.kt`封装了PortAudio的C API，解决了原生函数调用问题。注意以下几点：

1. **正确的方法名称**：
   - 使用`PortAudio.getDeviceCount()`而不是`Pa_GetNumDevices()`
   - 使用`PortAudio.getDefaultInputDevice()`和`PortAudio.getDefaultOutputDevice()`
   - 所有方法都从`PortAudio`对象中调用，而不是直接使用C函数

2. **统一的错误处理**：
   - 使用`PortAudio.paNoError`常量检查返回值
   - 使用`PortAudio.getErrorText(error)`获取可读错误信息
   
3. **统一的参数顺序**：
   - 初始化方法：`initialize(deviceId, sampleRate, channels)`
   - 音频处理方法：正确处理`CPointer`和Kotlin类型的转换

## 使用方法

### 基础使用

```kotlin
// 创建语音助手
val voiceAssistant = BasicVoiceAssistant()

// 设置语音识别器和合成器
voiceAssistant.setSpeechRecognizer(yourSpeechRecognizer)
voiceAssistant.setSpeechSynthesizer(yourSpeechSynthesizer)

// 设置参数
voiceAssistant.setParameters(
    wakewordSensitivity = 0.7f,
    listeningTimeoutMs = 5000L,
    soundFeedback = true
)

// 设置命令处理器
voiceAssistant.setCommandHandler { command ->
    // 处理命令并返回响应
    "已收到命令：$command"
}

// 初始化
voiceAssistant.initialize(
    wakewordModel = "/path/to/wakeword.model",
    recognizerModel = "/path/to/recognizer.model",
    synthesizerModel = "/path/to/synthesizer.model",
    synthesizerConfig = "/path/to/synthesizer.config"
)

// 启动助手
voiceAssistant.start()

// ... 使用完毕后
voiceAssistant.stop()
voiceAssistant.release()
```

### 高级使用：自定义组件

```kotlin
// 1. 创建定制的音频分析器
class MyCustomAnalyzer : AudioAnalyzer {
    // 实现接口方法...
}

// 2. 创建自定义唤醒词检测器
class MyWakewordDetector : WakewordDetector {
    // 实现接口方法...
}

// 3. 组装自定义语音助手
class MyVoiceAssistant : VoiceAssistant {
    private val audioAnalyzer = MyCustomAnalyzer()
    private val wakewordDetector = MyWakewordDetector()
    
    // 实现VoiceAssistant接口...
}
```

## 降低唤醒词误报的关键机制

1. **多层验证**：唤醒词检测需通过多重验证：
   - 原始模型检测
   - 音频特征验证（能量、ZCR等）
   - 信心值累积机制
   - 时间间隔控制

2. **自适应环境处理**：
   - 动态调整噪声门限和检测阈值
   - 根据环境噪声特征自动适应
   - 回声抑制，防止自身播放引起误触发

3. **增强型去抖动**：
   - 时间间隔控制，避免频繁触发
   - 信心值衰减机制，要求持续有效的输入 