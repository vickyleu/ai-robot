// WebRtcApmSingleton ‑ 支持按 (sampleRate, channels) 缓存多实例
@file:OptIn(ExperimentalForeignApi::class)

package voice.audio.processing

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ExperimentalForeignApi
import voice.util.LogManager
import voice.util.AudioDefaults

// Kotlin/Native 暂无 ConcurrentHashMap，使用全局锁保护普通可变 Map 即可
private typealias Key = Pair<Int, Int> // (sampleRate, channels)

object WebRtcApmSingleton {
    private val logger = LogManager.getLogger("WebRtcApmSingleton")
    private val lock = SynchronizedObject()

    // 按 (采样率, 通道) 缓存的 APM 实例
    private val instanceMap = mutableMapOf<Key, WebRtcApm>()

    fun getInstance(
        sampleRate: Int = AudioDefaults.TARGET_SAMPLE_RATE,
        channels: Int = AudioDefaults.CHANNELS,
        recreateIfNeeded: Boolean = false
    ): WebRtcApm? {
        val key: Key = sampleRate to channels
        synchronized(lock) {
            var instance = instanceMap[key]

            if (instance == null) {
                // 不存在就创建新的
                instance = createNewInstance(sampleRate, channels)
                if (instance != null) {
                    instanceMap[key] = instance
                }
            } else if (recreateIfNeeded) {
                // 需要重建
                val currentRate = instance.getActualInputSampleRate()
                val currentChannels = instance.getInputChannels()
                if (currentRate != sampleRate || currentChannels != channels) {
                    logger.info("重新创建APM实例: $currentRate->$sampleRate, $currentChannels->$channels")
                    instance.release()
                    instance = createNewInstance(sampleRate, channels)
                    if (instance != null) {
                        instanceMap[key] = instance
                    } else {
                        instanceMap.remove(key)
                    }
                }
            } else {
                // 更新参数但不重建
                instance.updateInputParameters(sampleRate, channels)
            }

            return instance
        }
    }

    private fun createNewInstance(sampleRate: Int, channels: Int): WebRtcApm? {
        val newInstance = WebRtcApm()
        return if (newInstance.initialize(sampleRate, channels)) {
            logger.info("创建新的WebRTC APM实例: 采样率=$sampleRate, 通道数=$channels")

            // 设置优化的VAD参数
            newInstance.setVadThreshold(0.12f)
            newInstance.setVadDebounceFrames(2)

            newInstance
        } else {
            logger.error("创建WebRTC APM实例失败")
            null
        }
    }


    fun enableEchoCancellation(enable: Boolean) {
        synchronized(lock) {
            instanceMap.values.forEach { it.enableEchoCancellation(enable) }
        }
        if (enable) {
            logger.info("单例模式：回声消除已启用")
        }
    }

    fun release() {
        synchronized(lock) {
            // 释放全部实例
            instanceMap.values.forEach { it.release() }
            instanceMap.clear()
            logger.info("已释放所有 WebRTC APM 实例")
        }
    }

    /**
     * 处理音频帧的便捷方法：按 (sampleRate, channels) 获取/创建对应实例后调用。
     * 若实例不可用则直接回传原始数据。
     */
    fun processFrame(
        audioData: ShortArray,
        sampleRate: Int = AudioDefaults.TARGET_SAMPLE_RATE,
        channels: Int = 2
    ): ShortArray {
        val apm = getInstance(sampleRate, channels)
        return if (apm != null) {
            try {
                apm.processFrame(audioData)
            } catch (e: Exception) {
                logger.error("处理音频帧异常: ${e.message}")
                audioData
            }
        } else audioData
    }

    /**
     * 查询 VAD 结果。
     */
    fun isVoiceDetected(sampleRate: Int = AudioDefaults.TARGET_SAMPLE_RATE, channels: Int = 2): Boolean {
        val apm = synchronized(lock) { instanceMap[sampleRate to channels] } ?: return false
        return apm.isVoiceDetected()
    }
}