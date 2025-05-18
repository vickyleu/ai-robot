# Microsemi DAC 配置与使用指南

本指南为树莓派上使用Microsemi DAC进行音频录制和播放提供解决方案。

## 问题概述

Microsemi DAC设备要求使用立体声（2通道）模式进行音频录制和播放，单声道模式将导致初始化失败。

常见错误：
- "PortAudio not initialized"
- "Cannot open audio input stream"
- "Device unavailable"

## 解决方案

我们已对代码进行了以下优化：

1. 强制使用立体声（2通道）模式
2. 优化ALSA配置
3. 增强错误恢复能力
4. 改进日志记录

## 使用步骤

### 1. 设备初始化

在启动应用前，建议先运行初始化脚本：

```bash
sudo ./scripts/init_microsemi_dac.sh
```

该脚本会执行以下操作：
- 停止占用音频设备的进程
- 重新加载Microsemi声卡模块
- 设置正确的设备权限
- 创建优化的ALSA配置
- 测试设备可用性

### 2. 启动应用

启动应用时，确保音频设备未被其他进程占用：

```bash
./gradlew run
```

### 3. 疑难解答

如果遇到问题，请执行以下步骤：

#### 检查设备状态
```bash
sudo ./device/yan-api/src/scripts/check_microsemi_dac.sh
```

这将生成详细的诊断信息，包括：
- 已加载的声卡模块
- 系统声卡信息
- 音频设备权限
- ALSA设备配置
- 设备可用性测试

#### 手动修复
如果诊断脚本显示设备有问题，可运行修复脚本：
```bash
sudo ./device/yan-api/src/scripts/fix_microsemi_dac.sh
```

## 技术细节

### ALSA配置优化

我们使用了以下ALSA配置，直接访问硬件设备并强制使用立体声模式：

```
pcm.!default {
    type hw
    card 0
    device 0
    format S16_LE
    channels 2
    rate 16000
}

ctl.!default {
    type hw
    card 0
}
```

### 应用程序参数修改

应用程序中已经进行了以下修改：
- 强制把单声道音频转换为立体声
- 改进了设备错误恢复机制
- 增加了设备初始化的重试逻辑
- 提供了更详细的错误日志

## 常见问题

1. **设备无法访问**
   - 确保设备权限正确：`sudo chmod -R 777 /dev/snd/*`
   - 检查模块是否加载：`lsmod | grep snd_microsemi`

2. **播放或录制静音**
   - 检查音量设置：`alsamixer`
   - 尝试不同采样率：8kHz或16kHz

3. **应用崩溃**
   - 检查日志文件中的错误信息
   - 尝试完全重新初始化音频子系统

## 参考资料

- [PortAudio文档](http://www.portaudio.com/docs.html)
- [ALSA配置指南](https://www.alsa-project.org/wiki/Main_Page)
- [树莓派音频配置](https://www.raspberrypi.org/documentation/configuration/audio-config.md) 