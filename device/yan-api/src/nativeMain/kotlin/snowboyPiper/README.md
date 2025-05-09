# 音频处理库优化说明

## 单例模式优化

为了优化音频处理性能和资源使用，我们对RNNoise和Soxr这两个重要的音频处理库进行了单例模式的重构。这种优化有以下几点好处：

1. **减少资源创建和销毁的开销**：通过单例模式缓存实例，避免频繁创建和销毁实例
2. **提高内存使用效率**：避免重复创建相同配置的实例
3. **改善性能**：减少资源初始化时间，尤其是在频繁处理音频数据的场景下
4. **简化代码**：统一资源管理，使代码更易维护
5. **防止资源泄露**：通过统一的资源管理器确保资源正确释放

## 主要改进

### 1. 实现单例模式

创建了两个单例类来分别管理RNNoise和Soxr资源：

- **RNNoiseSingleton**：管理RNNoise实例，提供统一的音频处理接口
- **SoxrSingleton**：管理Soxr实例，根据不同的采样率配置缓存不同的实例

### 2. 引入引用计数机制

为了更精确地管理资源生命周期，引入了引用计数机制：

- 每次获取实例时增加引用计数
- 用完实例后减少引用计数
- 当引用计数为0且超过一定时间未使用时，释放资源

### 3. 资源超时释放

实现了资源超时释放机制：

- 设置了30秒的默认超时时间
- 跟踪资源最后使用时间
- 当资源长时间未使用且引用计数为0时自动释放

### 4. 统一资源管理

通过`AudioProcessingResourceManager`实现全局资源管理：

- 使用 C 标准库的 `atexit` 函数注册程序退出清理钩子
- 提供手动释放资源的接口
- 集中管理所有音频处理资源

### 5. 线程安全

通过同步机制确保单例模式的线程安全：

- 使用`synchronized`块保护关键操作
- 使用原子变量进行引用计数

## 使用方法

1. 应用程序启动时初始化资源管理器：
   ```kotlin
   // 在应用程序入口点
   AudioProcessingResourceManager.registerShutdownHook()
   ```

2. 使用RNNoise进行音频处理：
   ```kotlin
   // 直接使用单例处理音频
   val processResult = RNNoiseSingleton.process(
       inputBuffer,
       outputBuffer,
       frameCount,
       vadProbabilitiesPtr,
       maxVadValues,
       vadThreshold,
       gain
   )
   ```

3. 使用Soxr进行重采样：
   ```kotlin
   // 直接使用单例进行重采样
   val result = SoxrSingleton.process(
       sampleRate.toDouble(), 
       requiredSampleRate.toDouble(),
       inputBuffer,
       frameCount.toUInt(),
       outputBuffer,
       outputSize.toUInt()
   )
   ```

4. 应用程序关闭时释放资源：
   ```kotlin
   // 在应用程序退出前
   AudioProcessingResourceManager.releaseAllResources()
   ```

## Kotlin Native 特有的资源管理

在 Kotlin Native 环境中，我们需要使用 C 标准库的方法来处理程序退出时的资源清理：

1. **使用 `platform.posix.atexit` 注册退出回调**：
   ```kotlin
   platform.posix.atexit {
       // 清理资源
       releaseAllResources()
       0 // C函数返回值
   }
   ```

2. **手动触发垃圾回收**：
   ```kotlin
   // 强制垃圾回收
   kotlin.native.internal.GC.collect()
   ```

3. **避免循环引用**：在 Kotlin Native 中，需要特别注意避免循环引用导致的内存泄漏。

4. **线程安全考虑**：Kotlin Native 的内存模型与 JVM 不同，需要特别注意线程安全。

## 性能优化建议

1. 尽量复用音频处理结果，避免重复处理
2. 在处理大量音频数据时，考虑批处理而非逐帧处理
3. 根据实际需求调整资源超时时间
4. 在高并发场景下，考虑针对 Kotlin Native 的线程模型调整同步策略
5. 对于频繁使用的配置，考虑增加预热机制，提前初始化单例 