@file:OptIn(ExperimentalForeignApi::class)

package voice.audio.processing

import com.airobot.webrtcapminterop.APMConfig
import com.airobot.webrtcapminterop.SoxWrapper
import com.airobot.webrtcapminterop.kAgcAdaptiveDigital
import com.airobot.webrtcapminterop.kNsHigh
import com.airobot.webrtcapminterop.my_webrtc_apm_voice_detected
import com.airobot.webrtcapminterop.webrtc_apm_apply_config
import com.airobot.webrtcapminterop.webrtc_apm_create
import com.airobot.webrtcapminterop.webrtc_apm_destroy
import com.airobot.webrtcapminterop.webrtc_apm_prepare
import com.airobot.webrtcapminterop.webrtc_apm_process_stream
import com.airobot.webrtcapminterop.my_webrtc_apm_enable_aec
import com.airobot.webrtcapminterop.soxr_io_spec_create
import com.airobot.webrtcapminterop.soxr_quality_spec_create
import com.airobot.webrtcapminterop.soxr_runtime_spec_create
import com.airobot.webrtcapminterop.soxr_wrapper_create
import com.airobot.webrtcapminterop.soxr_wrapper_create_resampler
import com.airobot.webrtcapminterop.soxr_wrapper_destroy
import com.airobot.webrtcapminterop.soxr_wrapper_process
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.free
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.refTo
import kotlinx.cinterop.set
import voice.util.LogManager

// SOXR常量定义
private const val SOXR_INT16_I = 0u  // 16位整型输入
private const val SOXR_HQ = 2u       // 高质量重采样

/**
 * WebRTC AudioProcessing Module 的 Kotlin 封装
 * 提供降噪、回声消除、自动增益控制和语音活动检测功能
 */
class WebRtcApm {
    private val logger = LogManager.getLogger("WebRtcApm")
    private var apmHandle: CPointer<*>? = null

    private var frameSize: Int = 0
    private var sampleRate: Int = 16000
    private var channels: Int = 1

    // 音频缓冲区
    private var inputFloatBuffer: CPointer<FloatVar>? = null
    private var outputFloatBuffer: CPointer<FloatVar>? = null
    private var inputArrayPointer: CPointer<CPointerVar<FloatVar>>? = null
    private var outputArrayPointer: CPointer<CPointerVar<FloatVar>>? = null

    private var soxrWrapper: CPointer<SoxWrapper>? = null
    private var resamplerInitialized = false
    private var currentInputRate: Int = 0

    /**
     * 初始化 WebRTC APM 处理器
     * @param sampleRate 采样率
     * @param channels 通道数
     * @return 初始化是否成功
     */
    fun initialize(sampleRate: Int, channels: Int): Boolean {
        if (apmHandle != null) {
            logger.warn("WebRTC APM 已经初始化")
            return true
        }

        this.sampleRate = sampleRate
        this.channels = channels
        this.frameSize = sampleRate / 100  // 10ms的音频帧

        try {
            // 创建APM实例
            apmHandle = webrtc_apm_create()
            if (apmHandle == null) {
                logger.error("WebRTC APM 创建失败")
                return false
            }

            // 配置APM
            memScoped {
                val config = alloc<APMConfig>()

                // 配置降噪
                config.noise_suppression.enabled = true
                config.noise_suppression.level = kNsHigh

                // 配置高通滤波器
                config.high_pass_filter.enabled = true

                // 配置自动增益控制
                config.gain_controller.enabled = true
                config.gain_controller.mode = kAgcAdaptiveDigital
                config.gain_controller.target_level_dbfs = 3
                config.gain_controller.compression_gain_db = 9
                config.gain_controller.enable_limiter = true

                // 配置前置放大器
                config.pre_amplifier.enabled = true
                config.pre_amplifier.fixed_gain_factor = 2.0f

                // 配置VAD
                config.voice_detection.enabled = true
                
                // 配置回声消除
                config.echo_canceller.enabled = true
                config.echo_canceller.mobile_mode = false
                config.echo_canceller.enforce_high_pass_filtering = true
                
                // 配置瞬时噪声抑制
                config.transient_suppression.enabled = true
                
                // 配置残余回声检测
                config.residual_echo_detector.enabled = true

                // 应用配置
                webrtc_apm_apply_config(apmHandle, config.ptr)
            }

            // 准备处理
            webrtc_apm_prepare(apmHandle, sampleRate, channels)

            // 分配音频缓冲区
            allocateBuffers()

            logger.info("WebRTC APM 初始化成功: 采样率=${sampleRate}, 通道数=${channels}")
            return true
        } catch (e: Exception) {
            logger.error("WebRTC APM 初始化失败: ${e.message}")
            release()
            return false
        }
    }

    /**
     * 分配音频处理缓冲区
     */
    private fun allocateBuffers() {
        inputFloatBuffer = nativeHeap.allocArray<FloatVar>(frameSize)
        outputFloatBuffer = nativeHeap.allocArray<FloatVar>(frameSize)

        // 创建指向浮点数组的指针数组
        inputArrayPointer = nativeHeap.allocArray<CPointerVar<FloatVar>>(1)
        outputArrayPointer = nativeHeap.allocArray<CPointerVar<FloatVar>>(1)

        // 设置指针数组的内容
        inputArrayPointer!![0] = inputFloatBuffer
        outputArrayPointer!![0] = outputFloatBuffer
    }

    private fun initializeResampler() {
        if (resamplerInitialized) {
            // 如果采样率改变了，需要重新初始化
            if (soxrWrapper != null && currentInputRate != sampleRate) {
                releaseResampler()
            } else {
                return
            }
        }
        
        try {
            // 创建SOXR包装器
            soxrWrapper = soxr_wrapper_create() ?: throw Exception("Failed to create soxr wrapper")
            
            // 设置IO规格 - 使用short类型
            soxr_io_spec_create(SOXR_INT16_I, SOXR_INT16_I, soxrWrapper)
            
            // 设置运行时规格 - 使用1个线程
            soxr_runtime_spec_create(1u, soxrWrapper)
            
            // 设置质量规格 - 使用高质量
            soxr_quality_spec_create(SOXR_HQ, soxrWrapper)
            
            // 创建重采样器
            val result = soxr_wrapper_create_resampler(
                soxrWrapper,
                sampleRate.toDouble(),  // 实际输入采样率
                16000.0  // WebRTC内部处理采样率
            )
            
            if (result != 0) {
                throw Exception("Failed to create resampler")
            }
            
            currentInputRate = sampleRate
            resamplerInitialized = true
            logger.info("SOXR重采样器初始化成功: ${sampleRate}Hz -> 16000Hz")
        } catch (e: Exception) {
            logger.error("初始化SOXR重采样器失败: ${e.message}")
            releaseResampler()
        }
    }
    
    private fun releaseResampler() {
        soxrWrapper?.let {
            soxr_wrapper_destroy(it)
            soxrWrapper = null
        }
        resamplerInitialized = false
    }

    /**
     * 处理音频帧
     * @param audioData 短整型音频数据
     * @return 处理后的短整型音频数据
     */
    fun processFrame(audioData: ShortArray): ShortArray {
        if (apmHandle == null) {
            logger.error("WebRTC APM 未初始化")
            return audioData
        }

        if (audioData.isEmpty()) {
            logger.warn("音频数据为空")
            return audioData
        }

        try {
            // 确保重采样器已初始化
            initializeResampler()
            
            // 如果重采样器初始化失败，直接返回原始数据
            if (!resamplerInitialized || soxrWrapper == null) {
                return audioData
            }
            
            // 分配输出缓冲区 - 16kHz采样率对应的大小
            val resampledSize = (audioData.size * 16000) / sampleRate
            val resampledBuffer = nativeHeap.allocArray<FloatVar>(resampledSize)
            
            try {
                // 执行重采样
                val done = soxr_wrapper_process(
                    wrapper = soxrWrapper,
                    in_data = audioData.refTo(0),
                    in_size = audioData.size.toUInt(),  // 输入帧数
                    out_data = resampledBuffer,
                    out_size = resampledSize.toUInt()    // 输出帧数
                )
                
                if (done == 0U) {
                    logger.error("重采样失败")
                    return audioData
                }
                
                // 计算需要送入 APM 的帧数（10 ms）
                val processSize = minOf(done.toInt(), frameSize)

                // 1) 将重采样后的 float32 PCM (假设幅度范围为 -32768~32767) 归一化到 [-1,1]
                for (i in 0 until processSize) {
                    inputFloatBuffer!![i] = resampledBuffer[i] / 32768f
                }

                // 2) 调用 WebRTC APM 处理
                webrtc_apm_process_stream(apmHandle, inputArrayPointer, outputArrayPointer)

                // 3) 将处理后的 float [-1,1] 转回 ShortArray
                return ShortArray(processSize) { i ->
                    val f = outputFloatBuffer!![i].coerceIn(-1f, 1f)
                    (f * 32767f).toInt().toShort()
                }
            } finally {
                // 释放临时缓冲区
                nativeHeap.free(resampledBuffer.rawValue)
            }
        } catch (e: Exception) {
            logger.error("WebRTC APM 处理音频帧失败: ${e.message}")
            return audioData
        }
    }

    /**
     * 检查是否检测到语音
     * @return 是否检测到语音
     */
    fun isVoiceDetected(): Boolean {
        if (apmHandle == null) {
            logger.error("WebRTC APM 未初始化")
            return false
        }
        // 调用WebRTC APM的语音活动检测函数
        // 由于bool无法生成CInterop的函数签名，所以在yanshee.h中添加了带bool的转换函数, 统一使用my_开头
        return my_webrtc_apm_voice_detected(apmHandle) == 1
    }
    
    /**
     * 启用或禁用回声消除功能
     * @param enable 是否启用回声消除
     */
    fun enableEchoCancellation(enable: Boolean) {
        if (apmHandle == null) {
            logger.error("WebRTC APM 未初始化")
            return
        }
        my_webrtc_apm_enable_aec(apmHandle, if (enable) 1 else 0)
        logger.info("回声消除已${if (enable) "启用" else "禁用"}")
    }

    /**
     * 释放资源
     */
    fun release() {
        try {
            // 释放APM实例
            apmHandle?.let {
                webrtc_apm_destroy(it)
                apmHandle = null
            }

            // 释放重采样器
            releaseResampler()

            // 释放音频缓冲区
            inputFloatBuffer?.let {
                nativeHeap.free(it.rawValue)
                inputFloatBuffer = null
            }
            outputFloatBuffer?.let {
                nativeHeap.free(it.rawValue)
                outputFloatBuffer = null
            }
            inputArrayPointer?.let {
                nativeHeap.free(it.rawValue)
                inputArrayPointer = null
            }
            outputArrayPointer?.let {
                nativeHeap.free(it.rawValue)
                outputArrayPointer = null
            }

            logger.info("WebRTC APM 资源已释放")
        } catch (e: Exception) {
            logger.error("释放WebRTC APM资源时出错: ${e.message}")
        }
    }
} 