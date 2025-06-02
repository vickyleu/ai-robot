// SafeSoxrResampler.kt

@file:OptIn(ExperimentalForeignApi::class)
package voice.audio.processing

import com.airobot.webrtcapminterop.*
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.*
import voice.util.AudioDefaults
import voice.util.LogManager

class SafeSoxrResampler(
    private val inputSampleRate: Int,
    private val outputSampleRate: Int,
    private val inputChannels: Int,
    private val outputChannels: Int,
    private val inputFormat: Int = SOXR_INT16_I.toInt(),
    private val outputFormat: Int = SOXR_INT16_I.toInt(),
    private val quality: Int = SOXR_LQ.toInt()
) {
    private val logger = LogManager.getLogger("SafeSoxrResampler")
    private var soxrWrapper: CPointer<SoxWrapper>? = null
    private var isInitialized = false
    private val instanceLock = SynchronizedObject()

    fun initialize(): Boolean = synchronized(instanceLock) {
        if (isInitialized) return true
        
        if (inputSampleRate == outputSampleRate && inputChannels == outputChannels) {
            isInitialized = true
            return true
        }
        synchronized(globalSoxrLock) {
            soxrWrapper = soxr_wrapper_create()
            if (soxrWrapper == null) {
                logger.error("创建失败")
                return false
            }
            soxr_io_spec_create(inputFormat.toUInt(), outputFormat.toUInt(), soxrWrapper)
            soxr_runtime_spec_create(1u, soxrWrapper)
            soxr_quality_spec_create(quality.toUInt(), soxrWrapper)
            if (soxr_wrapper_create_resampler(
                    soxrWrapper,
                    inputSampleRate.toDouble(),
                    outputSampleRate.toDouble(),
                    inputChannels.toUInt()
                ) != 0
            ) {
                logger.error("创建重采样器失败")
                release()
                return false
            }
        }
        isInitialized = true
        return true
    }

    fun process(inputData: ShortArray): ShortArray = synchronized(instanceLock) {
        if (inputData.isEmpty()) return inputData
        if (!isInitialized) {
            logger.error("未初始化")
            return inputData
        }
        try {
            val inputMaxAmp = inputData.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
            val inputNonZeroCount = inputData.count { it != 0.toShort() }
            val inputZeroRatio = (inputData.size - inputNonZeroCount).toFloat() / inputData.size
            val channelData = convertChannels(inputData, inputChannels, outputChannels)
            
            val finalData = if (inputSampleRate != outputSampleRate) {
                val resampled = resampleAudio(channelData)
                val resampleNonZero = resampled.count { it != 0.toShort() }
                val resampleZeroRatio = (resampled.size - resampleNonZero).toFloat() / resampled.size
                if (resampleZeroRatio > AudioDefaults.VOICE_PROBABILITY_HIGH_THRESHOLD) {
                    logger.error("重采样后零值过多")
                }
                resampled
            } else channelData
            val finalNonZero = finalData.count { it != 0.toShort() }
            val finalZeroRatio = (finalData.size - finalNonZero).toFloat() / finalData.size
            
            if (finalZeroRatio > AudioDefaults.VAD_OPTIMIZATION_SILENCE_TRIGGER_THRESHOLD) {
                logger.warn("最终零值过多")
            }
            return finalData
        } catch (e: Exception) {
            logger.error("处理失败")
            return inputData
        }
    }

    private fun convertChannels(inputData: ShortArray, fromCh: Int, toCh: Int): ShortArray {
        if (fromCh == toCh) return inputData
        return when {
            fromCh == AudioDefaults.WEBRTC_APM_CHANNELS && toCh == AudioDefaults.INPUT_DEVICE_CHANNELS -> {
                val outSize = inputData.size * AudioDefaults.INPUT_DEVICE_CHANNELS
                ShortArray(outSize) { i ->
                    val idx = i / AudioDefaults.INPUT_DEVICE_CHANNELS
                    if (idx < inputData.size) inputData[idx] else 0
                }
            }
            fromCh == AudioDefaults.INPUT_DEVICE_CHANNELS && toCh == AudioDefaults.WEBRTC_APM_CHANNELS -> {
                val outSize = inputData.size / AudioDefaults.INPUT_DEVICE_CHANNELS
                ShortArray(outSize) { i ->
                    val l = i * AudioDefaults.INPUT_DEVICE_CHANNELS
                    val r = l + 1
                    if (r < inputData.size) {
                        val left = inputData[l].toInt()
                        val right = inputData[r].toInt()
                        ((left + right) / AudioDefaults.INPUT_DEVICE_CHANNELS)
                            .coerceIn(AudioDefaults.PCM_16BIT_MIN, AudioDefaults.PCM_16BIT_MAX)
                            .toShort()
                    } else if (l < inputData.size) {
                        inputData[l]
                    } else 0
                }
            }
            else -> {
                logger.warn("不支持: ${fromCh}ch->${toCh}ch")
                inputData
            }
        }
    }

    private fun resampleAudio(inputData: ShortArray): ShortArray = memScoped {
        if (soxrWrapper == null) {
            logger.error("包装器为空")
            return simpleResample(inputData)
        }
        if (inputData.isEmpty()) {
            logger.warn("输入空")
            return ShortArray(0)
        }
        if (inputData.size > AudioDefaults.WEBRTC_APM_MAX_BUFFER_SIZE) {
            logger.error("输入过大")
            return simpleResample(inputData)
        }
        val inputMaxAmp = inputData.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
        val inputNonZero = inputData.count { it != 0.toShort() }
        val inputZeroRatio = (inputData.size - inputNonZero).toFloat() / inputData.size
        val ratio = outputSampleRate.toDouble() / inputSampleRate.toDouble()
        if (ratio <= 0.0 || ratio > 10.0) {
            logger.error("比例异常: $ratio")
            return simpleResample(inputData)
        }
        val useSimple = when {
            inputZeroRatio > AudioDefaults.VAD_OPTIMIZATION_SILENCE_TRIGGER_THRESHOLD -> {
                logger.info("零值过多")
                true
            }
            inputMaxAmp < (AudioDefaults.MIN_EFFECTIVE_AMPLITUDE / 10) -> {
                logger.info("信号过弱")
                true
            }
            inputData.size < (AudioDefaults.CALLBACK_BUFFER_SIZE / AudioDefaults.BUFFER_SIZE_DIVISOR) -> {
                logger.info("数据过短")
                true
            }
            else -> {
                logger.info("使用SOXR")
                false
            }
        }
        if (useSimple) return simpleResample(inputData)
        val safeInput = ShortArray(inputData.size) { i ->
            val s = inputData[i].toInt().coerceIn(AudioDefaults.SAFE_PCM_MIN, AudioDefaults.SAFE_PCM_MAX)
            val a = (s * AudioDefaults.PRE_AMPLIFIER_FIXED_GAIN_FACTOR * AudioDefaults.SAFE_GAIN_FACTOR).toInt()
            a.coerceIn(AudioDefaults.PCM_16BIT_MIN, AudioDefaults.PCM_16BIT_MAX).toShort()
        }
        val expectedOut = (safeInput.size * ratio).toInt()
        val outSize = (expectedOut * 1.2).toInt()
            .coerceAtLeast(safeInput.size)
            .coerceAtMost(AudioDefaults.WEBRTC_APM_MAX_BUFFER_SIZE)
        if (outSize <= 0 || outSize > AudioDefaults.WEBRTC_APM_MAX_BUFFER_SIZE) {
            logger.error("缓冲区大小无效: $outSize")
            return simpleResample(inputData)
        }
        val outputBuffer = allocArray<ShortVar>(outSize)
        val frames = synchronized(globalSoxrLock) {
            soxr_wrapper_process_short_to_short(
                wrapper = soxrWrapper,
                in_data = safeInput.refTo(0),
                in_size = safeInput.size.toUInt(),
                out_data = outputBuffer,
                out_size = outSize.toUInt()
            )
        }
        if (frames == 0U || frames.toInt() > outSize) {
            logger.error("SOXR 失败或帧数异常")
            return simpleResample(inputData)
        }
        val actual = frames.toInt()
        val result = ShortArray(actual) { i ->
            val raw = outputBuffer[i]
            if (kotlin.math.abs(raw.toInt()) > AudioDefaults.SAFE_PCM_MAX) 0 else raw
        }
        val nonZero = result.count { it != 0.toShort() }
        val zeroRatio = if (result.isNotEmpty()) (result.size - nonZero).toFloat() / result.size else 1f
        if (zeroRatio > 0.95f && inputZeroRatio < AudioDefaults.VOICE_PROBABILITY_HIGH_THRESHOLD) {
            logger.warn("结果异常: 零值过高")
            return simpleResample(inputData)
        }
        if (result.maxOfOrNull { kotlin.math.abs(it.toInt()) } == 0 &&
            inputMaxAmp > (AudioDefaults.MIN_EFFECTIVE_AMPLITUDE * 10)
        ) {
            logger.warn("输出全零但输入强")
            return simpleResample(inputData)
        }
        logger.info("SOXR成功")
        result
    }

    private fun simpleResample(inputData: ShortArray): ShortArray {
        if (inputSampleRate == outputSampleRate) return inputData
        val ratio = outputSampleRate.toDouble() / inputSampleRate.toDouble()
        val outSize = (inputData.size * ratio).toInt()
        logger.info("简单插值")
        return ShortArray(outSize) { i ->
            val idx = i / ratio
            val lo = idx.toInt()
            val hi = (lo + 1).coerceAtMost(inputData.size - 1)
            val frac = idx - lo
            if (lo >= inputData.size) 0 else {
                val l = inputData[lo].toFloat()
                val u = inputData[hi].toFloat()
                (l + (u - l) * frac).toInt()
                    .coerceIn(AudioDefaults.PCM_16BIT_MIN, AudioDefaults.PCM_16BIT_MAX)
                    .toShort()
            }
        }
    }

    fun release() = synchronized(instanceLock) {
        synchronized(globalSoxrLock) {
            soxrWrapper?.let {
                soxr_wrapper_destroy(it)
                soxrWrapper = null
            }
        }
        isInitialized = false
    }

    fun needsProcessing(): Boolean {
        return inputSampleRate != outputSampleRate || inputChannels != outputChannels
    }

    companion object {
        private val globalSoxrLock = SynchronizedObject()
        fun createForInput(
            inputSampleRate: Int,
            outputSampleRate: Int,
            channels: Int
        ): SafeSoxrResampler {
            return SafeSoxrResampler(
                inputSampleRate = inputSampleRate,
                outputSampleRate = outputSampleRate,
                inputChannels = channels,
                outputChannels = channels,
                inputFormat = SOXR_INT16_I.toInt(),
                outputFormat = SOXR_INT16_I.toInt(),
                quality = SOXR_LQ.toInt()
            )
        }
        fun createForOutput(
            inputSampleRate: Int,
            outputSampleRate: Int,
            inputChannels: Int,
            outputChannels: Int
        ): SafeSoxrResampler {
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
        fun createForPlayback(
            inputSampleRate: Int,
            outputSampleRate: Int,
            inputChannels: Int,
            outputChannels: Int
        ): SafeSoxrResampler {
            return SafeSoxrResampler(
                inputSampleRate = inputSampleRate,
                outputSampleRate = outputSampleRate,
                inputChannels = inputChannels,
                outputChannels = outputChannels,
                inputFormat = SOXR_INT16_I.toInt(),
                outputFormat = SOXR_INT16_I.toInt(),
                quality = 2u.toInt()
            )
        }
    }
}
