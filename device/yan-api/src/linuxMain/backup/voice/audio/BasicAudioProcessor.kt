@file:OptIn(ExperimentalForeignApi::class)

package com.airobot.device.yanapi.voice.audio

import com.airobot.alsainterop.snprintf
import com.airobot.device.yanapi.voice.interfaces.AudioProcessor
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.pow

/**
 * 基础音频处理器实现
 * 提供基本的音频处理功能，如增益控制、降噪、音量调整等
 */
class BasicAudioProcessor : AudioProcessor {
    // 处理参数
    private var gain = 3.0f       // 大幅提高增益，使语音信号更强
    private var noiseGate = 60    // 大幅降低噪声门限，允许更多微弱信号通过
    private var lowPass = 0.7f    // 降低低通滤波系数，保留更多高频细节
    private var dynamicRangeCompression = 0.4f // 降低动态范围压缩，保留更多细节
    private var attackTime = 0.01f // 压缩器启动时间
    private var releaseTime = 0.1f // 压缩器释放时间
    
    // 状态变量
    private var previousSample = 0.0f
    private var compressorLevel = 0.0f
    private var isEnabled = true
    
    // 高通滤波器状态
    private var hpfPreviousInput = 0.0f
    private var hpfPreviousOutput = 0.0f
    private var hpfAlpha = 0.85f  // 显著降低高通滤波系数，保留更多人声低频成分
    
    // 音频增强和唤醒词增强特定变量
    private var wakewordOptimized = true // 默认启用唤醒词增强
    private var sampleCount = 0 // 用于跟踪处理的样本数
    private var audioStats = AudioStats() // 用于统计音频特征

    /**
     * 处理音频数据
     * @param audioData 原始音频数据
     * @return 处理后的音频数据
     */
    override fun processAudio(audioData: ShortArray): ShortArray {
        if (!isEnabled) {
            return audioData
        }
        
        val processedData = ShortArray(audioData.size)
        
        // 计算RMS和音频特征用于动态增益调整
        var sumSquares = 0.0
        var sum = 0.0
        var max = Short.MIN_VALUE.toDouble()
        var min = Short.MAX_VALUE.toDouble()
        var zcrCount = 0
        
        for (i in audioData.indices) {
            if (i > 0 && ((audioData[i] >= 0 && audioData[i-1] < 0) || 
                           (audioData[i] < 0 && audioData[i-1] >= 0))) {
                zcrCount++
            }
            val sampleValue = audioData[i].toDouble()
            sumSquares += (sampleValue * sampleValue)
            sum += sampleValue
            if (sampleValue > max) max = sampleValue
            if (sampleValue < min) min = sampleValue
        }
        
        val rms = kotlin.math.sqrt(sumSquares / audioData.size)
        val avg = sum / audioData.size
        val zcr = zcrCount.toDouble() / (audioData.size - 1)
        
        // 更新音频统计
        audioStats.update(rms, zcr, max, min)
        sampleCount += audioData.size
        
        // 检测是否可能是唤醒词的特征
        val possibleWakeword = isPossibleWakewordPattern(rms, zcr)
        
        // 动态增益调整 - 针对不同情况自适应调整
        var effectiveGain = gain
        
        if (possibleWakeword && wakewordOptimized) {
            // 唤醒词优化模式 - 显著提升信号增益
            effectiveGain = gain * 2.8f
            // 偶尔打印唤醒词优化信息，避免刷屏
            if (sampleCount % 12000 == 0) {
                println("[增强] 应用唤醒词优化增益: ${effectiveGain.format(1)}, RMS=${rms.toInt()}, ZCR=${zcr.format(3)}")
            }
        } else if (rms < 300) {
            // 弱信号增强
            effectiveGain = gain * 2.5f
        } else if (rms < 800) {
            // 中等信号增强
            effectiveGain = gain * 1.8f
        }
        
        // 应用处理
        for (i in audioData.indices) {
            // 获取样本并转换为归一化浮点值(-1.0 - 1.0)
            val sample = audioData[i] / 32768.0f
            
            // 1. 应用自适应高通滤波器 - 根据声音特征动态调整
            // 唤醒词通常在中频，普通噪音多在低频，语音细节在高频
            val effectiveHpfAlpha = if (possibleWakeword) {
                0.75f // 降低高通滤波强度，保留更多中频内容（唤醒词主要频率）
            } else {
                hpfAlpha // 使用标准设置
            }
            
            val hpfOutput = effectiveHpfAlpha * (hpfPreviousOutput + sample - hpfPreviousInput)
            hpfPreviousInput = sample
            hpfPreviousOutput = hpfOutput
            var processed = hpfOutput
            
            // 2. 应用自适应噪声门限处理
            // 对于可能的唤醒词，使用更低的噪声门限
            val effectiveNoiseGate = if (possibleWakeword) {
                noiseGate * 0.7f // 降低噪声门限，确保唤醒词不被过滤
            } else {
                noiseGate.toFloat()
            }
            
            if (abs(processed * 32768.0f) < effectiveNoiseGate) {
                // 使用温和的噪声门限曲线
                val ratio = (abs(processed * 32768.0f) / effectiveNoiseGate).pow(0.75f)
                processed *= ratio // 使用指数0.75使曲线更温和
            }
            
            // 3. 应用低通滤波器 - 为唤醒词优化
            val effectiveLowPass = if (possibleWakeword) {
                lowPass * 0.9f // 降低低通滤波强度，保留更多语音细节
            } else {
                lowPass
            }
            
            processed = processed * (1.0f - effectiveLowPass) + previousSample * effectiveLowPass
            previousSample = processed
            
            // 4. 应用更温和的动态范围压缩
            val absValue = abs(processed)
            if (absValue > 0.7f) {
                // 更温和的压缩曲线
                val compressionFactor = 1.0f + (absValue - 0.7f) * dynamicRangeCompression
                processed /= compressionFactor
            }
            
            // 5. 应用最终增益
            processed *= effectiveGain * 1.2f
            
            // 将处理后的样本转换回整数并限制范围
            val result = (processed * 32767.0f).toInt()
            processedData[i] = max(-32768, min(32767, result)).toShort()
        }
        
        // 偶尔打印处理统计信息，避免刷屏
        if (sampleCount % 48000 == 0) { // 大约每秒一次
            println("[音频处理] 已处理${(sampleCount/48000)}秒音频, 平均RMS=${audioStats.avgRms.toInt()}, 峰值=${audioStats.maxValue.toInt()}")
        }
        
        return processedData
    }

    /**
     * 检测音频特征是否符合唤醒词模式
     */
    private fun isPossibleWakewordPattern(rms: Double, zcr: Double): Boolean {
        // 唤醒词通常有以下特征：
        // 1. 能量适中(不太高也不太低) - "小度小度"通常有稳定的能量
        // 2. 过零率较低 - 语音相对平滑，不像摩擦音那样有高过零率
        // 3. 持续时间适中
        
        val energyMatch = rms in 150.0..2000.0
        val zcrMatch = zcr < 0.25
        
        return energyMatch && zcrMatch
    }

    /**
     * 重置处理器状态
     */
    override fun reset() {
        previousSample = 0.0f
        compressorLevel = 0.0f
        hpfPreviousInput = 0.0f
        hpfPreviousOutput = 0.0f
        sampleCount = 0
        audioStats.reset()
    }

    /**
     * 设置增益值
     * @param gain 增益值，大于0的浮点数
     */
    fun setGain(gain: Float) {
        if (gain > 0) {
            this.gain = gain
            println("[DEBUG] 音频处理器增益设置为: $gain")
        }
    }

    /**
     * 设置噪声门限
     * @param threshold 噪声门限值，大于0的整数
     */
    fun setNoiseGate(threshold: Int) {
        if (threshold >= 0) {
            this.noiseGate = threshold
            println("[DEBUG] 音频处理器噪声门限设置为: $threshold")
        }
    }

    /**
     * 设置低通滤波系数
     * @param alpha 滤波系数，0-1之间的浮点数，越大平滑效果越强
     */
    fun setLowPassFilter(alpha: Float) {
        if (alpha in 0.0f..1.0f) {
            this.lowPass = alpha
            println("[DEBUG] 音频处理器低通滤波系数设置为: $alpha")
        }
    }
    
    /**
     * 设置高通滤波系数
     * @param alpha 滤波系数，0-1之间的浮点数，越大滤除的低频越多
     */
    fun setHighPassFilter(alpha: Float) {
        if (alpha in 0.0f..1.0f) {
            this.hpfAlpha = alpha
            println("[DEBUG] 音频处理器高通滤波系数设置为: $alpha")
        }
    }

    /**
     * 启用/禁用处理器
     * @param enabled 是否启用
     */
    fun setEnabled(enabled: Boolean) {
        this.isEnabled = enabled
    }

    /**
     * 启用/禁用唤醒词增强
     */
    fun setWakewordOptimization(enabled: Boolean) {
        this.wakewordOptimized = enabled
        println("[配置] 唤醒词优化模式: ${if(enabled) "开启" else "关闭"}")
    }

    /**
     * 应用预设配置
     * @param preset 预设类型
     */
    fun applyPreset(preset: Preset) {
        when (preset) {
            Preset.CLEAR -> {
                gain = 2.8f
                noiseGate = 50
                lowPass = 0.65f
                dynamicRangeCompression = 0.3f
                hpfAlpha = 0.85f
                wakewordOptimized = true
            }
            Preset.NOISE_REDUCTION -> {
                gain = 3.2f
                noiseGate = 80
                lowPass = 0.75f
                dynamicRangeCompression = 0.5f
                hpfAlpha = 0.9f
                wakewordOptimized = true
            }
            Preset.VOICE_ENHANCEMENT -> {
                gain = 3.5f
                noiseGate = 40
                lowPass = 0.6f
                dynamicRangeCompression = 0.35f
                hpfAlpha = 0.8f
                wakewordOptimized = true
            }
            Preset.WAKEWORD_FOCUS -> {
                gain = 4.0f
                noiseGate = 30
                lowPass = 0.55f
                dynamicRangeCompression = 0.3f
                hpfAlpha = 0.75f
                wakewordOptimized = true
            }
        }
        
        println("[INFO] 音频处理器已配置为预设: $preset")
    }
    
    /**
     * 预设类型枚举
     */
    enum class Preset {
        CLEAR,            // 清晰模式
        NOISE_REDUCTION,  // 降噪模式
        VOICE_ENHANCEMENT, // 语音增强模式
        WAKEWORD_FOCUS   // 唤醒词专注模式
    }
    
    /**
     * 格式化浮点数为指定小数位数的字符串
     */
    private fun Float.format(digits: Int): String {
        return "%.${digits}f".format(this)
    }
    
    /**
     * 格式化双精度浮点数为指定小数位数的字符串
     */
    private fun Double.format(digits: Int): String {
        return "%.${digits}f".format(this)
    }

    
    /**
     * 计算浮点数的幂
     */
    private fun Float.pow(exponent: Float): Float {
        return kotlin.math.exp(kotlin.math.ln(this.toDouble()) * exponent).toFloat()
    }
    
    /**
     * 音频统计数据类
     */
    private class AudioStats {
        var maxRms = 0.0
        var minRms = Double.MAX_VALUE
        var sumRms = 0.0
        var countRms = 0
        var maxValue = 0.0
        var minValue = Double.MAX_VALUE
        
        val avgRms: Double
            get() = if (countRms > 0) sumRms / countRms else 0.0
            
        fun update(rms: Double, zcr: Double, max: Double, min: Double) {
            if (rms > maxRms) maxRms = rms
            if (rms < minRms) minRms = rms
            sumRms += rms
            countRms++
            
            if (max > maxValue) maxValue = max
            if (min < minValue) minValue = min
        }
        
        fun reset() {
            maxRms = 0.0
            minRms = Double.MAX_VALUE
            sumRms = 0.0
            countRms = 0
            maxValue = 0.0
            minValue = Double.MAX_VALUE
        }
    }
}

/**
 * 给 String 加个 format(number)：把自身当作格式串，格式化一个 Double
 * 用法: "%6.2f".format(3.1415)
 */
fun String.format(number: Double): String = memScoped {
    // pattern 必须以 '\0' 结束
    val pattern = (this@format + "\u0000")
    val buf = allocArray<ByteVar>(64)
    snprintf(buf, 64u, pattern, number)
    buf.toKString()
}

/**
 * String.format(number: Float)
 */
fun String.format(number: Float): String = this.format(number.toDouble())