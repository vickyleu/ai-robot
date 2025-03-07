# AI Generation 服务

## 项目概述

本项目是一个基于Go语言开发的AI服务中间层，用于处理客户端请求并与Dify API进行交互。项目实现了高性能的并发处理、超时控制和错误重试机制。项目使用Go 1.22版本开发。

## 系统架构

```
客户端 -> Go服务 (HTTP: /completion, gRPC: CompletionService) -> Dify API (/v1/chat-messages)
```

### 核心组件

1. **API服务层**
   - 路径：`/internal/api`
   - 处理HTTP请求（默认端口：8080）
   - 实现API接口路由和中间件

2. **gRPC服务层**
   - 路径：`/internal/grpc`
   - 处理gRPC请求（默认端口：50051）
   - 提供高性能RPC服务

3. **服务层**
   - 路径：`/internal/service`
   - 实现业务逻辑
   - 处理AI请求响应

4. **配置层**
   - 路径：`/internal/config`
   - 管理系统配置
   - 环境变量处理

5. **类型定义**
   - 路径：`/internal/types`
   - 定义系统通用类型
   - 请求响应结构

6. **Dify客户端**
   - 路径：`/pkg/dify`
   - 封装Dify API调用
   - 实现重试机制和超时控制

## 服务配置

### 环境变量

- `SERVER_HOST`: 服务监听地址（默认：localhost）
- `SERVER_HTTP_PORT`: HTTP服务端口（默认：8080）
- `SERVER_GRPC_PORT`: gRPC服务端口（默认：50051）
- `DIFY_API_KEY`: Dify API密钥
- `DIFY_API_ENDPOINT`: Dify API地址

## API接口

### HTTP完成请求 (Completion)

- **端点**：`/completion`
- **方法**：POST
- **请求体**：
  ```json
  {
    "query": "用户输入",
    "inputs": {},
    "user": "用户ID",
    "response_mode": "blocking"
  }
  ```

- **响应格式**：
  ```json
  {
    "code": 200,
    "msg": "success",
    "data": { "content": "这里是机器人的回答文本",
              "functions": [
                         { "action": "handsup", "delay": 0 },
                         { "action": "voice", "params": "语音内容", "delay": 500 },
                         { "action": "handsdown", "delay": 1000 }
              ]
    }
  }
  ```

### gRPC完成请求

- **服务**：`CompletionService`
- **方法**：`GetCompletion`
- **请求消息**：
  ```protobuf
  message CompletionRequest {
    string query = 1;
    map<string, string> inputs = 2;
    string user = 3;
    string response_mode = 4;
  }
  ```

- **响应消息**：
  ```protobuf
  message CompletionResponse {
    int32 code = 1;
    string msg = 2;
    CompletionData data = 3;
  }

  message CompletionData {
    string event = 1;
    string task_id = 2;
    string id = 3;
    string message_id = 4;
    string conversation_id = 5;
    string mode = 6;
    string answer = 7;
    string created_at = 8;
    map<string, string> metadata = 9;
  }
  ```

### 错误处理

所有API响应都遵循统一的格式：

```json
{
  "code": 500,
  "msg": "错误信息",
  "data": null
}
```

常见错误码：
- 400: 请求参数错误
- 401: 未授权
- 403: 禁止访问
- 404: 资源不存在
- 500: 服务器内部错误
- 503: 服务暂时不可用

## 特性

1. **高性能** ✓
   - 使用goroutine处理并发请求
   - 实现请求限流控制
   - 连接池优化
   - 使用高性能JSON库（bytedance/sonic）

2. **可靠性** ✓
   - 请求重试机制
   - 超时控制
   - 错误处理
   - 优雅关闭

3. **扩展性** ✓
   - 模块化设计
   - 接口抽象
   - 配置灵活
   - 中间件支持

## 待开发功能

1. **Redis缓存层** □
   - 实现请求结果缓存
   - 支持缓存过期策略
   - 缓存预热机制
   - 分布式锁实现

2. **监控系统** □
   - Prometheus指标收集
   - Grafana仪表盘
   - 性能指标监控
   - 告警配置

3. **分布式追踪** □
   - OpenTelemetry集成
   - 请求链路追踪
   - 性能瓶颈分析
   - 日志聚合

4. **可靠性增强** □
   - 熔断器机制
   - 服务降级策略
   - 负载均衡
   - 限流优化

5. **配置管理** □
   - 配置中心集成
   - 动态配置更新
   - 多环境配置
   - 敏感信息加密

## 部署

### Docker部署

1. 构建镜像：
   ```bash
   docker build -t ai-generation .
   ```

2. 运行容器：
   ```bash
   docker run -d \
     -p 8080:8080 \
     -p 50051:50051 \
     -e DIFY_API_KEY=your_api_key \
     -e DIFY_API_ENDPOINT=your_api_endpoint \
     ai-generation
   ```

### 本地部署

1. 构建服务：
   ```bash
   go build -o ai-service ./cmd/server
   ```

2. 运行服务：
   ```bash
   ./ai-service
   ```

## 测试

提供了测试脚本：

- `test_api.sh`: 测试HTTP API
- `test_api_with_progress.sh`: 测试带进度的HTTP API
- `test_grpc_api.sh`: 测试gRPC API
- `test_grpc_api_with_progress.sh`: 测试带进度的gRPC API

运行测试前请确保：
1. 服务已正常启动
2. 环境变量已正确配置
3. 测试脚本有执行权限

## 贡献指南

1. Fork 项目
2. 创建特性分支
3. 提交更改
4. 推送到分支
5. 创建 Pull Request

## 许可证

MIT License