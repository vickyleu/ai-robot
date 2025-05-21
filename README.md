# AI Robot 项目
# 删除 JAR 和 POM 的缓存目录

rm -rf ~/.gradle/caches/modules-2/files-2.1/com.vickyleu.ktor/ktor-client-curl*
# 清理元数据缓存（关键！）
rm -rf ~/.gradle/caches/modules-2/metadata-2.*/descriptors/com.vickyleu.ktor/ktor-client-curl*

./gradlew build --refresh-dependencies --no-build-cache
## 项目简介

AI Robot是一个集成了多种设备控制协议的智能机器人控制系统，通过统一的接口实现对不同类型机器人的控制和管理。

## 系统架构

项目采用分层架构设计，主要包含以下几个核心模块：

### 1. 设备控制层,基于kotlin

#### 1.1 C++扩展桥接（cpp-bridge）
- Kotlin native提供统一的C++设备控制接口
    - 实现ROS机器人控制管理，支持ROS消息发布订阅、服务调用和参数访问
    - 实现Unitree机器人控制，集成SDK实现运动控制和状态管理
- 支持高性能异步操作和实时控制
- 提供完整的错误处理和日志记录机制

#### 1.2 YAN API集成
- 实现YAN协议的设备控制接口
- 提供标准化的设备操作API

#### 1.3 CRUZR机器人控制
- 集成CRUZR专用控制协议, 使用Android SDK
- 实现机器人动作和状态管理

### 2. 后端服务（backend）

#### 2.1 API服务,基于Go
- 提供RESTful接口
- 集成Dify API支持
- 实现设备控制和状态查询接口

#### 2.2 配置管理
- 统一的配置文件管理
- 支持动态配置更新

### 3. 前端控制界面（web）

#### 3.1 技术栈
- React
- Ktor 客户端
- kotlinx.coroutines 协程
- kotlinx.serialization 序列化

#### 3.2 核心功能
- 设备管理界面
- 实时设备控制面板
- 设备参数配置界面
- 实时状态监控和数据可视化

#### 3.3 主要模块
- commonMain: 共享业务逻辑和数据模型
- webMain: Web平台实现

#### 3.4 开发说明
- 使用 React 构建UI
- 采用 MVVM 架构模式
- 使用 Kotlin Flow 处理响应式数据流
- 通过 Ktor 实现网络通信

### 4. 共享模块（shared）

#### 4.1 核心库（core）
- 共享的基础功能实现
- 通用工具类和辅助函数

#### 4.2 通信协议（protocol）
- 统一的设备通信协议定义
- 消息格式和数据结构规范

## 项目结构

```
├── backend/           # Go后端服务
│   ├── api/          # API接口实现
│   ├── config/       # 配置管理
│   └── device/       # 设备控制实现
├── device/           # 设备控制模块
│   ├── cpp-bridge/   # C++扩展桥接
│   │   ├── unitree/  # 宇树机器人控制
│   │   └── ros/      # ROS机器人控制
│   ├── cruzr/        # CRUZR机器人控制
│   └── yan-api/      # YAN API实现
├── shared/           # 共享模块
│   ├── core/         # 核心库
│   └── protocol/     # 通信协议
└── web/             # Web前端项目
    ├── commonMain/   # 共享代码
    └── webMain/      # Web平台代码
```

## 技术栈

- 后端：Go
- 前端：React
- 设备控制：C++、Kotlin
- 构建工具：Gradle
- 网络通信：Ktor
- 状态管理：Kotlin Flow

## 开发指南

### 环境配置
1. 安装必要的开发工具
2. 配置Go开发环境
3. 安装Gradle构建工具

### 构建步骤
1. 克隆代码仓库
2. 执行Gradle构建
3. 启动后端服务
4. 运行前端开发服务器

### 前端开发
1. 安装 Node.js 和 npm
2. 进入 web 目录
3. 执行 `npm install` 安装依赖
4. 执行 `npm start` 启动开发服务器

## 注意事项

1. C++扩展开发需要确保正确配置编译环境
2. 设备控制模块的修改需要经过充分测试
3. API接口变更需要同步更新文档
4. 保持配置文件的同步更新

## 维护说明

1. 定期检查并更新依赖版本
2. 保持文档的及时更新
3. 遵循代码规范和提交规范
4. 做好版本管理和分支管理

# 音频处理链路重构

## 重构内容概述

将语音助手系统的音频处理链路完全重新设计，删除已废弃或停止维护的库（如RNNoise、SpeexDSP、Snowboy），并使用现代化的第三方库（WebRTC APM和Porcupine）替代旧的实现。

## 主要变更

1. **删除过时的库**
   - 移除RNNoise相关实现和引用
   - 移除SpeexDSP相关实现和引用
   - 移除Snowboy相关实现和引用

2. **引入新的音频处理库**
   - 使用WebRTC APM (Audio Processing Module)实现音频处理功能
     - 降噪 (NS)
     - 自动增益控制 (AGC)
     - 高通滤波 (HPF)
     - 回声消除 (AEC)
     - 语音活动检测 (VAD)
   - 使用Porcupine实现关键词检测功能

3. **核心类实现**
   - `WebRtcApm`: 封装WebRTC APM接口
   - `PorcupineKeywordDetector`: 封装Porcupine关键词检测功能
   - `WebRtcApmSingleton`: 提供单例访问管理
   - `AudioProcessingManager`: 重构音频处理管理器
   - `AudioPreprocessor`: 精简的音频预处理器
   - `KeywordDetector`: 重构关键词检测器
   - `VoiceAssistant`: 更新初始化方法以适配新实现

4. **配置更新**
   - 在`VoiceAssistantConfig`中添加`porcupineModelPath`支持Porcupine模型目录
   - 优化程序退出资源清理流程

## 新的音频处理流程

```
采集 → WebRTC APM(NS+AEC+AGC+HPF+VAD) → Porcupine关键词检测 → 语音识别
```

## 优势

1. **稳定性提升**: 使用更可靠的第三方库，减少自定义算法的bug
2. **性能提升**: WebRTC APM经过大量优化，能在资源有限的设备上高效运行
3. **功能增强**: 提供更先进的降噪和VAD功能
4. **维护性**: 依赖活跃维护的开源项目，能持续获得更新和优化
5. **代码简洁**: 减少重复代码，清晰的接口定义

## 后续优化方向

1. 进一步优化内存使用，减少不必要的音频数据复制
2. 继续清理残留代码
3. 添加更多配置选项，允许自定义WebRTC APM参数
4. 支持更多种类的关键词和模型
