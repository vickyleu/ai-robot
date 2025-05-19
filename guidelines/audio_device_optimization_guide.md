# 音频设备优化与代码库清理指导文件

## 一、文件结构与作用

### 1. 音频设备与采集 (Acquisition & HAL)

| 文件路径 | 文件名 | 作用 |
|---------|------|------|
| `voice/hal/` | `AudioDevice.kt` | 音频设备核心接口定义 (包含播放和录制控制) |
| `voice/acquisition/portaudio/` | `PortAudioDevice.kt` | `AudioDevice` 接口的PortAudio实现，管理底层音频流 |
| `voice/acquisition/portaudio/` | `PortAudioAcquisition.kt` | 实现 `AudioDevice` 接口，负责从 `PortAudioDevice` 采集PCM数据，并通过回调输出 |
| `voice/hal/` | `LinuxAudioDeviceSelector.kt` | Linux系统音频设备选择器，检测和配置ALSA设备 |
| `voice/hal/` | `AlsaStreamInfo.kt` | ALSA流信息数据类 |

### 2. 音频处理流水线 (Processing & Pipeline)

| 文件路径 | 文件名 | 作用 |
|---------|------|------|
| `voice/audio/` | `AudioProcessingPipeline.kt` | **核心音频处理流水线接口**，定义处理各环节、数据结构和诊断 |
| `voice/audio/processing/` | `AudioProcessingManager.kt` | `AudioProcessingPipeline` 的主要实现，协调整个处理流程 |
| `voice/audio/processing/` | `AudioPreprocessor.kt` | `AudioProcessingPipeline` 的实现 (专注于预处理)，进行质量评估、降采样、静音过滤等 |
| `voice/audio/` | `AudioMetrics.kt` | 音频质量指标数据类 |
| `voice/audio/` | `VADMetrics.kt` | VAD指标数据类 |
| `voice/audio/` | `RecognitionMetrics.kt` | 语音识别指标数据类 |
| `voice/util/` | `DiagnosticsCollector.kt` | `AudioProcessingPipeline.Diagnostics` 接口的实现，负责收集和汇总诊断数据 |


### 3. 语音活动检测 (VAD)

| 文件路径 | 文件名 | 作用 |
|---------|------|------|
| `voice/audio/vad/` | `VoiceActivityDetection.kt` | **统一的VAD接口定义** |
| `voice/audio/vad/` | `VoiceActivityDetector.kt` | `VoiceActivityDetection` 接口的实现，识别音频中的人声 |

### 4. 语音识别 (Recognition)

| 文件路径 | 文件名 | 作用 |
|---------|------|------|
| `voice/api/` | `SpeechRecognizerApi.kt` | **统一的语音识别器接口定义** |
| `voice/audio/recognition/` | `VoskSpeechRecognizer.kt` | `SpeechRecognizerApi` 接口的Vosk实现 |

### 5. 语音合成 (Synthesis)

| 文件路径 | 文件名 | 作用 |
|---------|------|------|
| `voice/api/` | `SpeechSynthesizerApi.kt` | 语音合成器接口定义 |
| `voice/synthesis/` | `PiperSpeechSynthesizer.kt` | `SpeechSynthesizerApi` 接口的Piper实现 |


### 6. 语音助手架构 (Voice Assistant)

| 文件路径 | 文件名 | 作用 |
|---------|------|------|
| `voice/api/` | `VoiceAssistantApi.kt` | 语音助手接口定义 |
| `voice/core/service/` | `VoiceAssistant.kt` | `VoiceAssistantApi` 接口的实现，处理唤醒词、命令识别和响应 |
| `voice/core/config/` | `VoiceAssistantConfig.kt` | 语音助手配置数据类 |
| `voice/detector/keyword/` | `KeywordDetector.kt` | 关键词检测器实现 (内部可能使用Vosk) |
| `voice/core/app/` | `VoiceDemoMain.kt` | 语音助手演示应用程序 |

## 二、Microsemi DAC 初始化与稳定性关键实现点

以下是在 `PortAudioDevice.kt` 和 `PortAudioAcquisition.kt` 中针对 Microsemi DAC 和音频稳定性实施的关键策略总结：

### 1. 强制资源释放与权限管理 (PortAudioDevice - initialize)
   - **积极清理**：在初始化开始时，通过 `system` 调用强制终止 `pulseaudio`, `arecord`, `aplay` 等潜在的音频占用进程，并使用 `fuser -k /dev/snd/*` 关闭打开了声卡文件的进程。
   - **设备权限**：执行 `sudo chmod -R 777 /dev/snd/*` 以确保对声卡设备的访问权限。
   - **ALSA配置**：调用 `deviceSelector.fixAlsaConfig()` 来应用简化的、针对性的ALSA配置，例如直接配置 `pcm.!default { type hw card 0 device 0 }`。
   - **模块重载**：在特定失败情况下（如直接 `arecord` 测试失败），尝试卸载 (`rmmod snd_microsemi`) 并重新加载 (`modprobe snd_microsemi`) DAC的内核模块。

### 2. 健壮的PortAudio初始化 (PortAudioDevice - initialize)
   - **多次尝试**：`Pa_Initialize()` 最多尝试2次，并在重试前有延迟。
   - **错误处理**：详细记录 `Pa_Initialize()` 的错误信息。
   - **预先测试**：在 `Pa_Initialize()` 之前，尝试使用 `arecord` 命令直接测试硬件设备 (`hw:0,0`) 的可用性。

### 3. 强制立体声与参数组合 (PortAudioDevice - openInputStream)
   - **强制立体声**：打开输入流时，始终强制使用2个通道 (`actualChannels = 2`)，因为Microsemi DAC需要立体声模式。
   - **参数组合尝试**：尝试多种预定义的参数组合（通道数、采样率、缓冲区大小）来打开输入流，例如优先尝试 `(2, 16000, 256)`。
   - **ALSA预测试**：在尝试每个参数组合打开PortAudio流之前，先用对应参数的 `arecord` 命令测试ALSA设备。

### 4. 流错误恢复与自动静音 (PortAudioDevice - readAudioSuspend)
   - **连续错误检测**：跟踪连续读取错误次数 (`consecutiveErrors`)。
   - **自动恢复尝试**：当连续错误达到阈值 (`maxConsecutiveErrors`) 或特定标志 (`audioReadResetNeeded`) 被设置时，触发 `attemptStreamRecovery()`。
   - **恢复策略**：`attemptStreamRecovery()` 包含多阶段恢复逻辑：
      - 关闭并重新打开流。
      - 延迟并重试。
      - 修复设备权限 (`chmod 666 /dev/snd/*`)。
      - 清理其他音频进程。
      - 尝试卸载/加载声卡模块。
      - 尝试重新初始化整个PortAudio。
      - 尝试 `alsactl -F restore`。
   - **自动静音**：在读取失败或流不可用时，填充静音数据返回，而不是直接返回错误码，以保持音频流的连续性，避免上层应用崩溃。
   - **全局流标志** (`globalStreamActive`)：通过此标志确保全局只有一个活动的PortAudio流（通常是输入流），防止因并发打开多个流（尤其是输入和输出）导致的PortAudio内部状态崩溃或内存损坏。在打开新流之前会检查此标志，如果已激活，则阻止打开新流。`closeStreams` 时会重置此标志。

### 5. 采集逻辑 (PortAudioAcquisition - startCapture)
   - **委托实现**：`PortAudioAcquisition` 将实际的PortAudio操作（如 `readAudioSuspend`）委托给 `PortAudioDevice` 单例。
   - **回调数据**：通过 `onAudioDataReceived` 回调函数将采集到的音频数据传递给上层（如 `AudioProcessingManager`）。
   - **错误处理**：采集循环中包含对读取错误的捕获和延迟重试逻辑。

### 6. 输出流管理 (PortAudioDevice - openOutputStream, play, playAsync)
   - **播放时的流打开**：如果输出流未打开，在播放音频（`play` 或 `playAsync`）时会尝试打开。同样会检查 `globalStreamActive` 标志，如果输入流已激活，则阻止打开输出流以避免冲突。
   - **强制16kHz/立体声**：播放时，打开输出流通常强制使用16kHz采样率和2通道。

## 三、完整解决方案回顾

上述关键点共同构成了针对Microsemi DAC问题的完整解决方案。核心思想是：
1.  **早期清理和配置**：在应用启动和PortAudio初始化阶段，积极清理潜在的冲突，并应用已知良好的ALSA配置。
2.  **健壮的初始化和流打开**：通过多次尝试和参数组合，提高成功打开音频流的概率。
3.  **运行时错误恢复**：在音频读写过程中，通过检测错误、自动恢复机制和填充静音数据，维持应用的稳定性。
4.  **单一活动流原则**：通过 `globalStreamActive` 标志严格控制，确保系统中最多只有一个PortAudio流（通常是输入流）处于活动状态，这是避免PortAudio内部崩溃的关键。

这些措施旨在最大程度上保证在嵌入式Linux环境（特别是使用Microsemi DAC）下音频功能的稳定运行。

## 四、调试与诊断
// ... (以下部分待更新) ...