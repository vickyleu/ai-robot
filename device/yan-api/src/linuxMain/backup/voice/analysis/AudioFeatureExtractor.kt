package com.airobot.device.yanapi.voice.analysis

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 音频特征提取器
 * 提取音频数据的各种特征，用于音频分析和语音识别
 */
class AudioFeatureExtractor {
    /**
     * 计算均方根能量
     * @param samples 音频采样数据
     * @return RMS能量值
     */
    fun calculateRmsEnergy(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        
        var sum = 0.0
        for (sample in samples) {
            sum += sample * sample
        }
        
        return sqrt(sum / samples.size)
    }
    
    /**
     * 计算过零率
     * @param samples 音频采样数据
     * @return 过零率，范围0-1
     */
    fun calculateZeroCrossingRate(samples: ShortArray): Double {
        if (samples.size <= 1) return 0.0
        
        var crossings = 0
        for (i in 1 until samples.size) {
            if ((samples[i] > 0 && samples[i - 1] <= 0) || 
                (samples[i] <= 0 && samples[i - 1] > 0)) {
                crossings++
            }
        }
        
        return crossings.toDouble() / (samples.size - 1)
    }
    
    /**
     * 计算音频振幅包络
     * @param samples 音频采样数据
     * @param windowSize 滑动窗口大小
     * @return 包络数据
     */
    fun calculateEnvelope(samples: ShortArray, windowSize: Int = 256): DoubleArray {
        if (samples.isEmpty()) return DoubleArray(0)
        
        val resultSize = samples.size / windowSize + 1
        val envelope = DoubleArray(resultSize)
        
        for (i in 0 until resultSize) {
            val startIdx = i * windowSize
            val endIdx = minOf(startIdx + windowSize, samples.size)
            
            var maxAmp = 0.0
            for (j in startIdx until endIdx) {
                val amp = abs(samples[j].toDouble())
                if (amp > maxAmp) {
                    maxAmp = amp
                }
            }
            
            envelope[i] = maxAmp
        }
        
        return envelope
    }
    
    /**
     * 计算频谱能量分布
     * @param samples 音频采样数据
     * @param bandCount 频带数量
     * @return 各频带能量
     */
    fun calculateSpectralEnergy(samples: ShortArray, bandCount: Int = 5): DoubleArray {
        if (samples.isEmpty()) return DoubleArray(bandCount)
        
        val segmentSize = samples.size / bandCount
        val energies = DoubleArray(bandCount)
        
        for (band in 0 until bandCount) {
            val start = band * segmentSize
            val end = minOf(start + segmentSize, samples.size)
            
            var energy = 0.0
            for (i in start until end) {
                energy += samples[i] * samples[i]
            }
            
            energies[band] = energy / (end - start)
        }
        
        return energies
    }
    
    /**
     * 检测是否包含人声
     * 基于能量和过零率的简单人声检测
     * @param samples 音频采样数据
     * @param energyThreshold 能量阈值
     * @param zcrLowThreshold 过零率低阈值
     * @param zcrHighThreshold 过零率高阈值
     * @return 是否检测到人声
     */
    fun detectVoiceActivity(
        samples: ShortArray, 
        energyThreshold: Double = 500.0,
        zcrLowThreshold: Double = 0.1,
        zcrHighThreshold: Double = 0.3
    ): Boolean {
        val energy = calculateRmsEnergy(samples)
        val zcr = calculateZeroCrossingRate(samples)
        
        // 语音通常有较高的能量和适中的过零率
        return energy > energyThreshold && (zcr in zcrLowThreshold..zcrHighThreshold)
    }
    
    /**
     * 计算声音平滑度 (Spectral Flatness)
     * 值越低表示声音越有结构（如语音），值越高表示声音越像噪声
     * @param samples 音频采样数据
     * @return 平滑度，0-1范围
     */
    fun calculateSpectralFlatness(samples: ShortArray): Double {
        if (samples.isEmpty()) return 1.0
        
        var geometricMean = 0.0
        var arithmeticMean = 0.0
        
        // 简化版本，实际应该使用FFT
        for (sample in samples) {
            val value = abs(sample.toDouble())
            if (value > 0) {
                geometricMean += log10(value)
                arithmeticMean += value
            }
        }
        
        if (arithmeticMean == 0.0) return 1.0
        
        geometricMean = 10.0.pow(geometricMean / samples.size)
        arithmeticMean /= samples.size
        
        return geometricMean / arithmeticMean
    }
    
    /**
     * 计算短时能量
     * 将信号分成多个短帧并计算每帧能量
     * @param samples 音频采样数据
     * @param frameSize 帧大小
     * @return 各帧能量值
     */
    fun calculateShortTimeEnergy(samples: ShortArray, frameSize: Int = 256): DoubleArray {
        if (samples.isEmpty()) return DoubleArray(0)
        
        val frameCount = samples.size / frameSize + 1
        val energies = DoubleArray(frameCount)
        
        for (i in 0 until frameCount) {
            val startIdx = i * frameSize
            val endIdx = minOf(startIdx + frameSize, samples.size)
            
            var energy = 0.0
            for (j in startIdx until endIdx) {
                energy += samples[j] * samples[j]
            }
            
            energies[i] = energy / (endIdx - startIdx)
        }
        
        return energies
    }
    
    /**
     * 提取完整的特征集
     * @param samples 音频采样数据
     * @return 包含多种特征的Map
     */
    fun extractFeatures(samples: ShortArray): Map<String, Double> {
        val features = mutableMapOf<String, Double>()
        
        // 能量特征
        features["rms_energy"] = calculateRmsEnergy(samples)
        
        // 过零率特征
        features["zero_crossing_rate"] = calculateZeroCrossingRate(samples)
        
        // 频谱平坦度
        features["spectral_flatness"] = calculateSpectralFlatness(samples)
        
        // 频谱能量分布
        val spectralEnergies = calculateSpectralEnergy(samples, 5)
        for (i in spectralEnergies.indices) {
            features["spectral_band_$i"] = spectralEnergies[i]
        }
        
        // 计算高低频比
        if (spectralEnergies.size >= 5) {
            val lowFreqEnergy = spectralEnergies[0] + spectralEnergies[1]
            val highFreqEnergy = spectralEnergies[3] + spectralEnergies[4]
            features["high_low_freq_ratio"] = if (lowFreqEnergy > 0) highFreqEnergy / lowFreqEnergy else 0.0
        }
        
        return features
    }
} 