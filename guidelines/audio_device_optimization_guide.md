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


## 二、Microsemi DAC问题解决方案

### 核心问题
Microsemi DAC要求立体声（2通道）模式进行录制和播放，但代码尝试使用单声道，导致初始化失败。

### 修复方法

1. **强制使用立体声模式**
   ```kotlin
   // 在PortAudioDevice.kt和PortAudioAcquisition.kt中强制使用2通道
   val actualChannels = 2 // 强制使用立体声，无论请求的是什么
   ```

2. **直接资源释放**
   ```kotlin
   // 在initialize()方法中添加以下代码
   system("sudo pkill -9 pulseaudio 2>/dev/null || true")
   system("sudo pkill -9 arecord 2>/dev/null || true")
   system("sudo pkill -9 aplay 2>/dev/null || true")
   system("sudo fuser -k /dev/snd/* 2>/dev/null || true")
   system("sudo chmod -R 777 /dev/snd/* 2>/dev/null || true")
   ```

3. **简化ALSA配置**
   ```kotlin
   // 直接在代码中创建最简化配置
   val file = fopen(asoundrcPath, "w")
   if (file != null) {
       fputs("pcm.!default { type hw card 0 device 0 }\n", file)
       fputs("ctl.!default { type hw card 0 }\n", file)
       fclose(file)
   }
   
   // 应用配置
   system("sudo alsactl kill rescan 2>/dev/null || true")
   system("sudo alsactl init 2>/dev/null || true")
   system("sudo alsactl store 2>/dev/null || true")
   system("sudo alsactl -F restore 2>/dev/null || true")
   ```

4. **多参数组合尝试**
   ```kotlin
   // 尝试多种参数组合
   val paramCombinations = listOf(
       Triple(2, sampleRate, 256),   // 原始请求采样率，立体声
       Triple(2, 16000, 256),        // 立体声, 16kHz, 256帧
       Triple(2, 8000, 128),         // 立体声, 8kHz, 128帧
       Triple(2, 16000, 512),        // 立体声, 16kHz, 512帧
       Triple(2, 16000, 1024)        // 立体声, 16kHz, 1024帧
   )
   ```

5. **标准输出日志**
   ```kotlin
   // 在关键点添加标准输出打印，确保总能看到日志
   logger.info("正在执行Pa_Initialize()...尝试 #$initAttempt")
   println("正在执行Pa_Initialize()...尝试 #$initAttempt")
   ```

6. **错误恢复机制**
   ```kotlin
   // 添加自动重试逻辑
   var initAttempt = 0
   val maxInitAttempts = 8
   
   while (!initSuccess && initAttempt < maxInitAttempts) {
       // 重试逻辑
   }
   ```

## 三、完整解决方案

### 1. PortAudioDevice.kt

1. 在`initialize()`方法中：
   - 加入强制资源释放代码
   - 创建最简化ALSA配置
   - 添加标准输出日志
   - 修复参数组合尝试
   - 增强错误恢复机制

2. 在`openInputStream()`方法中：
   - 强制使用立体声模式
   - 添加多种参数组合
   - 改进错误处理

### 2. PortAudioAcquisition.kt

1. 在`initialize()`方法中：
   - 释放已占用资源
   - 强制使用立体声模式
   - 添加标准输出日志

2. 在`captureLoop()`方法中：
   - 添加强制设备释放
   - 改进设备访问策略

### 3. LinuxAudioDeviceSelector.kt

- 简化`isRaspberryPi()`方法，直接返回true
- 优化`fixAlsaConfig()`方法，使用最简配置

## 四、调试与诊断

### 代码层诊断方法

1. **添加标准输出调试**
   ```kotlin
   println("步骤X: 详细描述...")
   ```

2. **保存设备状态信息**
   ```kotlin
   system("arecord -l > /tmp/arecord_devices_list.txt 2>&1")
   system("cat /proc/asound/cards > /tmp/asound_cards.txt 2>&1")
   system("ls -la /dev/snd > /tmp/snd_devices.txt 2>&1")
   ```

3. **直接测试设备**
   ```kotlin
   val testCmd = "arecord -d 1 -f S16_LE -r 16000 -c 2 -D hw:0,0 /dev/null 2>/tmp/arecord_test.log"
   val testResult = system(testCmd)
   ```

### 关键点日志

确保在以下关键点添加详细日志：
1. 初始化开始
2. 音频资源释放
3. ALSA配置创建
4. PortAudio初始化尝试
5. 流打开尝试
6. 音频采集状态

## 五、执行步骤

1. 更新`PortAudioDevice.kt`中的初始化逻辑
2. 更新`PortAudioAcquisition.kt`中的采集逻辑
3. 简化`LinuxAudioDeviceSelector.kt`
4. 确保添加必要的导入声明：
   ```kotlin
   import platform.posix.fopen
   import platform.posix.fputs
   import platform.posix.fclose
   import platform.posix.getenv
   ```

## 六、最终验证

成功初始化的特征：
1. PortAudio初始化成功日志
2. 标准音频输入流成功打开
3. 采集循环正常运行
4. 没有设备忙或资源不可用的错误消息 