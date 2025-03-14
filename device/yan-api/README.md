# YAN API模块 (YAN API Module)

## 模块简介

YAN API模块是设备控制层的标准化接口实现，提供了与YAN协议兼容的机器人控制接口。该模块封装了底层通信细节，提供高级别的设备控制和状态管理功能。
https://yandev.ubtrobot.com/#/zh/api?api=YanAPI 接口文档
本地接口文档在src/nativeMain/resources/README.md

通过将yan 的python模块生成cython, 当前模块改为C++实现, 头文件为src/pythonMain/python/cpp/YanAPI.h
原restful接口文档在src/nativeMain/resources/README.md
部分接口名会带_value后缀, 比原文档多一个后缀, 请以c++文档为准



## 功能特性

### 1. 设备控制
- 运动控制命令
- 姿态调整
- 传感器数据读取
- 设备状态管理

### 2. 通信协议
- YAN协议实现
- 消息序列化和反序列化
- 通信加密和安全
- 错误处理和重试机制

### 3. 状态管理
- 设备状态监控
- 实时数据更新
- 事件通知系统
- 异常状态处理

## 技术实现

### 开发环境要求
- Kotlin 2.1.10+
- JDK 17+
- YAN SDK

### 目录结构
```
yan-api/
├── src/
│   └── nativeMain/    # Native平台实现
└── test/              # 测试代码
```

## 使用示例

### 1. 初始化设备
```kotlin
val device = YanDevice.create()
device.connect("192.168.1.100")
```

### 2. 发送控制命令
```kotlin
device.move(Movement(
    direction = Direction.FORWARD,
    speed = 0.5f
))
```

### 3. 状态监听
```kotlin
device.status.collect { status ->
    // 处理状态更新
}
```

## 错误处理

### 1. 异常类型
- 连接异常
- 通信超时
- 协议错误
- 设备错误

### 2. 错误恢复
- 自动重连机制
- 错误重试策略
- 降级处理方案

## 性能优化

1. 通信优化
   - 消息缓冲和批处理
   - 异步通信处理
   - 连接池管理

2. 资源管理
   - 内存使用优化
   - 连接资源管理
   - 线程池配置

## 安全性

1. 通信安全
   - 数据加密
   - 身份认证
   - 访问控制

2. 操作安全
   - 命令验证
   - 权限检查
   - 操作审计

## 注意事项

1. 正确配置网络环境
2. 遵循设备操作规范
3. 注意并发操作处理
4. 保持与设备固件版本兼容

## API文档

详细的API文档请参考源代码注释和生成的文档。

## 版本兼容性

- 支持YAN协议1.0及以上版本
- 向后兼容性保证
- 版本升级指南

## 贡献指南

1. 代码风格规范
2. 测试覆盖要求
3. 文档更新规则
4. 版本号管理