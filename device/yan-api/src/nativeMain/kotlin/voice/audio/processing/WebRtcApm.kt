@file:OptIn(ExperimentalForeignApi::class)

package voice.audio.processing

import com.airobot.webrtcapminterop.APMConfig
import com.airobot.webrtcapminterop.kAgcAdaptiveDigital
import com.airobot.webrtcapminterop.kNsHigh
import com.airobot.webrtcapminterop.my_webrtc_apm_voice_detected
import com.airobot.webrtcapminterop.webrtc_apm_apply_config
import com.airobot.webrtcapminterop.webrtc_apm_create
import com.airobot.webrtcapminterop.webrtc_apm_destroy
import com.airobot.webrtcapminterop.webrtc_apm_prepare
import com.airobot.webrtcapminterop.webrtc_apm_process_stream
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.free
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import voice.util.LogManager

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

        if (audioData.size < frameSize) {
            logger.warn("音频数据过短，跳过处理: ${audioData.size} < ${frameSize}")
            return audioData
        }

        try {
            // 将短整型数据转换为浮点型
            for (i in 0 until frameSize) {
                inputFloatBuffer!![i] = audioData[i].toFloat() / 32768.0f
            }

            // 处理音频数据
            webrtc_apm_process_stream(apmHandle, inputArrayPointer, outputArrayPointer)

            // 将浮点型数据转换回短整型
            val result = ShortArray(frameSize) { i ->
                val sample = (outputFloatBuffer!![i] * 32768.0f).toInt()
                when {
                    sample > Short.MAX_VALUE -> Short.MAX_VALUE
                    sample < Short.MIN_VALUE -> Short.MIN_VALUE
                    else -> sample.toShort()
                }
            }

            return result
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
     * 释放资源
     */
    fun release() {
        try {
            // 释放APM实例
            apmHandle?.let {
                webrtc_apm_destroy(it)
                apmHandle = null
            }

            // 释放音频缓冲区
            inputFloatBuffer?.let {
                nativeHeap.free(it)
                inputFloatBuffer = null
            }
            outputFloatBuffer?.let {
                nativeHeap.free(it)
                outputFloatBuffer = null
            }
            inputArrayPointer?.let {
                nativeHeap.free(it)
                inputArrayPointer = null
            }
            outputArrayPointer?.let {
                nativeHeap.free(it)
                outputArrayPointer = null
            }

            logger.info("WebRTC APM 资源已释放")
        } catch (e: Exception) {
            logger.error("释放WebRTC APM资源时出错: ${e.message}")
        }
    }
} 