// WebRtcApmSingleton ‑ 支持按 (sampleRate, channels) 缓存多实例
@file:OptIn(ExperimentalForeignApi::class)

package voice.audio.processing

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.datetime.Clock.System
import voice.util.LogManager
import voice.util.AudioDefaults
import kotlin.concurrent.Volatile

private typealias Key = Pair<Int, Int> // (sampleRate, channels)

/**
 * WebRTC APM单例管理器
 * 根据不同的采样率和通道数缓存多个实例
 * 采用严格的线程安全设计，确保在并发环境下可靠运行
 */
object WebRtcApmSingleton {
    private val logger = LogManager.getLogger("WebRtcApmSingleton")
    private val lock = SynchronizedObject()

    // 按 (采样率, 通道) 缓存的 APM 实例
    @Volatile
    private var instanceMap = mutableMapOf<Key, WebRtcApm>()
    
    // 实例状态跟踪 (key -> 最后使用时间戳)
    private val instanceTimestamps = mutableMapOf<Key, Long>()
    
    // 正在处理的采样率通道追踪，用于调试
    private val activeProcessingKeys = mutableSetOf<Key>()
    
    /**
     * 获取APM实例 - 仅在非回调线程中使用
     * @param sampleRate 采样率
     * @param channels 通道数
     * @param forceRecreate 如果参数不匹配是否重新创建
     * @return WebRtcApm实例或null
     */
    suspend fun getInstance(
        sampleRate: Int = AudioDefaults.INPUT_DEVICE_SAMPLE_RATE,
        channels: Int = AudioDefaults.INPUT_DEVICE_CHANNELS,
        forceRecreate: Boolean = false
    ): WebRtcApm? {
        val key: Key = sampleRate to channels
        
        // 使用同步块获取和更新实例
        return synchronized(lock) {
            var instance = instanceMap[key]

            if (instance == null) {
                // 不存在就创建新的
                instance = createNewInstance(sampleRate, channels)
                if (instance != null) {
                    instanceMap[key] = instance
                    instanceTimestamps[key] = System.now().toEpochMilliseconds()
                    logger.info("创建并缓存了新的APM实例: $sampleRate Hz, $channels 通道")
                }
            } else if (forceRecreate) {
                // 需要重建 - 验证实例的实际参数
                val currentRate = instance.getActualInputSampleRate()
                val currentChannels = instance.getInputChannels()
                
                if (currentRate != sampleRate || currentChannels != channels) {
                    logger.info("参数不匹配，重新创建APM实例: $currentRate->$sampleRate Hz, $currentChannels->$channels 通道")
                    try {
                        instance.release()
                    } catch (e: Exception) {
                        logger.error("释放旧APM实例失败: ${e.message}")
                    }
                    
                    instance = createNewInstance(sampleRate, channels)
                    if (instance != null) {
                        instanceMap[key] = instance
                        instanceTimestamps[key] =System.now().toEpochMilliseconds()
                    } else {
                        // 创建失败，移除无效条目
                        instanceMap.remove(key)
                        instanceTimestamps.remove(key)
                    }
                } else {
                    // 更新时间戳
                    instanceTimestamps[key] =System.now().toEpochMilliseconds()
                }
            } else {
                // 更新参数但不重建
                try {
                    instance.updateInputParameters(sampleRate, channels)
                    // 更新时间戳
                    instanceTimestamps[key] =System.now().toEpochMilliseconds()
                } catch (e: Exception) {
                    logger.error("更新APM参数失败: ${e.message}")
                }
            }

            instance
        }
    }
    
    /**
     * 获取APM实例 - 线程安全版本，适用于回调线程
     * 只读取已有实例，不创建新实例，避免在回调线程中锁定
     * 
     * @param sampleRate 采样率 
     * @param channels 通道数
     * @return WebRtcApm实例或null
     */
    suspend fun getInstanceThreadSafe(
        sampleRate: Int = AudioDefaults.INPUT_DEVICE_SAMPLE_RATE,
        channels: Int = AudioDefaults.INPUT_DEVICE_CHANNELS
    ): WebRtcApm? {
        val key: Key = sampleRate to channels
        
        // 线程安全地检查key是否存在
        val hasKey = synchronized(lock) {
            if (instanceMap.containsKey(key)) {
                // 更新时间戳但不获取锁太久
                instanceTimestamps[key] =System.now().toEpochMilliseconds()
                true
            } else {
                false
            }
        }
        
        if (!hasKey) {
            // 如果缓存中没有该key，尝试创建 - 但放在锁外面
            val created = getInstance(sampleRate, channels)
            if (created == null) {
                logger.warn("无法为(${sampleRate}Hz, ${channels}ch)创建APM实例")
            }
            return created
        }
        
        // 从map获取实例 - 在有可能并发修改的情况下
        try {
            return instanceMap[key]
        } catch (e: Exception) {
            logger.error("线程安全地获取实例失败: ${e.message}")
            // 发生异常后尝试创建新实例
            return synchronized(lock) {
                createNewInstance(sampleRate, channels)?.also { 
                    instanceMap[key] = it
                    instanceTimestamps[key] =System.now().toEpochMilliseconds()
                }
            }
        }
    }

    /**
     * 创建新实例 - 仅在同步块内部使用
     * 
     * @param sampleRate 采样率
     * @param channels 通道数
     * @return 新创建的WebRtcApm实例或null
     */
    private suspend fun createNewInstance(sampleRate: Int, channels: Int): WebRtcApm? {
        try {
            val newInstance = WebRtcApm()
            if (newInstance.initialize(sampleRate, channels)) {
                logger.info("创建新的WebRTC APM实例: 采样率=$sampleRate Hz, 通道数=$channels")
                
                // 设置VAD参数 - 使用AudioDefaults常量配置
                newInstance.setVadThreshold(AudioDefaults.VAD_THRESHOLD)
                newInstance.setVadDebounceFrames(AudioDefaults.VAD_DEBOUNCE_FRAMES)
                
                return newInstance
            }
            
            // 初始化失败，释放资源
            try {
                newInstance.release()
            } catch (e: Exception) {
                logger.error("释放失败的APM实例时出错: ${e.message}")
            }
            
            logger.error("WebRTC APM实例初始化失败")
            return null
        } catch (e: Exception) {
            logger.error("创建WebRTC APM实例异常: ${e.message}")
            return null
        }
    }

    /**
     * 为所有实例启用/禁用回声消除
     * 
     * @param enable 是否启用回声消除
     */
    fun enableEchoCancellation(enable: Boolean) {
        if (enable && AudioDefaults.ENABLE_ECHO_CANCELLATION_SAFE_MODE) {
            // 安全模式下拒绝启用回声消除
            logger.error("🚫 单例模式：拒绝启用回声消除（安全模式）")
            logger.error("🚫 配置：ENABLE_ECHO_CANCELLATION_SAFE_MODE = ${AudioDefaults.ENABLE_ECHO_CANCELLATION_SAFE_MODE}")
            logger.info("单例模式：回声消除保持禁用状态")
            return
        }
        
        val instances = synchronized(lock) {
            instanceMap.values.toList() // 创建副本以避免长时间持有锁
        }
        
        // 在锁外操作，防止长时间阻塞
        instances.forEach { 
            try {
                it.enableEchoCancellation(enable)
            } catch (e: Exception) {
                logger.error("设置回声消除失败: ${e.message}")
            }
        }
        
        logger.info("单例模式：回声消除已${if (enable) "启用" else "禁用"} (${instances.size}个实例)")
    }

    /**
     * 清理超时的实例
     * @param maxAgeMs 最大不活跃时间（毫秒）
     */
    fun cleanupInactiveInstances(maxAgeMs: Long = 60000) {
        val now =System.now().toEpochMilliseconds()
        val keysToRemove = mutableListOf<Key>()
        
        synchronized(lock) {
            // 找出超时的键
            for ((key, timestamp) in instanceTimestamps) {
                if (now - timestamp > maxAgeMs) {
                    keysToRemove.add(key)
                }
            }
            
            // 释放和移除超时实例
            for (key in keysToRemove) {
                val instance = instanceMap.remove(key)
                instanceTimestamps.remove(key)
                
                try {
                    instance?.release()
                    logger.info("释放不活跃的APM实例: $key")
                } catch (e: Exception) {
                    logger.error("释放不活跃实例出错: $key, ${e.message}")
                }
            }
        }
    }

    /**
     * 释放所有资源
     */
    fun release() {
        val instancesToRelease = synchronized(lock) {
            val instances = instanceMap.values.toList() // 创建副本
            instanceMap.clear()
            instanceTimestamps.clear()
            activeProcessingKeys.clear()
            instances
        }
        
        // 在锁外释放实例，避免长时间持有锁
        var releaseCount = 0
        instancesToRelease.forEach { 
            try {
                it.release()
                releaseCount++
            } catch (e: Exception) {
                logger.error("释放APM实例失败: ${e.message}")
            }
        }
        
        logger.info("已释放 $releaseCount/${instancesToRelease.size} 个WebRTC APM实例")
    }



    /**
     * 获取当前缓存的实例数量和状态
     * 
     * @return 状态描述字符串
     */
    fun getInstancesInfo(): String {
        return synchronized(lock) {
            val now =System.now().toEpochMilliseconds()
            val sb = StringBuilder()
            sb.appendLine("APM实例缓存状态:")
            sb.appendLine("共有 ${instanceMap.size} 个实例")
            
            instanceMap.forEach { (key, instance) ->
                val (rate, ch) = key
                val timestamp = instanceTimestamps[key] ?: 0L
                val age = now - timestamp
                
                sb.appendLine("- ${rate}Hz/${ch}ch: 已缓存${age/1000}秒, " +
                             "实际参数=(${instance.getActualInputSampleRate()}Hz, ${instance.getInputChannels()}ch)")
            }
            
            sb.toString()
        }
    }
}