package com.airobot.device.yanapi.voice.audio

import com.airobot.device.yanapi.voice.interfaces.AudioProcessor
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

/**
 * 基础音频处理器实现
 * 提供基本的音频处理功能，如增益控制、降噪、音量调整等
 */
class BasicAudioProcessor : AudioProcessor {
    // 处理参数
    private var gain = 1.0f       // 增益
    private var noiseGate = 100   // 噪声门限
    private var lowPass = 0.9f    // 低通滤波系数
    
    // 状态变量
    private var previousSample = 0.0f
    private var isEnabled = true
    
    /**
     * 处理音频数据
     * @param audioData 原始音频数据
     * @return 处理后的音频数据
     */
    override fun processAudio(audioData: ShortArray): ShortArray {
        if (!isEnabled || audioData.isEmpty()) {
            return audioData
        }
        
        val result = ShortArray(audioData.size)
        
        // 应用处理
        for (i in audioData.indices) {
            var sample = audioData[i] * gain  // 应用增益
            
            // 应用噪声门限
            if (abs(sample) < noiseGate) {
                sample = 0.0f
            }
            
            // 应用低通滤波
            sample = lowPass * sample + (1 - lowPass) * previousSample
            previousSample = sample
            
            // 限幅
            if (sample > Short.MAX_VALUE) {
                sample = Short.MAX_VALUE.toFloat()
            } else if (sample < Short.MIN_VALUE) {
                sample = Short.MIN_VALUE.toFloat()
            }
            
            result[i] = sample.toInt().toShort()
        }
        
        return result
    }
    
    /**
     * 重置处理器状态
     */
    override fun reset() {
        previousSample = 0.0f
    }
    
    /**
     * 设置处理器参数
     * @param paramName 参数名称
     * @param value 参数值
     */
    fun setParameter(paramName: String, value: Double) {
        when (paramName.lowercase()) {
            "gain" -> gain = value.toFloat()
            "noisegate" -> noiseGate = value.toInt()
            "lowpasscoeff" -> lowPass = value.toFloat()
            "enabled" -> isEnabled = value > 0
        }
    }
    
    /**
     * 获取处理器参数值
     * @param paramName 参数名称
     * @return 参数值，如果参数不存在则返回0.0
     */
    fun getParameter(paramName: String): Double {
        return when (paramName.lowercase()) {
            "gain" -> gain.toDouble()
            "noisegate" -> noiseGate.toDouble()
            "lowpasscoeff" -> lowPass.toDouble()
            "enabled" -> if (isEnabled) 1.0 else 0.0
            else -> 0.0
        }
    }
    
    /**
     * 应用自适应增益控制
     * @param audioData 音频数据
     * @param targetLevel 目标能量水平
     * @return 处理后的音频数据
     */
    fun applyAdaptiveGainControl(audioData: ShortArray, targetLevel: Double): ShortArray {
        if (audioData.isEmpty()) return audioData
        
        // 计算当前RMS能量
        var sumSquares = 0.0
        for (sample in audioData) {
            sumSquares += (sample * sample)
        }
        val rms = kotlin.math.sqrt(sumSquares / audioData.size)
        
        // 根据目标能量调整增益
        if (rms > 0) {
            val gainFactor = (targetLevel / rms).toFloat()
            val result = ShortArray(audioData.size)
            
            for (i in audioData.indices) {
                var sample = audioData[i] * gainFactor
                
                // 限幅
                if (sample > Short.MAX_VALUE) {
                    sample = Short.MAX_VALUE.toFloat()
                } else if (sample < Short.MIN_VALUE) {
                    sample = Short.MIN_VALUE.toFloat()
                }
                
                result[i] = sample.toInt().toShort()
            }
            
            return result
        }
        
        return audioData
    }
    
    /**
     * 启用处理
     */
    fun enable() {
        isEnabled = true
    }
    
    /**
     * 禁用处理
     */
    fun disable() {
        isEnabled = false
    }
} 