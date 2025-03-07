# 前端控制界面 (Web Frontend)

## 模块简介

前端控制界面是AI Robot项目的用户交互层，基于Kotlin Multiplatform和React实现的现代化Web应用。提供直观的设备控制界面和实时数据可视化功能。

## 技术栈

### 1. 核心框架
- Kotlin Multiplatform
- React + React Router
- Material UI (MUI)
- Kotlin Wrappers

### 2. 网络通信
- Ktor 客户端
- WebSocket 实时通信
- RESTful API 集成

### 3. 状态管理
- Kotlin Flow
- React Hooks
- 响应式编程

## 主要功能

### 1. 设备管理
- 设备列表和状态监控
- 设备连接和配置
- 实时状态更新
- 设备诊断工具

### 2. 控制面板
- 实时设备控制
- 自定义控制命令
- 动作序列编程
- 紧急停止功能

### 3. 数据可视化
- 实时数据图表
- 状态历史记录
- 性能监控面板
- 日志查看器

### 4. 系统配置
- 用户偏好设置
- 网络配置
- 安全设置
- 系统日志

## 项目结构

```
web/
├── src/
│   ├── jsMain/
│   │   ├── kotlin/
│   │   │   └── com.airobot.web/
│   │   │       ├── components/  # UI组件
│   │   │       ├── pages/       # 页面组件
│   │   │       ├── hooks/       # React Hooks
│   │   │       ├── utils/       # 工具函数
│   │   │       └── Main.kt      # 入口文件
│   │   └── resources/           # 静态资源
│   └── commonMain/               # 跨平台共享代码
└── build.gradle.kts             # 构建配置
```

## 开发环境

### 要求
- Kotlin 2.1.10+
- JDK 17+
- Node.js 18+
- Gradle 8.0+

### 设置步骤

1. 克隆项目：
   ```bash
   git clone <repository-url>
   cd ai-robot
   ```

2. 安装依赖：
   ```bash
   ./gradlew :web:dependencies
   ```

3. 启动开发服务器：
   ```bash
   ./gradlew :web:jsBrowserDevelopmentRun
   ```

## 构建和部署

### 开发环境构建
```bash
./gradlew :web:jsBrowserDevelopmentRun
```

### 生产环境构建
```bash
./gradlew :web:jsBrowserProductionWebpack
```

## 开发指南

### 1. 组件开发
- 使用函数式组件和Hooks
- 遵循Material UI设计规范
- 实现响应式布局
- 添加适当的动画效果

### 2. 状态管理
- 使用Kotlin Flow处理响应式数据
- 实现MVVM架构模式
- 合理使用React Context

### 3. 网络请求
- 使用Ktor客户端
- 处理错误和超时
- 实现请求重试机制

### 4. 性能优化
- 组件懒加载
- 资源按需加载
- 缓存策略
- 代码分割

## 测试

### 单元测试
```bash
./gradlew :web:jsTest
```

### 集成测试
```bash
./gradlew :web:integrationTest
```

## 部署

1. 构建生产版本
2. 配置环境变量
3. 设置API端点
4. 部署到Web服务器

## 注意事项

1. 遵循代码规范和最佳实践
2. 保持组件的可复用性
3. 注意性能优化
4. 确保跨浏览器兼容性

## 文档

- API文档：参考源代码注释
- 组件文档：查看组件目录下的README
- 更新日志：CHANGELOG.md

## 贡献指南

1. 遵循代码规范
2. 提供完整的测试
3. 更新相关文档
4. 提交清晰的PR说明