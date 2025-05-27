// 简化的 WebRtcApm.kt - 只使用标准WebRTC APM + SOXR
@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package voice.audio.processing

import com.airobot.core.utils.format
import com.airobot.webrtcapminterop.*
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import voice.util.AudioDefaults
import voice.util.AudioUtils
import voice.util.LogManager
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.abs
import kotlin.native.concurrent.freeze

/**
 * RAII资源管理器 - 确保资源总是被正确释放
 */
sealed class ManagedResource : AutoCloseable {
    abstract override fun close()

    /**
     * 管理WebRTC APM句柄
     */
    class ApmHandle(private val handle: CPointer<*>) : ManagedResource() {
        fun get(): CPointer<*> = handle

        override fun close() {
            try {
                webrtc_apm_destroy(handle)
            } catch (e: Exception) {
                LogManager.getLogger("ApmHandle").warn("释放APM句柄失败: ${e.message}")
            }
        }
    }

    /**
     * 管理SOXR包装器
     */
    class SoxrWrapper(private val wrapper: CPointer<SoxWrapper>) : ManagedResource() {
        fun get(): CPointer<SoxWrapper> = wrapper

        override fun close() {
            try {
                soxr_wrapper_destroy(wrapper)
            } catch (e: Exception) {
                LogManager.getLogger("SoxrWrapper").warn("释放SOXR包装器失败: ${e.message}")
            }
        }
    }

    /**
     * 管理原生堆分配的缓冲区
     */
    class NativeBuffer<T : CVariable>(
        private val buffer: CPointer<T>,
        private val name: String = "Buffer"
    ) : ManagedResource() {
        fun get(): CPointer<T> = buffer

        override fun close() {
            try {
                nativeHeap.free(buffer.rawValue)
            } catch (e: Exception) {
                LogManager.getLogger("NativeBuffer").warn("释放$name 失败: ${e.message}")
            }
        }
    }
}

/**
 * RAII资源管理器 - 自动管理多个资源的生命周期
 */
class ResourceManager : AutoCloseable {
    private val resources = mutableListOf<ManagedResource>()
    private val logger = LogManager.getLogger("ResourceManager")

    fun <T : ManagedResource> manage(resource: T): T {
        resources.add(resource)
        return resource
    }

    override fun close() {
        // 按LIFO顺序释放资源（后分配的先释放）
        resources.asReversed().forEach { resource ->
            try {
                resource.close()
            } catch (e: Exception) {
                logger.warn("释放资源失败: ${e.message}")
            }
        }
        resources.clear()
    }
}

/**
 * 安全的缓冲区管理器
 */
class BufferManager {
    private val logger = LogManager.getLogger("BufferManager")
    private val bufferMutex = Mutex()

    // 当前缓冲区资源
    private var currentResources: ResourceManager? = null
    private var currentBufferSize: Int = 0

    // 缓冲区指针
    private var inputFloatBuffer: CPointer<FloatVar>? = null
    private var outputFloatBuffer: CPointer<FloatVar>? = null
    private var inputArrayPointer: CPointer<CPointerVar<FloatVar>>? = null
    private var outputArrayPointer: CPointer<CPointerVar<FloatVar>>? = null

    suspend fun ensureBufferSize(requiredSize: Int, channels: Int): Boolean {
        if (requiredSize <= 0) {
            logger.error("无效的缓冲区大小: $requiredSize")
            return false
        }

        if (requiredSize > MAX_BUFFER_SIZE) {
            logger.error("缓冲区大小过大: $requiredSize > $MAX_BUFFER_SIZE")
            return false
        }

        bufferMutex.withLock {
            if (currentBufferSize == requiredSize && inputFloatBuffer != null) {
                return true // 已经分配了正确大小的缓冲区
            }

            // 释放旧资源
            currentResources?.close()
            currentResources = null
            clearBufferPointers()

            try {
                // 创建新的资源管理器
                val resources = ResourceManager()

                // 分配缓冲区
                val inputBuffer = resources.manage(
                    ManagedResource.NativeBuffer(
                        nativeHeap.allocArray<FloatVar>(requiredSize),
                        "InputFloatBuffer"
                    )
                )

                val outputBuffer = resources.manage(
                    ManagedResource.NativeBuffer(
                        nativeHeap.allocArray<FloatVar>(requiredSize),
                        "OutputFloatBuffer"
                    )
                )

                val safeChannels = channels.coerceAtLeast(1)
                val inputArray = resources.manage(
                    ManagedResource.NativeBuffer(
                        nativeHeap.allocArray<CPointerVar<FloatVar>>(safeChannels),
                        "InputArrayPointer"
                    )
                )

                val outputArray = resources.manage(
                    ManagedResource.NativeBuffer(
                        nativeHeap.allocArray<CPointerVar<FloatVar>>(safeChannels),
                        "OutputArrayPointer"
                    )
                )

                // 设置指针
                inputFloatBuffer = inputBuffer.get()
                outputFloatBuffer = outputBuffer.get()
                inputArrayPointer = inputArray.get()
                outputArrayPointer = outputArray.get()

                // 配置数组指针
                if (channels == 1) {
                    inputArrayPointer!![0] = inputFloatBuffer
                    outputArrayPointer!![0] = outputFloatBuffer
                } else {
                    logger.warn("当前APM配置为 $channels 通道，但缓冲区分配仅完整支持单通道")
                    inputArrayPointer!![0] = inputFloatBuffer
                    outputArrayPointer!![0] = outputFloatBuffer
                }

                // 保存资源管理器和大小
                currentResources = resources
                currentBufferSize = requiredSize

                logger.debug("成功分配APM缓冲区: 大小=$requiredSize")
                return true

            } catch (e: Exception) {
                logger.error("缓冲区分配失败: ${e.message}")
                currentResources?.close()
                currentResources = null
                clearBufferPointers()
                return false
            }
        }
    }

    private fun clearBufferPointers() {
        inputFloatBuffer = null
        outputFloatBuffer = null
        inputArrayPointer = null
        outputArrayPointer = null
        currentBufferSize = 0
    }

    fun getInputBuffer(): CPointer<FloatVar>? = inputFloatBuffer
    fun getOutputBuffer(): CPointer<FloatVar>? = outputFloatBuffer
    fun getInputArrayPointer(): CPointer<CPointerVar<FloatVar>>? = inputArrayPointer
    fun getOutputArrayPointer(): CPointer<CPointerVar<FloatVar>>? = outputArrayPointer
    fun getCurrentBufferSize(): Int = currentBufferSize

    fun release() {
        runBlocking {
            bufferMutex.withLock {
                currentResources?.close()
                currentResources = null
                clearBufferPointers()
            }
        }
    }

    companion object {
        private const val MAX_BUFFER_SIZE = 200000
    }
}

/**
 * 安全的SOXR重采样器封装类
 * 支持采样率和声道数转换，自动处理内存管理
 */
class SafeSoxrResampler(
    private val inputSampleRate: Int,
    private val outputSampleRate: Int,
    private val inputChannels: Int,
    private val outputChannels: Int,
    private val inputFormat: Int = 0u.toInt(), // SOXR_INT16_I
    private val outputFormat: Int = 0u.toInt(), // SOXR_INT16_I (输出也用INT16，避免精度损失)
    private val quality: Int = 1u.toInt() // SOXR_LQ (低质量，高稳定性)
) {
    private val logger = LogManager.getLogger("SafeSoxrResampler")
    private var soxrWrapper: CPointer<SoxWrapper>? = null
    private var isInitialized = false
    
    /**
     * 初始化重采样器
     */
    fun initialize(): Boolean {
        if (isInitialized) {
            return true
        }
        
        try {
            // 检查是否需要重采样
            if (inputSampleRate == outputSampleRate && inputChannels == outputChannels) {
                logger.debug("输入输出格式相同，无需重采样: ${inputSampleRate}Hz/${inputChannels}ch")
                isInitialized = true
                return true
            }
            
            // 创建SOXR包装器
            soxrWrapper = soxr_wrapper_create()
            if (soxrWrapper == null) {
                logger.error("无法创建SOXR包装器")
                return false
            }
            
            // 配置IO格式 - 根据参数动态配置
            soxr_io_spec_create(inputFormat.toUInt(), outputFormat.toUInt(), soxrWrapper)
            
            // 配置运行时参数 - 使用单线程确保稳定性
            soxr_runtime_spec_create(1u, soxrWrapper)
            
            // 配置质量参数
            soxr_quality_spec_create(quality.toUInt(), soxrWrapper)
            
            // 创建重采样器实例
            val result = soxr_wrapper_create_resampler(
                soxrWrapper,
                inputSampleRate.toDouble(),
                outputSampleRate.toDouble(),
                inputChannels.toUInt()
            )
            
            if (result != 0) {
                logger.error("创建重采样器失败，错误码: $result")
                release()
                return false
            }
            
            isInitialized = true
            logger.info("SafeSoxrResampler初始化成功: ${inputSampleRate}Hz/${inputChannels}ch -> ${outputSampleRate}Hz/${outputChannels}ch")
            return true
            
        } catch (e: Exception) {
            logger.error("SafeSoxrResampler初始化失败: ${e.message}")
            release()
            return false
        }
    }
    
    /**
     * 执行重采样和声道转换
     * @param inputData 输入音频数据
     * @return 重采样和声道转换后的音频数据
     */
    fun process(inputData: ShortArray): ShortArray {
        if (inputData.isEmpty()) {
            return inputData
        }
        
        if (!isInitialized) {
            logger.error("重采样器未初始化")
            return inputData
        }
        
        try {
            // 第1步：声道转换（如果需要）
            val channelConvertedData = convertChannels(inputData, inputChannels, outputChannels)
            
            // 第2步：采样率转换（如果需要）
            return if (inputSampleRate != outputSampleRate) {
                resampleAudio(channelConvertedData)
            } else {
                channelConvertedData
            }
            
        } catch (e: Exception) {
            logger.error("SafeSoxrResampler处理失败: ${e.message}")
            return inputData
        }
    }
    
    /**
     * 声道转换
     */
    private fun convertChannels(inputData: ShortArray, fromChannels: Int, toChannels: Int): ShortArray {
        if (fromChannels == toChannels) {
            return inputData
        }
        
        return when {
            fromChannels == 1 && toChannels == 2 -> {
                // 单声道转立体声：每个样本重复为左右声道
                ShortArray(inputData.size * 2) { i ->
                    inputData[i / 2]
                }
            }
            fromChannels == 2 && toChannels == 1 -> {
                // 立体声转单声道：取左右声道平均值
                ShortArray(inputData.size / 2) { i ->
                    val left = inputData[i * 2].toInt()
                    val right = inputData[i * 2 + 1].toInt()
                    ((left + right) / 2).coerceIn(-32767, 32767).toShort()
                }
            }
            else -> {
                logger.warn("不支持的声道转换: ${fromChannels}ch -> ${toChannels}ch")
                inputData
            }
        }
    }
    
    /**
     * 使用SOXR进行采样率转换
     */
    private fun resampleAudio(inputData: ShortArray): ShortArray {
        if (soxrWrapper == null) {
            logger.error("SOXR包装器为空")
            return inputData
        }
        
        // 计算期望的输出大小
        val expectedOutputSize = (inputData.size * outputSampleRate.toDouble() / inputSampleRate.toDouble()).toInt()
        val outputBufferSize = (expectedOutputSize * 12 / 10).coerceAtLeast(inputData.size)
        
        // 安全性检查
        if (outputBufferSize <= 0 || outputBufferSize > 200000) {
            logger.error("计算的输出缓冲区大小异常: $outputBufferSize")
            return inputData
        }
        
        // 根据输出格式选择合适的缓冲区类型
        when (outputFormat) {
            SOXR_INT16_I.toInt() -> {
                // 输出INT16格式
                var outputBuffer: CPointer<ShortVar>? = null
                
                try {
                    outputBuffer = nativeHeap.allocArray<ShortVar>(outputBufferSize)
                    
                    val outputFrames = soxr_wrapper_process(
                        wrapper = soxrWrapper,
                        in_data = inputData.refTo(0),
                        in_size = inputData.size.toUInt(),
                        out_data = outputBuffer.reinterpret<FloatVar>(),
                        out_size = outputBufferSize.toUInt()
                    )
                    
                    if (outputFrames == 0U && inputData.isNotEmpty()) {
                        logger.warn("SOXR重采样返回0帧")
                        return inputData
                    }
                    
                    return ShortArray(outputFrames.toInt()) { i ->
                        outputBuffer!![i]
                    }
                    
                } finally {
                    outputBuffer?.let { buffer ->
                        try {
                            nativeHeap.free(buffer)
                        } catch (e: Exception) {
                            logger.warn("释放SOXR输出缓冲区失败: ${e.message}")
                        }
                    }
                }
            }
            
            SOXR_FLOAT32_I.toInt() -> {
                // 输出FLOAT32格式
                var outputBuffer: CPointer<FloatVar>? = null
                
                try {
                    outputBuffer = nativeHeap.allocArray<FloatVar>(outputBufferSize)
                    
                    val outputFrames = soxr_wrapper_process(
                        wrapper = soxrWrapper,
                        in_data = inputData.refTo(0),
                        in_size = inputData.size.toUInt(),
                        out_data = outputBuffer,
                        out_size = outputBufferSize.toUInt()
                    )
                    
                    if (outputFrames == 0U && inputData.isNotEmpty()) {
                        logger.warn("SOXR重采样返回0帧")
                        return inputData
                    }
                    
                    return ShortArray(outputFrames.toInt()) { i ->
                        (outputBuffer!![i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    }
                    
                } finally {
                    outputBuffer?.let { buffer ->
                        try {
                            nativeHeap.free(buffer)
                        } catch (e: Exception) {
                            logger.warn("释放SOXR输出缓冲区失败: ${e.message}")
                        }
                    }
                }
            }
            
            else -> {
                logger.error("不支持的输出格式: $outputFormat")
                return inputData
            }
        }
    }
    
    /**
     * 释放资源
     */
    fun release() {
        soxrWrapper?.let {
            try {
                soxr_wrapper_destroy(it)
            } catch (e: Exception) {
                logger.warn("释放SOXR包装器失败: ${e.message}")
            }
            soxrWrapper = null
        }
        isInitialized = false
        logger.debug("SafeSoxrResampler资源已释放")
    }
    
    /**
     * 检查是否需要处理
     */
    fun needsProcessing(): Boolean {
        return inputSampleRate != outputSampleRate || inputChannels != outputChannels
    }
    
    companion object {
        // SOXR格式常量 - 根据实际C库定义调整
        const val SOXR_INT16_I = 0u
        const val SOXR_INT32_I = 1u  
        const val SOXR_FLOAT32_I = 2u
        const val SOXR_FLOAT64_I = 3u
        
        // SOXR质量常量
        const val SOXR_QQ = 0u      // 快速质量
        const val SOXR_LQ = 1u      // 低质量
        const val SOXR_MQ = 2u      // 中等质量
        const val SOXR_HQ = 3u      // 高质量
        const val SOXR_VHQ = 4u     // 非常高质量
        
        /**
         * 创建用于输入重采样的实例（INT16输入，FLOAT32输出用于APM）
         */
        fun createForInput(inputSampleRate: Int, outputSampleRate: Int, channels: Int): SafeSoxrResampler {
            return SafeSoxrResampler(
                inputSampleRate = inputSampleRate,
                outputSampleRate = outputSampleRate,
                inputChannels = channels,
                outputChannels = channels,
                inputFormat = SOXR_INT16_I.toInt(),
                outputFormat = SOXR_FLOAT32_I.toInt(),
                quality = SOXR_LQ.toInt()
            )
        }
        
        /**
         * 创建用于输出重采样的实例（INT16输入，INT16输出）
         */
        fun createForOutput(inputSampleRate: Int, outputSampleRate: Int, inputChannels: Int, outputChannels: Int): SafeSoxrResampler {
            return SafeSoxrResampler(
                inputSampleRate = inputSampleRate,
                outputSampleRate = outputSampleRate,
                inputChannels = inputChannels,
                outputChannels = outputChannels,
                inputFormat = SOXR_INT16_I.toInt(),
                outputFormat = SOXR_INT16_I.toInt(),
                quality = SOXR_LQ.toInt()
            )
        }
    }
}

/**
 * RAII模式重构的WebRtcApm类
 */
class WebRtcApm : AutoCloseable {
    private val logger = LogManager.getLogger("WebRtcApm")

    // 资源管理
    private var resources: ResourceManager? = null
    private var apmHandle: CPointer<*>? = null
    private val bufferManager = BufferManager()

    // 重采样器
    private var inputResampler: SafeSoxrResampler? = null

    // 音频格式配置
    private var inputFormat = AudioDefaults.Formats.INPUT_DEVICE
    private var apmFormat = AudioDefaults.Formats.WEBRTC_APM

    // VAD参数
    private var vadLogCounter: Int = 0
    private var consecutiveVadPositive: Int = 0
    private var lastVadResult: Boolean = false
    private var vadDebounceFrames: Int = 3

    // 状态标志
    @Volatile
    private var isFullyInitialized = false
    @Volatile
    private var processingEnabled = false

    suspend fun initialize(sampleRate: Int, channels: Int): Boolean {
        processingEnabled = false
        isFullyInitialized = false

        if (apmHandle != null) {
            logger.warn("WebRTC APM 已经初始化，使用先前配置: $apmFormat")
            if (inputFormat.sampleRate != sampleRate || inputFormat.channels != channels) {
                logger.warn("警告: 尝试使用不同的参数重新初始化。旧参数: $inputFormat, 新参数: ${sampleRate}Hz/${channels}ch")
            }
            processingEnabled = true
            isFullyInitialized = true
            return true
        }

        // 设置音频格式
        inputFormat = AudioDefaults.AudioFormat(sampleRate, channels)
        apmFormat = AudioDefaults.Formats.WEBRTC_APM

        // 验证音频格式
        if (!AudioDefaults.isValidAudioFormat(inputFormat)) {
            logger.error("无效的输入音频格式: $inputFormat")
            return false
        }

        logger.info("APM配置: 输入=$inputFormat, APM内部处理=$apmFormat")
        logger.info("转换路径: ${AudioDefaults.getConversionPath(inputFormat, apmFormat)}")

        try {
            // 创建资源管理器
            val resourceManager = ResourceManager()

            // 创建APM实例
            val handle = webrtc_apm_create()
                ?: throw Exception("WebRTC APM 创建失败")

            val managedHandle = resourceManager.manage(
                ManagedResource.ApmHandle(handle)
            )

            // 配置APM
            memScoped {
                val config = alloc<APMConfig>()

                // 启用噪声抑制
                config.noise_suppression.enabled = true
                config.noise_suppression.level = kNsVeryHigh

                config.high_pass_filter.enabled = true

                // 启用自动增益控制
                config.gain_controller.enabled = true
                config.gain_controller.mode = kAgcAdaptiveDigital
                config.gain_controller.target_level_dbfs = 3
                config.gain_controller.compression_gain_db = 9
                config.gain_controller.enable_limiter = true

                config.pre_amplifier.enabled = true
                config.pre_amplifier.fixed_gain_factor = 1.0f

                config.voice_detection.enabled = true
                config.echo_canceller.enabled = true
                config.transient_suppression.enabled = true
                config.residual_echo_detector.enabled = true

                webrtc_apm_apply_config(handle, config.ptr)
            }

            // 准备APM处理
            webrtc_apm_prepare(handle, apmFormat.sampleRate, apmFormat.channels)

            // 启用键盘声检测
            try {
                my_webrtc_apm_set_key_pressed(handle, 0)
                logger.info("WebRTC APM 键盘声检测已启用")
            } catch (e: Exception) {
                logger.warn("启用键盘声检测失败: ${e.message}")
            }

            // 初始化输入重采样器
            if (AudioDefaults.needsSampleRateConversion(inputFormat.sampleRate, apmFormat.sampleRate)) {
                inputResampler = SafeSoxrResampler.createForInput(
                    inputSampleRate = inputFormat.sampleRate,
                    outputSampleRate = apmFormat.sampleRate,
                    channels = inputFormat.channels  // 保持声道数不变，APM处理时再转换
                )
                if (!inputResampler!!.initialize()) {
                    throw Exception("输入重采样器初始化失败")
                }
            }

            // 保存资源
            resources = resourceManager
            apmHandle = handle

            // 标记为完全初始化并启用处理
            isFullyInitialized = true
            processingEnabled = true

            logger.info("WebRTC APM 初始化成功")
            return true

        } catch (e: Exception) {
            logger.error("WebRTC APM 初始化失败: ${e.message}")
            close()
            return false
        }
    }

    suspend fun processFrame(audioData: ShortArray): ShortArray {
        if (!isFullyInitialized || !processingEnabled || apmHandle == null) {
            logger.debug("APM未完全初始化或处理被屏蔽，返回原始数据")
            return audioData
        }

        if (audioData.isEmpty()) {
            return audioData
        }

        // 简单的音量检查
        val maxAmplitude = audioData.take(100).maxOfOrNull { abs(it.toInt()) } ?: 0
        if (maxAmplitude < 1) {
            logger.debug("跳过极低能量音频: 最大振幅=$maxAmplitude")
            return audioData
        }

        try {
            // 第1步：声道转换到APM格式
            val channelConvertedData = if (AudioDefaults.needsChannelConversion(inputFormat.channels, apmFormat.channels)) {
                when {
                    inputFormat.channels == 2 && apmFormat.channels == 1 -> {
                        if (vadLogCounter % 1000 == 0) {
                            logger.debug("声道转换: 2ch -> 1ch")
                        }
                        AudioUtils.stereoToMono(audioData)
                    }
                    inputFormat.channels == 1 && apmFormat.channels == 2 -> {
                        if (vadLogCounter % 1000 == 0) {
                            logger.debug("声道转换: 1ch -> 2ch")
                        }
                        ShortArray(audioData.size * 2) { i -> audioData[i / 2] }
                    }
                    else -> {
                        logger.warn("不支持的声道转换: ${inputFormat.channels}ch -> ${apmFormat.channels}ch")
                        audioData
                    }
                }
            } else {
                audioData
            }

            // 第2步：重采样到APM格式
            val resampledData = if (inputResampler != null) {
                if (vadLogCounter % 1000 == 0) {
                    logger.debug("输入重采样: ${inputFormat.sampleRate}Hz -> ${apmFormat.sampleRate}Hz")
                }
                inputResampler!!.process(channelConvertedData)
            } else {
                channelConvertedData
            }

            // 第3步：APM处理
            val dataSize = resampledData.size
            if (!bufferManager.ensureBufferSize(dataSize, apmFormat.channels)) {
                logger.error("缓冲区分配失败")
                return resampledData
            }

            val inputBuffer = bufferManager.getInputBuffer()
            val outputBuffer = bufferManager.getOutputBuffer()
            val inputArrayPointer = bufferManager.getInputArrayPointer()
            val outputArrayPointer = bufferManager.getOutputArrayPointer()

            if (inputBuffer == null || outputBuffer == null || inputArrayPointer == null || outputArrayPointer == null) {
                logger.error("APM缓冲区为空")
                return resampledData
            }

            // 填充输入缓冲区
            for (i in 0 until dataSize) {
                inputBuffer[i] = (resampledData[i] / 32768f).coerceIn(-1f, 1f)
            }

            // WebRTC APM处理
            try {
                webrtc_apm_process_stream(apmHandle, inputArrayPointer, outputArrayPointer)
            } catch (e: Exception) {
                logger.error("APM处理失败: ${e.message}")
                return resampledData
            }

            // 提取处理结果
            val processedData = ShortArray(dataSize) { i ->
                val floatSample = outputBuffer[i].coerceIn(-1f, 1f)
                (floatSample * 32767f).toInt().toShort()
            }

            // 检查处理后的音频质量
            val maxAmp = processedData.maxOfOrNull { abs(it.toInt()) } ?: 0
            if (maxAmp == 0) {
                logger.warn("APM处理后音频全为0，使用原始数据")
                return resampledData
            }

            if (vadLogCounter++ % 1000 == 0) {
                logger.debug("APM处理完成: 最大振幅=$maxAmp")
            }

            return processedData

        } catch (e: Exception) {
            logger.error("APM处理失败: ${e.message}")
            return audioData
        }
    }

    suspend fun processAndResample(
        audioData: ShortArray,
        outputSampleRate: Int = apmFormat.sampleRate,
        outputChannels: Int = apmFormat.channels
    ): ShortArray {
        // 第1步：APM处理
        val apmProcessedData = processFrame(audioData)

        if (apmProcessedData.isEmpty()) {
            return apmProcessedData
        }

        val targetFormat = AudioDefaults.AudioFormat(outputSampleRate, outputChannels)

        // 如果目标格式与APM格式相同，直接返回
        if (apmFormat.isSameAs(targetFormat)) {
            return apmProcessedData
        }

        // 第2步：输出重采样 - 使用SafeSoxrResampler
        val resampledData = if (AudioDefaults.needsSampleRateConversion(apmFormat.sampleRate, outputSampleRate) || 
                                AudioDefaults.needsChannelConversion(apmFormat.channels, outputChannels)) {
            
            if (vadLogCounter % 500 == 0) {
                logger.debug("输出重采样和声道转换: ${apmFormat.sampleRate}Hz/${apmFormat.channels}ch -> ${outputSampleRate}Hz/${outputChannels}ch")
            }

            // 使用SafeSoxrResampler处理采样率和声道转换
            val outputResampler = SafeSoxrResampler.createForOutput(
                inputSampleRate = apmFormat.sampleRate,
                outputSampleRate = outputSampleRate,
                inputChannels = apmFormat.channels,
                outputChannels = outputChannels
            )
            
            try {
                if (outputResampler.initialize()) {
                    val result = outputResampler.process(apmProcessedData)
                    
                    if (vadLogCounter % 500 == 0) {
                        val maxAmp = result.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
                        logger.debug("输出重采样完成: ${apmProcessedData.size} -> ${result.size}样本, 最大振幅=$maxAmp")
                    }
                    
                    result
                } else {
                    logger.error("输出重采样器初始化失败，使用原始数据")
                    apmProcessedData
                }
            } catch (e: Exception) {
                logger.error("输出重采样失败: ${e.message}，使用原始数据")
                apmProcessedData
            } finally {
                outputResampler.release()
            }
        } else {
            if (vadLogCounter % 1000 == 0) {
                logger.debug("格式相同，跳过输出重采样")
            }
            apmProcessedData
        }
        
        return resampledData
    }

    // VAD和其他方法保持不变...
    fun isVoiceDetected(): Boolean {
        if (!isFullyInitialized || !processingEnabled || apmHandle == null) {
            return false
        }

        val apmVadResult = my_webrtc_apm_voice_detected(apmHandle) == 1
        var finalVadResult = apmVadResult

        // 基本能量检查
        try {
            val inputBuffer = bufferManager.getInputBuffer()
            val bufferSize = bufferManager.getCurrentBufferSize()

            if (inputBuffer != null && bufferSize > 0) {
                var energy = 0.0f
                for (i in 0 until bufferSize) {
                    val sample = inputBuffer[i]
                    energy += sample * sample
                }

                val rmsEnergy = kotlin.math.sqrt(energy / bufferSize)
                if (rmsEnergy < AudioDefaults.MIN_RMS_ENERGY) {
                    finalVadResult = false
                }
            }
        } catch (e: Exception) {
            logger.debug("能量计算异常: ${e.message}")
        }

        // VAD去抖动处理
        if (finalVadResult) {
            consecutiveVadPositive++
            val result = consecutiveVadPositive >= vadDebounceFrames

            if (result != lastVadResult) {
                logger.debug("VAD状态变化: $lastVadResult -> $result")
                lastVadResult = result
            }

            return result
        } else {
            if (consecutiveVadPositive > 0) {
                consecutiveVadPositive = kotlin.math.max(0, consecutiveVadPositive - 2)

                if (consecutiveVadPositive == 0 && lastVadResult) {
                    logger.debug("VAD状态变化: true -> false")
                    lastVadResult = false
                }
            }
            return consecutiveVadPositive >= vadDebounceFrames
        }
    }

    fun calculateEnergy(audioData: ShortArray): Double {
        if (audioData.isEmpty()) return 0.0

        var sum = 0.0
        var maxAbs = 0

        for (sample in audioData) {
            val sampleAbs = abs(sample.toInt())
            if (sampleAbs > maxAbs) maxAbs = sampleAbs
            sum += (sample * sample).toDouble()
        }

        val rms = kotlin.math.sqrt(sum / audioData.size) / Short.MAX_VALUE

        if (maxAbs >= 32767 && vadLogCounter++ % 50 == 0) {
            logger.warn("检测到音频饱和: maxAbs=$maxAbs, rms=$rms")
        }

        return rms
    }

    // 配置方法
    fun setVadThreshold(threshold: Float) {
        logger.info("VAD阈值设置请求: $threshold (注意: WebRTC APM内部VAD阈值不可直接设置)")
    }

    fun setVadDebounceFrames(frames: Int) {
        vadDebounceFrames = frames.coerceAtLeast(1)
        logger.info("VAD去抖动帧数设置为: $vadDebounceFrames")
    }

    fun enableEchoCancellation(enable: Boolean) {
        apmHandle?.let {
            my_webrtc_apm_enable_aec(it, if (enable) 1 else 0)
            logger.info("回声消除${if (enable) "启用" else "禁用"}")
        }
    }

    fun setKeyPressed(keyPressed: Boolean) {
        apmHandle?.let {
            my_webrtc_apm_set_key_pressed(it, if (keyPressed) 1 else 0)
        }
    }

    // 获取器方法
    fun getActualInputSampleRate(): Int = inputFormat.sampleRate
    fun getInputChannels(): Int = inputFormat.channels
    fun getApmSampleRate(): Int = apmFormat.sampleRate
    fun getApmChannels(): Int = apmFormat.channels
    fun getApmHandle(): CPointer<*>? = apmHandle

    suspend fun updateInputParameters(sampleRate: Int, channels: Int) {
        val newFormat = AudioDefaults.AudioFormat(sampleRate, channels)
        if (!inputFormat.isSameAs(newFormat)) {
            logger.info("更新输入参数: 旧($inputFormat) -> 新($newFormat)")
            logger.info("转换路径变化: ${AudioDefaults.getConversionPath(newFormat, apmFormat)}")
            inputFormat = newFormat

            // 重新初始化输入重采样器
            inputResampler?.release()
            inputResampler = null

            if (AudioDefaults.needsSampleRateConversion(inputFormat.sampleRate, apmFormat.sampleRate)) {
                inputResampler = SafeSoxrResampler.createForInput(
                    inputSampleRate = inputFormat.sampleRate,
                    outputSampleRate = apmFormat.sampleRate,
                    channels = inputFormat.channels  // 保持声道数不变，APM处理时再转换
                )
                if (!inputResampler!!.initialize()) {
                    logger.error("重新初始化输入重采样器失败")
                    inputResampler?.release()
                    inputResampler = null
                }
            }

            logger.info("输入参数已更新")
        }
    }

    override fun close() {
        try {
            // 停止处理
            processingEnabled = false
            isFullyInitialized = false

            // 释放重采样器
            inputResampler?.release()
            inputResampler = null

            // 释放缓冲区管理器
            bufferManager.release()

            // 释放主要资源
            resources?.close()
            resources = null
            apmHandle = null

            logger.info("WebRTC APM 资源已释放")
        } catch (e: Exception) {
            logger.error("释放资源失败: ${e.message}")
        }
    }

    // 为了向后兼容，保留release方法
    fun release() {
        close()
    }
}