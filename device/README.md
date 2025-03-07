# 设备控制层 (Device Control Layer)

## 模块简介

设备控制层是 AI Robot 项目的核心组件之一，负责与各种机器人设备进行直接通信和控制。该层基于 Kotlin 实现，提供统一的设备控制接口，支持多种机器人平台和协议。

## 子模块说明

### 1. C++扩展桥接（cpp-bridge）
- 基于 Kotlin/Native 实现的 C++ 接口封装
- 提供 ROS（Robot Operating System）控制接口
- 支持底层硬件访问和控制

### 2. YAN API 集成（yan-api）
- 实现 YAN 协议的设备控制接口
- 提供标准化的设备操作 API
- 支持设备状态监控和管理

### 3. CRUZR 机器人控制（cruzr）
- 集成 CRUZR 机器人专用控制协议
- 基于 Android SDK 实现
- 提供完整的机器人动作和状态管理

## 技术特性

- 基于 Kotlin Multiplatform 实现跨平台支持
- 使用协程处理异步操作
- 支持实时数据流处理
- 提供统一的错误处理机制

## 开发指南

### 环境要求
- JDK 17 或更高版本
- Android SDK（用于 CRUZR 控制）
- C++ 开发环境（用于 ROS 集成）
- Kotlin Multiplatform 开发环境

### 构建说明
1. 确保已安装所有必要的开发工具
2. 在项目根目录执行：
   ```bash
   ./gradlew :device:build
   ```

### 模块测试
- 单元测试：`./gradlew :device:test`
- 集成测试：`./gradlew :device:integrationTest`

## 注意事项

1. C++ 桥接模块需要正确配置本地开发环境
2. CRUZR 控制模块需要 Android SDK 支持
3. 所有设备操作都应该通过统一接口进行
4. 确保正确处理设备连接和断开事件

## API 文档

详细的 API 文档请参考各子模块的具体实现：
- [cpp-bridge API 文档](cpp-bridge/README.md)
- [yan-api API 文档](yan-api/README.md)
- [cruzr API 文档](cruzr/README.md) 