# 共享模块 (Shared Module)

## 模块简介

共享模块是 AI Robot 项目的基础支持层，提供跨平台的核心功能和通用工具。该模块采用 Kotlin Multiplatform 技术实现，确保代码在不同平台上的复用性和一致性。

## 子模块说明

### 1. 核心库（core）
- 基础数据结构和工具类
- 平台无关的业务逻辑实现
- 通用扩展函数和工具方法
- 错误处理和日志记录框架

### 2. 通信协议（protocol）
- 统一的设备通信协议定义
- 消息序列化和反序列化
- 数据模型和传输格式规范
- 协议版本管理和兼容性处理

## 技术特性

- 使用 Kotlin Multiplatform 实现跨平台支持
- kotlinx.serialization 用于数据序列化
- kotlinx.coroutines 处理异步操作
- 统一的错误处理和日志记录

## 开发指南

### 环境要求
- Kotlin 2.1.10 或更高版本
- JDK 17 或更高版本
- Gradle 8.0 或更高版本

### 构建说明
1. 在项目根目录执行：
   ```bash
   ./gradlew :shared:build
   ```

### 测试
- 运行所有测试：
  ```bash
  ./gradlew :shared:allTests
  ```
- 特定平台测试：
  ```bash
  ./gradlew :shared:jvmTest    # JVM 平台测试
  ./gradlew :shared:jsTest     # JavaScript 平台测试
  ./gradlew :shared:nativeTest # Native 平台测试
  ```

## 使用指南

### 1. 核心库使用
```kotlin
import com.airobot.shared.core.utils.*
import com.airobot.shared.core.models.*

// 使用工具类
val result = SafeOperation.execute {
    // 你的代码
}

// 使用数据模型
val deviceStatus = DeviceStatus(
    id = "device_001",
    status = Status.ONLINE
)
```

### 2. 协议使用
```kotlin
import com.airobot.shared.protocol.*

// 创建消息
val message = ControlMessage(
    type = MessageType.COMMAND,
    payload = CommandPayload(...)
)

// 序列化
val json = message.toJson()

// 反序列化
val parsed = ControlMessage.fromJson(json)
```

## 注意事项

1. 所有共享代码应该保持平台无关性
2. 需要平台特定实现时，使用 expect/actual 声明
3. 保持向后兼容性，特别是协议更新时
4. 确保适当的单元测试覆盖率

## 文档

- [核心库 API 文档](core/README.md)
- [协议规范文档](protocol/README.md)
- [版本变更记录](CHANGELOG.md)

## 贡献指南

1. 遵循 Kotlin 编码规范
2. 提供完整的单元测试
3. 更新相关文档
4. 保持向后兼容性 