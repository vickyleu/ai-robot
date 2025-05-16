@file:OptIn(ExperimentalForeignApi::class, ExperimentalTime::class, NativeRuntimeApi::class)

package snowboyPiper.interop

import com.airobot.rnnoiseinterop.RNNoiseWrapper
import com.airobot.rnnoiseinterop.SOXR_FLOAT32_I
import com.airobot.rnnoiseinterop.SoxWrapper
import com.airobot.rnnoiseinterop.rnnoise_wrapper_create
import com.airobot.rnnoiseinterop.rnnoise_wrapper_destroy
import com.airobot.rnnoiseinterop.rnnoise_wrapper_process
import com.airobot.rnnoiseinterop.rnnoise_wrapper_process_batch
import com.airobot.rnnoiseinterop.rnnoise_wrapper_set_gain
import com.airobot.rnnoiseinterop.rnnoise_wrapper_set_vad_threshold
import com.airobot.rnnoiseinterop.soxr_io_spec_create
import com.airobot.rnnoiseinterop.soxr_quality_spec_create
import com.airobot.rnnoiseinterop.soxr_wrapper_create
import com.airobot.rnnoiseinterop.soxr_wrapper_create_resampler
import com.airobot.rnnoiseinterop.soxr_wrapper_destroy
import com.airobot.rnnoiseinterop.soxr_wrapper_process
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.CArrayPointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.pointed
import kotlinx.cinterop.staticCFunction
import platform.posix.atexit
import kotlin.concurrent.AtomicInt
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

// 清理回调函数
private fun cleanupResources() {
    println("[INFO] 程序退出，清理音频处理资源...")
    AudioProcessingResourceManager.releaseAllResources()
}

/**
 * RNNoise工具的单例模式包装器
 * 避免频繁创建和销毁RNNoise实例，提高性能和资源利用率
 */
object RNNoiseSingleton {
    // 实例缓存
    private var instance: CPointer<RNNoiseWrapper>? = null

    // 引用计数，用于追踪实例使用情况
    private var refCount = AtomicInt(0)

    // 最后使用时间，用于超时释放
    private var lastUseTime = 0L

    // 超时时间（毫秒）
    private const val TIMEOUT_MS = 120000L

    // 默认VAD阈值
    private const val DEFAULT_VAD_THRESHOLD = 0.08f

    // 默认增益
    private const val DEFAULT_GAIN = 2.5f

    // 用于多线程同步的锁对象
    private val lock = SynchronizedObject()

    /**
     * 获取RNNoise实例
     * 如果实例不存在或已过期，将创建新实例
     * @return RNNoise包装器实例
     */
    fun getInstance(): CPointer<RNNoiseWrapper>? {
        val currentTime = Clock.System.now().toEpochMilliseconds()

        synchronized(lock) {
            // 检查是否已超时
            if (instance != null && currentTime - lastUseTime > TIMEOUT_MS && refCount.value == 0) {
                // 已过期且没有引用，释放资源
                rnnoise_wrapper_destroy(instance)
                instance = null
            }

            // 如果实例不存在，创建新实例
            if (instance == null) {
                instance = rnnoise_wrapper_create()
                if (instance == null) {
                    println("[ERROR] 无法创建RNNoise实例")
                    return null
                }

                // 设置默认参数
                rnnoise_wrapper_set_vad_threshold(instance, DEFAULT_VAD_THRESHOLD)
                rnnoise_wrapper_set_gain(instance, DEFAULT_GAIN)
            }

            // 更新最后使用时间
            lastUseTime = currentTime

            // 增加引用计数
            refCount.incrementAndGet()

            return instance
        }
    }

    /**
     * 释放对RNNoise实例的引用
     * 不会立即销毁实例，而是减少引用计数
     */
    fun releaseInstance() {
        synchronized(lock) {
            if (refCount.value > 0) {
                refCount.decrementAndGet()
            }
        }
    }

    /**
     * 使用RNNoise处理音频数据
     * @param inputBuffer 输入音频数据
     * @param outputBuffer 输出音频数据
     * @param frameCount 帧数
     * @param vadProbabilitiesPtr VAD概率数组
     * @param maxVadValues 最大VAD值数量
     * @param vadThreshold VAD阈值（可选）
     * @param gain 增益（可选）
     * @return 处理的帧数
     */
    fun process(
        inputBuffer: CArrayPointer<ShortVar>?,
        outputBuffer: CArrayPointer<ShortVar>?,
        frameCount: Int,
        vadProbabilitiesPtr: CArrayPointer<FloatVar>? = null,
        maxVadValues: Int = 0,
        vadThreshold: Float = DEFAULT_VAD_THRESHOLD,
        gain: Float = DEFAULT_GAIN
    ): Int {
        // 参数检查
        if (inputBuffer == null || outputBuffer == null || frameCount <= 0) {
            // 如果参数检查失败，尝试获取实例并设置参数
            val wrapper = getInstance() ?: return -1
            try {
                // 设置参数
                rnnoise_wrapper_set_vad_threshold(wrapper, vadThreshold)
                rnnoise_wrapper_set_gain(wrapper, gain)
                return 0
            } finally {
                releaseInstance()
            }
        }

        val wrapper = getInstance() ?: return -1

        try {
            // 设置参数
            rnnoise_wrapper_set_vad_threshold(wrapper, vadThreshold)
            rnnoise_wrapper_set_gain(wrapper, gain)

            // 处理音频
            val result = rnnoise_wrapper_process(
                wrapper,
                inputBuffer,
                outputBuffer,
                frameCount,
                vadProbabilitiesPtr,
                maxVadValues
            )

            return result
        } finally {
            // 确保释放实例引用
            releaseInstance()
        }
    }

    /**
     * 使用RNNoise批量处理音频数据
     * @param inputBuffer 输入音频数据
     * @param outputBuffer 输出音频数据
     * @param sampleCount 样本数
     * @param voiceFramesDetectedPtr 检测到的语音帧数指针
     * @param vadThreshold VAD阈值（可选）
     * @param gain 增益（可选）
     * @return 处理的帧数
     */
    fun processBatch(
        inputBuffer: CArrayPointer<ShortVar>,
        outputBuffer: CArrayPointer<ShortVar>,
        sampleCount: Int,
        voiceFramesDetectedPtr: CPointer<IntVar>,
        vadThreshold: Float = 0.05f,
        gain: Float = 3.0f
    ): Int {
        val wrapper = getInstance() ?: return -1

        try {
            // 设置参数
            rnnoise_wrapper_set_vad_threshold(wrapper, vadThreshold)
            rnnoise_wrapper_set_gain(wrapper, gain)

            // 批量处理音频
            val result = rnnoise_wrapper_process_batch(
                wrapper,
                inputBuffer,
                outputBuffer,
                sampleCount,
                voiceFramesDetectedPtr
            )

            return result
        } finally {
            // 确保释放实例引用
            releaseInstance()
        }
    }

    /**
     * 检查RNNoise实例是否有效
     * @return 实例是否有效
     */
    fun isValid(): Boolean = instance != null

    /**
     * 获取当前引用计数
     * @return 引用计数
     */
    fun getRefCount(): Int = refCount.value

    /**
     * 强制释放资源，通常在程序结束时调用
     */
    fun forceRelease() {
        synchronized(lock) {
            if (instance != null) {
                rnnoise_wrapper_destroy(instance)
                instance = null
                refCount.value = 0
            }
        }
    }
}

/**
 * Soxr工具的单例模式包装器
 * 避免频繁创建和销毁Soxr实例，提高性能和资源利用率
 */
object SoxrSingleton {
    // 实例参数缓存
    private data class SoxrConfig(val inputRate: Double, val outputRate: Double)

    // 实例缓存映射，根据不同的采样率配置缓存不同的实例
    private val instanceMap = mutableMapOf<SoxrConfig, CPointer<SoxWrapper>?>()

    // 引用计数映射
    private val refCountMap = mutableMapOf<SoxrConfig, AtomicInt>()

    // 最后使用时间映射
    private val lastUseTimeMap = mutableMapOf<SoxrConfig, Long>()

    // 超时时间（毫秒）
    private const val TIMEOUT_MS = 180000L // 增加超时时间，减少实例销毁重建

    // 用于多线程同步的锁对象
    private val lock = SynchronizedObject()

    // 预定义常用采样率，用于预热
    private val commonConfigs = listOf(
        SoxrConfig(48000.0, 16000.0),
        SoxrConfig(44100.0, 16000.0),
        SoxrConfig(16000.0, 8000.0),
        SoxrConfig(8000.0, 16000.0)
    )

    /**
     * 预热常用采样率配置的实例
     * 在程序启动时调用，减少运行时创建实例延迟
     */
    fun preloadCommonConfigs() {
        synchronized(lock) {
            for (config in commonConfigs) {
                if (!instanceMap.containsKey(config) || instanceMap[config] == null) {
                    val wrapper = createSoxrInstance(config.inputRate, config.outputRate)
                    if (wrapper != null) {
                        instanceMap[config] = wrapper
                        refCountMap[config] = AtomicInt(0)
                        lastUseTimeMap[config] = Clock.System.now().toEpochMilliseconds()
                        println("[INFO] 预定义Soxr配置${config.inputRate}->${config.outputRate}预热成功")
                    }
                }
            }
        }
    }

    /**
     * 创建Soxr实例
     */
    private fun createSoxrInstance(inputRate: Double, outputRate: Double): CPointer<SoxWrapper>? {
        val wrapper = soxr_wrapper_create() ?: return null

        // 配置实例
        soxr_io_spec_create(SOXR_FLOAT32_I, SOXR_FLOAT32_I, wrapper)
        soxr_quality_spec_create(SOXR_FLOAT32_I, wrapper)

        // 创建重采样器
        val result = soxr_wrapper_create_resampler(wrapper, inputRate, outputRate)
        if (result < 0 || wrapper.pointed.soxr == null) {
            soxr_wrapper_destroy(wrapper)
            return null
        }

        return wrapper
    }

    /**
     * 获取Soxr实例
     * 如果实例不存在或已过期，将创建新实例
     * @param inputRate 输入采样率
     * @param outputRate 输出采样率
     * @return Soxr包装器实例
     */
    fun getInstance(inputRate: Double, outputRate: Double): CPointer<SoxWrapper>? {
        val config = SoxrConfig(inputRate, outputRate)
        val currentTime = Clock.System.now().toEpochMilliseconds()

        synchronized(lock) {
            // 检查是否已超时
            if (instanceMap.containsKey(config) &&
                currentTime - (lastUseTimeMap[config] ?: 0) > TIMEOUT_MS &&
                (refCountMap[config]?.value ?: 0) == 0
            ) {
                // 已过期且没有引用，释放资源
                instanceMap[config]?.let { soxr_wrapper_destroy(it) }
                instanceMap.remove(config)
                refCountMap.remove(config)
                lastUseTimeMap.remove(config)
            }

            // 如果实例不存在，创建新实例
            if (!instanceMap.containsKey(config) || instanceMap[config] == null) {
                val wrapper = createSoxrInstance(inputRate, outputRate)
                if (wrapper == null) {
                    println("[ERROR] 无法创建Soxr实例: $inputRate -> $outputRate")
                    return null
                }
                // 保存实例
                instanceMap[config] = wrapper
                refCountMap[config] = AtomicInt(0)
            }

            // 更新最后使用时间
            lastUseTimeMap[config] = currentTime

            // 增加引用计数
            refCountMap[config]?.incrementAndGet()

            return instanceMap[config]
        }
    }

    /**
     * 释放对Soxr实例的引用
     * 不会立即销毁实例，而是减少引用计数
     * @param inputRate 输入采样率
     * @param outputRate 输出采样率
     */
    fun releaseInstance(inputRate: Double, outputRate: Double) {
        val config = SoxrConfig(inputRate, outputRate)

        synchronized(lock) {
            refCountMap[config]?.let {
                if (it.value > 0) {
                    it.decrementAndGet()
                }
            }
        }
    }

    /**
     * 使用Soxr处理音频数据
     * @param inputRate 输入采样率
     * @param outputRate 输出采样率
     * @param inData 输入音频数据
     * @param inSize 输入大小
     * @param outData 输出音频数据
     * @param outSize 输出大小
     * @return 处理的样本数
     */
    fun process(
        inputRate: Double,
        outputRate: Double,
        inData: CArrayPointer<ShortVar>,
        inSize: UInt,
        outData: CArrayPointer<FloatVar>,
        outSize: UInt
    ): ULong {
        val wrapper = getInstance(inputRate, outputRate) ?: return 0u

        try {
            // 处理音频
            val result = soxr_wrapper_process(
                wrapper,
                inData,
                inSize,
                outData,
                outSize
            )

            return result.toULong()
        } finally {
            // 确保释放实例引用
            releaseInstance(inputRate, outputRate)
        }
    }

    /**
     * 检查是否有指定采样率配置的有效实例
     * @param inputRate 输入采样率
     * @param outputRate 输出采样率
     * @return 实例是否有效
     */
    fun isValid(inputRate: Double, outputRate: Double): Boolean {
        val config = SoxrConfig(inputRate, outputRate)
        return instanceMap.containsKey(config) && instanceMap[config] != null
    }

    /**
     * 获取当前引用计数
     * @param inputRate 输入采样率
     * @param outputRate 输出采样率
     * @return 引用计数
     */
    fun getRefCount(inputRate: Double, outputRate: Double): Int {
        val config = SoxrConfig(inputRate, outputRate)
        return refCountMap[config]?.value ?: 0
    }

    /**
     * 强制释放所有资源，通常在程序结束时调用
     */
    fun forceReleaseAll() {
        synchronized(lock) {
            for (entry in instanceMap) {
                entry.value?.let { soxr_wrapper_destroy(it) }
            }
            instanceMap.clear()
            refCountMap.clear()
            lastUseTimeMap.clear()
        }
    }

    /**
     * 释放特定的实例
     * @param inputRate 输入采样率
     * @param outputRate 输出采样率
     */
    fun forceRelease(inputRate: Double, outputRate: Double) {
        val config = SoxrConfig(inputRate, outputRate)

        synchronized(lock) {
            instanceMap[config]?.let { soxr_wrapper_destroy(it) }
            instanceMap.remove(config)
            refCountMap.remove(config)
            lastUseTimeMap.remove(config)
        }
    }
}

/**
 * 资源管理器，负责在程序结束时释放所有资源
 */
object AudioProcessingResourceManager {
    private var isShutdownHookRegistered = false
    private var isPrewarmed = false

    /**
     * 注册关闭钩子，确保在程序结束时释放资源
     */
    fun registerShutdownHook() {
        if (!isShutdownHookRegistered) {
            // C 标准库 atexit 注册函数，在程序退出时调用
            atexit(staticCFunction<Unit> {
                // 清理资源
                releaseAllResources()
            })
            isShutdownHookRegistered = true
            println("[INFO] 已注册程序退出清理钩子")
        }
    }

    /**
     * 预热资源，提前加载所有必要的资源
     */
    fun prewarmResources() {
        if (isPrewarmed) return

        // 预热常用采样率配置的实例
        SoxrSingleton.preloadCommonConfigs()

        // 预热RNNoise实例
        val rnnoise = RNNoiseSingleton.getInstance()
        if (rnnoise != null) {
            println("[INFO] RNNoise实例预热成功")
            RNNoiseSingleton.releaseInstance()
        }

        isPrewarmed = true
        println("[INFO] 所有音频处理资源已预热完成")
    }

    /**
     * 释放所有音频处理资源
     */
    fun releaseAllResources() {
        // 释放RNNoise资源
        RNNoiseSingleton.forceRelease()

        // 释放Soxr资源
        SoxrSingleton.forceReleaseAll()

        // 强制垃圾回收
        kotlin.native.runtime.GC.collect()

        // 重置预热状态
        isPrewarmed = false

        println("[INFO] 所有音频处理资源已释放")
    }
} 