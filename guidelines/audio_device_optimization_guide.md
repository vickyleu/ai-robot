# 音频设备优化与代码库清理指导文件

## 一、文件结构与作用

### 1. 音频设备与采集 (Acquisition)

| 文件路径 | 文件名 | 作用 |
|---------|------|------|
| `voice/acquisition/portaudio/` | `PortAudioDevice.kt` | 音频设备实现，负责音频输入/输出功能，基于PortAudio |
| `voice/acquisition/portaudio/` | `PortAudioAcquisition.kt` | 音频采集实现，负责从物理设备采集PCM数据 |
| `voice/hal/` | `AudioDevice.kt` | 音频设备接口定义 |
| `voice/hal/` | `LinuxAudioDeviceSelector.kt` | Linux系统音频设备选择器，检测和配置ALSA设备 |
| `voice/hal/` | `AlsaStreamInfo.kt` | ALSA流信息数据类 |

### 2. 音频处理 (Processing)

| 文件路径 | 文件名 | 作用 |
|---------|------|------|
| `voice/audio/processing/` | `AudioProcessingManager.kt` | 音频处理管理器，协调整个处理流程 |
| `voice/audio/processing/` | `AudioPreprocessor.kt` | 音频预处理器，进行质量评估、降采样、静音过滤等 |
| `voice/audio/` | `AudioPipeline.kt` | 音频处理流水线定义 |
| `voice/audio/` | `AudioMetrics.kt` | 音频质量指标数据类 |

### 3. 语音活动检测 (VAD)

| 文件路径 | 文件名 | 作用 |
|---------|------|------|
| `voice/audio/vad/` | `VoiceActivityDetector.kt` | 语音活动检测器，识别音频中的人声 |
| `voice/audio/` | `VADMetrics.kt` | VAD指标数据类 |
| `voice/api/vad/` | `IVoiceActivityDetector.kt` | VAD接口定义 |

### 4. 已删除的重复文件

| 文件路径 | 文件名 | 原因 |
|---------|------|------|
| `voice/hal/` | `PortAudioDevice.kt` | 与`voice/acquisition/portaudio/PortAudioDevice.kt`重复 |
| `voice/hal/` | `VoiceActivityDetector.kt` | 与`voice/audio/vad/VoiceActivityDetector.kt`重复 |
| `voice/audio/preprocessing/` | `AudioPreprocessor.kt` | 与`voice/audio/processing/AudioPreprocessor.kt`重复 |
| `voice/hal/` | `AudioPreprocessor.kt` | 与`voice/audio/processing/AudioPreprocessor.kt`重复 |
| `voice/audio/acquisition/` | `PortAudioAcquisition.kt` | 与`voice/acquisition/portaudio/PortAudioAcquisition.kt`重复 |
| `voice/acquisition/portaudio/` | `VoiceActivityDetector.kt` | 与`voice/audio/vad/VoiceActivityDetector.kt`重复 |

## 二、问题分析

### 1. 音频初始化问题

**症状：** 
- "PortAudio not initialized" 错误
- "Cannot open audio input stream" 错误

**原因：**
- 系统尝试使用单声道（1通道）模式，而Microsemi DAC需要立体声（2通道）模式
- 设备权限或配置问题
- 初始化失败后缺乏有效的重试机制

### 2. 代码重复和组织问题

**症状：**
- 多个相同功能的文件分散在不同目录
- 模块之间职责不清
- 代码依赖关系混乱

## 三、修改计划

### 第一阶段：修复音频设备初始化

1. **确保使用立体声模式**
   - 修改 `PortAudioAcquisition.kt`，将 `channels` 参数强制设为 2
   - 修改 `LinuxAudioDeviceSelector.kt`，确保ALSA设备配置为立体声

2. **添加错误恢复机制**
   - 在 `PortAudioDevice.kt` 中添加多次重试逻辑
   - 添加错误处理和恢复机制

3. **优化设备权限**
   - 确保应用程序有足够的权限访问音频设备

### 第二阶段：代码清理与优化

1. **删除重复文件**
   - 删除以下文件：
     ```
     voice/hal/PortAudioDevice.kt
     voice/hal/VoiceActivityDetector.kt
     voice/audio/preprocessing/AudioPreprocessor.kt
     voice/hal/AudioPreprocessor.kt
     voice/audio/acquisition/PortAudioAcquisition.kt
     voice/acquisition/portaudio/VoiceActivityDetector.kt
     ```

2. **添加类型别名确保兼容性**
   - 在适当的位置添加`typealias`确保向后兼容

3. **更新导入路径**
   - 检查所有引用已删除文件的导入语句，更新为正确路径

### 第三阶段：优化参数与配置

1. **调整噪音抑制参数**
   - 优化 `VoiceActivityDetector` 中的检测阈值
   - 调整预处理器中的过滤参数

2. **添加调试日志**
   - 在关键位置添加详细日志，便于诊断问题

3. **增强错误恢复能力**
   - 实现自动重连和恢复机制
   - 添加设备状态监控

## 四、执行步骤

1. 修复 `PortAudioAcquisition.kt` 中的通道配置
2. 更新 `LinuxAudioDeviceSelector.kt` 中的设备选择逻辑
3. 增强 `PortAudioDevice.kt` 中的错误处理和恢复机制
4. 删除重复文件
5. 修复导入路径
6. 调整参数配置
7. 添加调试日志和错误恢复
8. 全面测试 