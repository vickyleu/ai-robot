# Cruzr 设备模块

## 模块简介

Cruzr设备模块是AI Robot项目中负责与Cruzr机器人进行交互的核心组件。该模块提供了与Cruzr机器人硬件通信的接口实现，支持动作控制、状态监控等功能。

## 目录结构

```
cruzr/
├── src/
│   ├── androidMain/          # Android平台特定实现
│   │   └── kotlin/
│   │       └── com.airobot.device.cruzr/
│   │           ├── bridge/   # 原生SDK桥接层
│   │           └── impl/     # Android平台具体实现
│   └── commonMain/           # 跨平台共享代码
│       └── kotlin/
│           └── com.airobot.device.cruzr/
│               ├── api/      # 公共API接口
│               ├── model/    # 数据模型
│               └── util/     # 工具类
├── build.gradle.kts          # 构建配置
└── README.md                 # 本文档
```

## 主要功能

1. 设备连接管理
   - 初始化设备连接
   - 管理连接状态
   - 处理连接异常

2. 动作控制
   - 基础动作指令
   - 复杂动作序列
   - 实时动作反馈

3. 状态监控
   - 设备状态查询
   - 传感器数据获取
   - 错误状态处理

## 开发指南

### 环境要求
- Android Studio 4.2+
- Kotlin 1.8+
- Gradle 8.0+

### 构建说明
1. 在Android Studio中打开项目
2. 同步Gradle项目
3. 运行或调试应用

## 注意事项
- 确保Android SDK和Cruzr SDK版本兼容
- 正确处理设备连接和断开事件
- 遵循Cruzr设备操作规范

## 错误处理

1. 连接错误
   - 网络连接失败
   - 设备未响应
   - 认证失败

2. 操作错误
   - 非法动作指令
   - 设备状态异常
   - 超时处理

## 注意事项

1. 初始化顺序
   - 确保在使用设备功能前完成初始化
   - 检查必要权限是否已授予

2. 资源管理
   - 及时释放不需要的资源
   - 正确处理生命周期事件

3. 异常处理
   - 实现适当的错误恢复机制
   - 添加必要的日志记录

## 测试

1. 单元测试
   ```bash
   ./gradlew :device:cruzr:test
   ```

2. 集成测试
   ```bash
   ./gradlew :device:cruzr:connectedAndroidTest
   ```

## 维护者

- 项目维护团队
- 技术支持邮箱

## 更新日志

请参考CHANGELOG.md文件