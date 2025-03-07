# 核心库 (Core Library)

## 模块简介

核心库是共享模块的基础组件，提供了跨平台的基础设施和工具类。该模块采用Kotlin Multiplatform实现，确保在不同平台上的一致性和可靠性。

## 功能特性

### 1. 设备管理
- 统一的设备抽象接口
- 设备生命周期管理
- 设备状态监控
- 设备事件处理

### 2. 工具类
- 日志记录工具
- 错误处理工具
- 数据转换工具
- 配置管理工具

### 3. 扩展函数
- 集合操作扩展
- 字符串处理扩展
- 协程工具扩展
- 时间日期扩展

## 技术实现

### 开发环境要求
- Kotlin 2.1.10+
- JDK 17+
- Gradle 8.0+

### 目录结构
```
core/
├── src/
│   ├── commonMain/     # 跨平台共享代码
│   │   ├── device/    # 设备管理
│   │   ├── utils/     # 工具类
│   │   └── extensions/# 扩展函数
│   ├── jvmMain/       # JVM平台实现
│   └── jsMain/        # JavaScript平台实现
└── test/              # 测试代码
```

## 使用示例

### 1. 设备管理
```kotlin
// 创建设备管理器
val deviceManager = DefaultDeviceManager()

// 注册设备
deviceManager.register(device)

// 监听设备状态
deviceManager.deviceStatus.collect { status ->
    // 处理状态更新
}
```

### 2. 工具类使用
```kotlin
// 日志记录
Logger.info("设备已连接")

// 错误处理
Result.runCatching {
    // 可能抛出异常的代码
}.onSuccess { result ->
    // 处理成功结果
}.onFailure { error ->
    // 处理错误
}
```

### 3. 扩展函数
```kotlin
// 集合操作
val filtered = list.filterNotNull()

// 协程工具
launch {
    withTimeout(5.seconds) {
        // 限时操作
    }
}
```

## 错误处理

### 1. 异常类型
- DeviceException
- ConfigurationException
- TimeoutException
- ValidationException

### 2. 错误恢复
- 重试机制
- 降级策略
- 错误日志记录

## 性能优化

1. 内存管理
   - 对象池复用
   - 内存泄漏检测
   - GC优化

2. 并发处理
   - 协程调度优化
   - 线程池管理
   - 资源竞争控制

## 测试

1. 单元测试
   ```bash
   ./gradlew :shared:core:test
   ```

2. 集成测试
   ```bash
   ./gradlew :shared:core:integrationTest
   ```

## 注意事项

1. 遵循平台无关性原则
2. 正确处理资源释放
3. 避免阻塞操作
4. 合理使用协程

## API文档

详细的API文档请参考源代码注释和生成的文档。

## 版本兼容性

- 保持API稳定性
- 向后兼容支持
- 版本更新指南

## 贡献指南

1. 代码风格规范
2. 测试覆盖要求
3. 文档更新规则
4. 版本号管理