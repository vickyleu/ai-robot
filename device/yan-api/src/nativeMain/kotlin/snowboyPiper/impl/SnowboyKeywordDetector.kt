@file:OptIn(
    ExperimentalForeignApi::class, ExperimentalTime::class, ExperimentalTime::class,
    ExperimentalForeignApi::class
)

package snowboyPiper.impl

import com.airobot.device.yanapi.snowboyPiper.config.VoiceAssistantConfig
import com.airobot.device.yanapi.snowboyPiper.interfaces.AudioAnalyzer
import com.airobot.device.yanapi.snowboyPiper.interfaces.VoiceStateManager
import com.airobot.piperinterop.SOXR_FLOAT32_I
import com.airobot.piperinterop.soxr_io_spec_create
import com.airobot.piperinterop.soxr_quality_spec_create
import com.airobot.piperinterop.soxr_wrapper_create
import com.airobot.piperinterop.soxr_wrapper_create_resampler
import com.airobot.piperinterop.soxr_wrapper_destroy
import com.airobot.piperinterop.soxr_wrapper_process
import com.airobot.snowboyinterop.SnowboyDetectWrapper
import com.airobot.snowboyinterop.snowboy_apply_frontend
import com.airobot.snowboyinterop.snowboy_bits_per_sample
import com.airobot.snowboyinterop.snowboy_create
import com.airobot.snowboyinterop.snowboy_free
import com.airobot.snowboyinterop.snowboy_num_channels
import com.airobot.snowboyinterop.snowboy_run_detection_int16
import com.airobot.snowboyinterop.snowboy_sample_rate
import com.airobot.snowboyinterop.snowboy_set_audio_gain
import com.airobot.snowboyinterop.snowboy_set_sensitivity
import kotlinx.cinterop.CArrayPointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import platform.posix.perror
import platform.posix.stat
import snowboyPiper.impl.VoskSpeechService.Companion.executeCommand
import snowboyPiper.interfaces.AudioPlayer
import snowboyPiper.interfaces.KeywordDetector
import snowboyPiper.interfaces.KeywordDetector.DetectorState
import snowboyPiper.interfaces.KeywordDetector.DetectorState.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Snowboy关键词检测器实现
 * 负责初始化和运行Snowboy关键词检测
 */
class SnowboyKeywordDetector(
    private val audioAnalyzer: AudioAnalyzer,
    private val voiceStateManager: VoiceStateManager
) : KeywordDetector {


    // 检测器实例
    private var snowboyDetector: CPointer<SnowboyDetectWrapper>? = null

    // 检测状态
    private val _detectionState = MutableStateFlow(KeywordDetector.DetectionState.IDLE)
    override val detectionState: StateFlow<KeywordDetector.DetectionState> =
        _detectionState.asStateFlow()

    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Default)

    // 去抖动控制
    private var lastDetectionTime = 0L
    private val debounceTimeMs = 500L // 0.5秒去抖动时间

    // 存储初始化参数，用于可能的重新初始化
    private var lastResourcePath = ""
    private var lastModelPath = ""
    private var lastSensitivity = VoiceAssistantConfig.snowboySensitivity

    /**
     * 初始化检测器
     * @param resourcePath 资源文件路径
     * @param modelPath 模型文件路径
     * @param sensitivity 灵敏度，范围0-1
     * @return 初始化是否成功
     */
    override fun initialize(resourcePath: String, modelPath: String, sensitivity: Float): Boolean {
        // 保存参数以便重新初始化
        lastResourcePath = resourcePath
        lastModelPath = modelPath
        lastSensitivity = sensitivity

        _detectionState.value = KeywordDetector.DetectionState.INITIALIZING

        try {
            snowboyDetector = snowboy_create(resourcePath, modelPath)
            if (snowboyDetector == null) {
                println("[ERROR] Snowboy检测器创建失败")
                _detectionState.value = KeywordDetector.DetectionState.ERROR
                return false
            }
            
            // 使用最高灵敏度，确保检测到唤醒词
            val actualSensitivity = 0.15f
            
            // 灵敏度范围为0-1，值越高越容易检测到关键词，但可能增加误检率
            println("[INFO] 设置灵敏度 ${actualSensitivity}")
            snowboy_set_sensitivity(snowboyDetector, actualSensitivity.toString())
            
            // 设置高音频增益，确保信号足够强
            snowboy_set_audio_gain(snowboyDetector, 1.5f)
            
            // 关闭前端处理
            snowboy_apply_frontend(snowboyDetector, 1)
            
            // 验证灵敏度设置是否生效
            println("[DEBUG] 灵敏度设置完成，准备进行模型验证")

            // 检查模型是否正确加载
            scope.launch {
                // 验证模型文件大小和权限
                val checkModelSizeCmd = "ls -la $modelPath"
                val modelSizeInfo = executeCommand(checkModelSizeCmd).trim()
                println("[INFO] 模型文件信息: $modelSizeInfo")
                // 检查模型文件内容格式
                val checkModelFormatCmd = "file $modelPath"
                val modelFormatInfo = executeCommand(checkModelFormatCmd).trim()
                println("[INFO] 模型文件格式: $modelFormatInfo")
            }
            println("[INFO] Snowboy检测器初始化成功")

            _detectionState.value = KeywordDetector.DetectionState.LISTENING
            return true
        } catch (e: Exception) {
            println("[ERROR] Snowboy初始化异常: ${e.message}")
            e.printStackTrace()
            _detectionState.value = KeywordDetector.DetectionState.ERROR
            return false
        }
    }

    /**
     * 检测关键词
     * @param buffer 音频数据缓冲区
     * @param frameCount 帧数
     * @return 检测结果，大于0表示检测到关键词，0表示未检测到，负值表示错误
     */
    override fun detect(
        player: AudioPlayer,
        buffer: ShortArray,
        frameCount: Int,
        sampleRate: Int,
        channels: Int
    ): DetectorState {
        if (snowboyDetector == null) {
            println("[ERROR] Snowboy检测器未初始化")
            return ERROR
        }

        // 添加调试日志
        println("[DEBUG] SnowboyKeywordDetector.detect 被调用，帧数: $frameCount")

        if (_detectionState.value != KeywordDetector.DetectionState.LISTENING) {
            _detectionState.value = KeywordDetector.DetectionState.LISTENING
        }

        try {
            // 计算音频能量
            var sumSquares = 0.0
            for (sample in buffer) {
                sumSquares += (sample * sample)
            }
            val rms = kotlin.math.sqrt(sumSquares / frameCount)
            
            // 如果能量非常低，直接忽略
            if (rms < 150.0) {
                println("[DEBUG] 音频能量极低，忽略: $rms")
                return NoEvent
            }
            
            // 计算过零率
            var zeroCrossings = 0
            for (i in 1 until frameCount) {
                if ((buffer[i] > 0 && buffer[i-1] <= 0) ||
                    (buffer[i] <= 0 && buffer[i-1] > 0)) {
                    zeroCrossings++
                }
            }
            val zcr = zeroCrossings.toDouble() / frameCount
            
            // 过零率检查 - 只过滤明显的非语音噪音（如高频机械噪声）
            if (zcr > 0.5) {
                println("[DEBUG] 过零率极高，可能是机械噪音: $zcr")
                return NoEvent
            }
            
            println("[DEBUG] 音频通过初步特征检查，RMS: $rms, ZCR: $zcr")
            
            val bufferPtr = nativeHeap.allocArray<ShortVar>(frameCount)
            
            // 使用适中的增益
            for (i in 0 until frameCount) {
//                val gain = 0.3f
//                val ampValue = buffer[i].toInt() * gain
//                bufferPtr[i] = kotlin.math.max(-32768, kotlin.math.min(32767, ampValue.toInt())).toShort()
                bufferPtr[i] = buffer[i]
            }

            // 检测音频格式要求
            val requiredSampleRate = snowboy_sample_rate(snowboyDetector)
            val requiredChannels = snowboy_num_channels(snowboyDetector)
            val requiredBitsPerSample = snowboy_bits_per_sample(snowboyDetector)
            println("[DEBUG] Snowboy要求：采样率=$requiredSampleRate Hz, 通道数=$requiredChannels, 位深=$requiredBitsPerSample bit")
            println("[DEBUG] 当前音频：采样率=$sampleRate Hz, 通道数=$channels, 位深=16 bit")
            
            // 执行转码（如果需要）
            val (finalBufferPtr, outputSize) = if (sampleRate != requiredSampleRate || channels != requiredChannels) {
                println("[DEBUG] 需要转码，从 $sampleRate/$channels 到 $requiredSampleRate/$requiredChannels")
                val bufferTrans = transcoding(frameCount, bufferPtr, sampleRate, requiredSampleRate)
                if (bufferTrans == null) {
                    println("[ERROR] 转码失败")
                    nativeHeap.free(bufferPtr.rawValue)
                    return ERROR
                }
                bufferTrans
            } else {
                println("[DEBUG] 音频格式匹配，无需转码")
                bufferPtr to frameCount
            }
            
            // 直接执行关键词检测
            println("[DEBUG] 执行关键词检测，输入大小: $outputSize")
            val result = snowboy_run_detection_int16(snowboyDetector, finalBufferPtr, outputSize, 0)
            println("[DEBUG] 检测结果: $result")
            
            // 释放缓冲区
            if (finalBufferPtr != bufferPtr) {
                nativeHeap.free(finalBufferPtr.rawValue)
            }
            nativeHeap.free(bufferPtr.rawValue)
            
            // 处理检测结果
            if (result > 0) {
                // 检测到关键词
                println("[INFO] 检测到关键词！结果值: $result")
                
                // 最基本的验证 - 只过滤极端情况
                if (rms > 10000.0) {
                    println("[DEBUG] 音频能量过高，可能是非语音噪音: $rms")
                    return NoEvent
                }
                
                // 去抖动
                val currentTime = Clock.System.now().toEpochMilliseconds()
                if (currentTime - lastDetectionTime < debounceTimeMs) {
                    println("[INFO] 在去抖动期间内，忽略此次检测")
                    return NoEvent
                }
                
                // 更新状态
                lastDetectionTime = currentTime
                _detectionState.value = KeywordDetector.DetectionState.DETECTED
                             
                // 返回检测结果
                return DetectorState.fromValue(result)
            }
            
            // 处理其他结果
            return when (result) {
                -2 -> {
                    println("[DEBUG] 检测到静音")
                    Silence
                }
                -1 -> {
                    println("[ERROR] 检测器错误")
                    ERROR
                }
                0 -> {
                    println("[DEBUG] 未检测到关键词")
                    NoEvent
                }
                else -> NoEvent
            }
        } catch (e: Exception) {
            println("[ERROR] 关键词检测异常: ${e.message}")
            e.printStackTrace()
            _detectionState.value = KeywordDetector.DetectionState.ERROR
            return ERROR
        }
    }

    private fun transcoding(
        frameCount: Int,
        bufferPtr: CArrayPointer<ShortVar>,
        sampleRate: Int,
        requiredSampleRate: Int
    ): Pair<CArrayPointer<ShortVar>,Int>? {
        // 使用soxr c api 进行音频转换
        println("[WARN] 采样率或通道数不匹配，使用soxr进行转换\n")
        // 1. 将short转换为float用于soxr处理（可选，取决于您的soxr配置）
        val floatInput = nativeHeap.allocArray<FloatVar>(frameCount)
        for (i in 0 until frameCount) {
            floatInput[i] = (bufferPtr[i]).toFloat() / 32768.0f
        }
        // 2. 计算输出缓冲区大小
        val outputSize =
            ((frameCount.toDouble() * sampleRate) / requiredSampleRate + 0.5).toInt()
        val floatOutput = nativeHeap.allocArray<FloatVar>(outputSize)
        // 3. 配置soxr
        val wrapper = soxr_wrapper_create()
        if (wrapper == null) {
            println("[ERROR] 音频转码失败")
            return null
        }
        soxr_io_spec_create(SOXR_FLOAT32_I, SOXR_FLOAT32_I, wrapper)
        soxr_quality_spec_create(SOXR_FLOAT32_I, wrapper) // 使用最高质量
        // 4. 创建soxr重采样器
        soxr_wrapper_create_resampler(
            wrapper, sampleRate.toDouble(), sampleRate.toDouble()
        )
        if (wrapper.pointed.soxr == null) {
            println("[ERROR] 创建soxr失败")
            return null
        }
        // 5. 执行重采样
        soxr_wrapper_process(
            wrapper,
            in_data = bufferPtr,
            in_size = frameCount.toUInt(),
            out_data = floatOutput,
            out_size = outputSize.toUInt(),
        )
        val resampledBuffer = nativeHeap.allocArray<ShortVar>(outputSize)
        // 6. 将float转换回short
        for (i in 0 until outputSize) {
            var sample = floatOutput[i]
            // 限制在[-1.0, 1.0]范围内防止截断失真
            if (sample > 1.0f) sample = 1.0f
            if (sample < -1.0f) sample = -1.0f
            resampledBuffer[i] = (sample * 32767.0f).toInt().toShort()
        }
        // 7. 释放soxr资源
        nativeHeap.free(floatInput.rawValue)
        nativeHeap.free(floatOutput.rawValue)
        soxr_wrapper_destroy(wrapper)
        println("[INFO] 音频转换完成，输出大小: $outputSize 样本")
        return resampledBuffer to outputSize
    }

    /**
     * 计算音频的能量模式得分，用于判断是否符合唤醒词特征
     * 唤醒词通常有明显的能量变化模式，如"小度小度"有明显的两段能量峰值
     */
    private fun calculateEnergyPattern(audioData: ShortArray): Double {
        // 将音频分成多个小段，计算每段的能量
        val segmentCount = 12 // 分12段，可以捕捉"小度小度"的音节变化
        val segmentSize = audioData.size / segmentCount
        val segmentEnergies = DoubleArray(segmentCount)
        
        // 计算每段的能量
        for (i in 0 until segmentCount) {
            var energy = 0.0
            val start = i * segmentSize
            val end = minOf((i + 1) * segmentSize, audioData.size)
            
            for (j in start until end) {
                energy += audioData[j] * audioData[j]
            }
            segmentEnergies[i] = energy / (end - start)
        }
        
        // 标准化能量值
        val maxEnergy = segmentEnergies.maxOrNull() ?: 1.0
        if (maxEnergy > 0) {
            for (i in segmentEnergies.indices) {
                segmentEnergies[i] = segmentEnergies[i] / maxEnergy
            }
        }
        
        // 检查能量模式是否符合唤醒词特征 - "小度小度"通常有两个能量峰值
        // 计算能量峰值数量和位置
        var peakCount = 0
        val peakPositions = mutableListOf<Int>()
        
        for (i in 1 until segmentCount - 1) {
            if (segmentEnergies[i] > 0.6 && // 能量峰值必须足够高
                segmentEnergies[i] > segmentEnergies[i-1] && 
                segmentEnergies[i] > segmentEnergies[i+1]) {
                peakCount++
                peakPositions.add(i)
            }
        }
        
        // 计算模式匹配得分
        var patternScore = 0.0
        
        // 理想的"小度小度"应该有2-4个能量峰值，且峰值之间有一定间隔
        if (peakCount >= 2 && peakCount <= 4) {
            patternScore += 0.5 // 基础分
            
            // 检查峰值间隔是否合理
            if (peakPositions.size >= 2) {
                for (i in 0 until peakPositions.size - 1) {
                    val gap = peakPositions[i+1] - peakPositions[i]
                    // 合理的间隔应该在1-5段之间
                    if (gap >= 1 && gap <= 5) {
                        patternScore += 0.25
                    }
                }
            }
        }
        
        // 记录能量模式信息
        println("[DEBUG] 能量模式分析: 峰值数=$peakCount, 峰值位置=$peakPositions, 匹配得分=$patternScore")
        return patternScore
    }
    
    /**
     * 根据能量模式判断是否符合唤醒词特征
     */
    private fun hasWakewordEnergyPattern(audioData: ShortArray): Boolean {
        val energyPatternScore = calculateEnergyPattern(audioData)
        // 要求至少达到0.7的匹配度
        return energyPatternScore >= 0.7
    }

    /**
     * 检查音频是否具有人声的频谱特征模式
     */
    private fun hasHumanVoicePattern(buffer: ShortArray, frameCount: Int): Boolean {
        // 暂时放宽检测标准，默认接受所有音频
        println("[DEBUG] 人声模式检测：暂时放宽标准，接受所有音频")
        return true;
        
        /*
        // 将音频分为多个子帧
        val subFrameSize = 1024
        val subFrameCount = frameCount / subFrameSize
        
        if (subFrameCount < 3) {
            return true // 帧太短，无法进行充分分析
        }
        
        // 计算每个子帧的能量
        val subFrameEnergies = DoubleArray(subFrameCount)
        for (i in 0 until subFrameCount) {
            var energy = 0.0
            val startIdx = i * subFrameSize
            val endIdx = minOf((i + 1) * subFrameSize, frameCount)
            
            for (j in startIdx until endIdx) {
                energy += buffer[j] * buffer[j]
            }
            subFrameEnergies[i] = energy / (endIdx - startIdx)
        }
        
        // 计算能量变化模式 - 人声通常有明显的能量波动
        var energyVariations = 0
        for (i in 1 until subFrameCount) {
            val ratio = subFrameEnergies[i] / subFrameEnergies[i-1]
            if (ratio < 0.5 || ratio > 2.0) {
                energyVariations++
            }
        }
        
        // 人声通常有足够的能量变化
        val hasEnoughVariations = energyVariations >= subFrameCount / 5
        
        // 人声通常不会有突然的能量峰值
        var hasSuddenPeaks = false
        for (i in 1 until subFrameCount - 1) {
            val peakRatio1 = subFrameEnergies[i] / subFrameEnergies[i-1]
            val peakRatio2 = subFrameEnergies[i] / subFrameEnergies[i+1]
            
            if (peakRatio1 > 10.0 && peakRatio2 > 10.0) {
                hasSuddenPeaks = true
                break
            }
        }
        
        // 键盘敲击通常有突然的能量峰值，然后快速下降
        // 人声通常有更平滑的能量变化
        return hasEnoughVariations && !hasSuddenPeaks
        */
    }

    /**
     * 释放资源
     */
    override fun release() {
        try {
            snowboyDetector?.let {
                snowboy_free(it)
                println("[INFO] Snowboy资源已释放")
            }
            snowboyDetector = null
            _detectionState.value = KeywordDetector.DetectionState.IDLE
        } catch (e: Exception) {
            println("[WARN] 释放Snowboy资源时出错: ${e.message}")
            _detectionState.value = KeywordDetector.DetectionState.ERROR
        }
    }
}