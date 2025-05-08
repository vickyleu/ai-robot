package com.airobot.device.yanapi.snowboyPiper.impl

import com.airobot.device.yanapi.snowboyPiper.interfaces.AudioAnalyzer
import com.airobot.rnnoiseinterop.RNNoiseWrapper
import com.airobot.rnnoiseinterop.rnnoise_wrapper_create
import com.airobot.rnnoiseinterop.rnnoise_wrapper_destroy
import com.airobot.rnnoiseinterop.rnnoise_wrapper_process
import com.airobot.rnnoiseinterop.rnnoise_wrapper_set_vad_threshold
import com.airobot.rnnoiseinterop.rnnoise_wrapper_set_gain
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.set
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.pow
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class, ExperimentalForeignApi::class)
class BasicAudioAnalyzer(
    private val energyThreshold: Double,
    private val noiseGateThreshold: Double,
    private val validVoiceRmsThreshold: Double,
    private val validVoiceZcrThreshold: Double
) : AudioAnalyzer {

    private var backgroundNoiseLevel = 0.0
    private var adaptiveRmsThreshold = validVoiceRmsThreshold * 0.5 // 降低初始阈值以提高灵敏度
    private val adaptationFactor = 0.95 // 适应因子
    private var silenceCounter = 0
    private val maxSilenceBeforeAdapt = 10
    
    // 添加变量以记录环境噪声基线
    private var noiseBaseline = 0.0
    private var hasEstablishedNoise = false
    private val noiseHistorySize = 30 // 增加历史样本数量
    private val noiseHistory = DoubleArray(noiseHistorySize) { 0.0 }
    private var noiseHistoryIndex = 0
    
    // 添加语音特征历史以确认真实语音
    private val featureHistorySize = 5
    private val energyHistory = DoubleArray(featureHistorySize) { 0.0 }
    private val zcrHistory = DoubleArray(featureHistorySize) { 0.0 }
    private var featureHistoryIndex = 0
    
    // 添加连续语音检测计数器
    private var consecutiveVoiceFrames = 0
    private val minConsecutiveFramesForVoice = 2 // 降低连续帧要求，提高灵敏度
    
    // 用于上升和下降模式分析的变量
    private val energyPatternSize = 8
    private val recentEnergies = DoubleArray(energyPatternSize) { 0.0 }
    private var energyPatternIndex = 0
    
    // 回声消除相关变量
    private var lastPlaybackTime = 0L
    private val echoSuppressionTimeMs = 1500L // 增加回声抑制时间到1.5秒
    
    // 音频特征记忆，用于辨别回声
    private val echoSignatureSize = 5
    private val lastPlaybackSignatures = Array(5) { DoubleArray(echoSignatureSize) } // 增加到5个签名
    private var signatureIndex = 0
    private var hasPlaybackSignature = false
    
    // 增强回声检测的相似度阈值，降低防止误判
    private val echoSimilarityThreshold = 0.65f // 从0.7降到0.65
    
    // 记录连续语音状态
    private var lastVoiceActivityTime = 0L
    private var isInContinuousSpeech = false
    private val voiceContinuityThreshold = 800L // 连续语音阈值800ms
    private val silencePauseThreshold = 1000L // 静音阈值1000ms
    
    // RNNoise相关变量
    private var useRNNoise = true // 默认启用RNNoise
    private var lastVadProbability = 0.0f
    
    // 缓存RNNoise实例，避免频繁创建和销毁
    private var cachedRNNoiseWrapper: CPointer<RNNoiseWrapper>? = null
    private var lastRNNoiseUseTime = 0L
    private val rnnoiseCacheTimeout = 30000L // 30秒超时，避免长时间占用资源
    
    // 降低人声检测的能量阈值
    private val minEnergyThreshold = 30.0 // 从150.0降低到30.0
    private val maxEnergyThreshold = 10000.0
    
    // 放宽过零率范围
    private val minZcrThreshold = 0.01 // 从0.03降低到0.01
    private val maxZcrThreshold = 0.5 // 从0.3提高到0.5
    
    // 添加键盘声特征检测相关变量
    private val keyboardPatternHistorySize = 10
    private val keyboardEnergies = DoubleArray(keyboardPatternHistorySize) { 0.0 }
    private val keyboardZcrs = DoubleArray(keyboardPatternHistorySize) { 0.0 }
    private var keyboardPatternIndex = 0
    private var keyboardPatternCount = 0

    override fun hasVoiceActivity(buffer: ShortArray): Boolean {
        // 检查是否在回声抑制时间内
        val currentTime = Clock.System.now().toEpochMilliseconds()
        if (currentTime - lastPlaybackTime < echoSuppressionTimeMs) {
            // 检查是否是回声
            val currentSignature = extractAudioSignature(buffer)
            if (isEchoSignature(currentSignature)) {
                return false // 认为是回声，忽略
            }
        }
        
        // 更新连续语音状态
        updateContinuousSpeechState(buffer)
        
        // 计算音频能量
        var sumSquares = 0.0
        for (sample in buffer) {
            val sampleValue = sample.toDouble()
            sumSquares += (sampleValue * sampleValue)
        }
        val rms = sqrt(sumSquares / buffer.size)
        
        // 计算过零率
        var zeroCrossings = 0
        for (i in 1 until buffer.size) {
            if ((buffer[i] > 0 && buffer[i-1] <= 0) ||
                (buffer[i] <= 0 && buffer[i-1] > 0)) {
                zeroCrossings++
            }
        }
        val zcr = zeroCrossings.toDouble() / buffer.size
        
        // 检测是否符合键盘敲击特征
        updateKeyboardPatternHistory(rms, zcr)
        if (isLikelyKeyboardNoise(rms, zcr)) {
            return false
        }
        
        // 使用RNNoise进行人声检测 - 在连续语音中可以跳过以提高效率
        val isHumanVoice = if (!isInContinuousSpeech || rms > 100.0) {
            checkVoiceWithRNNoise(buffer)
        } else {
            true // 连续语音中默认认为是人声
        }
        
        // 判断是否有语音活动 - 放宽条件
        val hasEnergy = rms >= minEnergyThreshold
        val hasValidZcr = zcr >= minZcrThreshold && zcr <= maxZcrThreshold
        
        // 在连续语音中要更宽容
        val result = if (isInContinuousSpeech) {
            // 在连续语音中，只要有基本的能量就接受
            rms > minEnergyThreshold * 0.7 || isHumanVoice
        } else {
            // 首次检测需要更严格
            (hasEnergy && hasValidZcr) || isHumanVoice
        }
        
        // 更新连续语音帧计数
        if (result) {
            consecutiveVoiceFrames++
        } else {
            consecutiveVoiceFrames = max(0, consecutiveVoiceFrames - 1)
        }
        
        return result || consecutiveVoiceFrames >= minConsecutiveFramesForVoice
    }

    override fun containsValidVoice(buffer: ShortArray): Boolean {
        // 检查是否在回声抑制时间内
        val currentTime = Clock.System.now().toEpochMilliseconds()
        if (currentTime - lastPlaybackTime < echoSuppressionTimeMs) {
            // 检查是否是回声
            val currentSignature = extractAudioSignature(buffer)
            if (isEchoSignature(currentSignature)) {
                return false // 认为是回声，忽略
            }
        }
        
        // 计算音频能量和过零率用于键盘声检测
        var sumSquares = 0.0
        for (sample in buffer) {
            val sampleValue = sample.toDouble()
            sumSquares += (sampleValue * sampleValue)
        }
        val rms = sqrt(sumSquares / buffer.size)
        
        var zeroCrossings = 0
        for (i in 1 until buffer.size) {
            if ((buffer[i] > 0 && buffer[i-1] <= 0) ||
                (buffer[i] <= 0 && buffer[i-1] > 0)) {
                zeroCrossings++
            }
        }
        val zcr = zeroCrossings.toDouble() / buffer.size
        
        // 检查是否为键盘声
        if (isLikelyKeyboardNoise(rms, zcr)) {
            return false
        }
        
        // 在连续语音中更宽容
        if (isInContinuousSpeech) {
            return true
        }
        
        // 使用RNNoise进行人声检测
        val isHumanVoice = checkVoiceWithRNNoise(buffer)
        
        // 判断是否包含有效人声 - 极大放宽条件
        val hasEnergy = rms >= minEnergyThreshold && rms <= maxEnergyThreshold
        val hasValidZcr = zcr >= minZcrThreshold && zcr <= maxZcrThreshold
        
        // 任一条件满足即可
        return hasEnergy || hasValidZcr || isHumanVoice
    }

    /**
     * 获取RNNoise包装器，使用缓存避免频繁创建销毁
     */
    private fun getRNNoiseWrapper(): CPointer<RNNoiseWrapper>? {
        val currentTime = Clock.System.now().toEpochMilliseconds()
        
        // 检查缓存是否超时
        if (cachedRNNoiseWrapper != null && currentTime - lastRNNoiseUseTime > rnnoiseCacheTimeout) {
            // 超时释放资源
            rnnoise_wrapper_destroy(cachedRNNoiseWrapper)
            cachedRNNoiseWrapper = null
        }
        
        if (cachedRNNoiseWrapper == null) {
            cachedRNNoiseWrapper = rnnoise_wrapper_create()
        }
        
        // 更新最后使用时间
        if (cachedRNNoiseWrapper != null) {
            lastRNNoiseUseTime = currentTime
        }
        
        return cachedRNNoiseWrapper
    }
    
    /**
     * 使用RNNoise检测是否为人声
     */
    private fun checkVoiceWithRNNoise(buffer: ShortArray): Boolean {
        try {
            val rnnWrapper = getRNNoiseWrapper() ?: return true // 无法创建时默认接受
            
            // 设置极低的VAD阈值和高增益
            rnnoise_wrapper_set_vad_threshold(rnnWrapper, 0.05f) // 极低阈值
            rnnoise_wrapper_set_gain(rnnWrapper, 3.0f) // 高增益
            
            // 创建输入和输出缓冲区
            val frameCount = buffer.size
            val inputBuffer = nativeHeap.allocArray<ShortVar>(frameCount)
            val outputBuffer = nativeHeap.allocArray<ShortVar>(frameCount)
            
            // 复制音频数据到输入缓冲区
            for (i in 0 until frameCount) {
                inputBuffer[i] = buffer[i]
            }
            
            // 创建VAD概率数组
            val maxVadValues = frameCount / 480 + 1 // 每480样本一个VAD值
            val vadProbabilitiesPtr = nativeHeap.allocArray<FloatVar>(maxVadValues)
            
            // 处理音频数据
            val processResult = rnnoise_wrapper_process(
                rnnWrapper,
                inputBuffer,
                outputBuffer,
                frameCount,
                vadProbabilitiesPtr,
                maxVadValues
            )
            
            // 检查处理结果
            if (processResult <= 0) {
                nativeHeap.free(inputBuffer.rawValue)
                nativeHeap.free(outputBuffer.rawValue)
                nativeHeap.free(vadProbabilitiesPtr.rawValue)
                return true // 出错时默认接受
            }
            
            // 分析VAD概率
            var voiceFrames = 0
            var totalFrames = minOf(processResult, maxVadValues)
            var maxProb = 0.0f
            
            for (i in 0 until totalFrames) {
                val prob = vadProbabilitiesPtr[i]
                maxProb = max(maxProb, prob)
                if (prob >= 0.05f) { // 极低阈值
                    voiceFrames++
                }
            }
            
            // 释放资源
            nativeHeap.free(inputBuffer.rawValue)
            nativeHeap.free(outputBuffer.rawValue)
            nativeHeap.free(vadProbabilitiesPtr.rawValue)
            
            // 判断是否检测到足够的人声帧 - 极低阈值
            val voiceRatio = if (totalFrames > 0) voiceFrames.toFloat() / totalFrames else 0f
            val isHumanVoice = voiceRatio >= 0.05f || maxProb >= 0.1f // 极低阈值
            
            // 只在有人声时或调试需要时输出日志
            if (isHumanVoice) {
                println("[DEBUG] RNNoise VAD结果: 语音帧比例=$voiceRatio, 最高概率=$maxProb, 是人声=$isHumanVoice")
            }
            
            // 存储最后的VAD概率，以便其他方法使用
            lastVadProbability = maxProb
            
            return isHumanVoice
        } catch (e: Exception) {
            println("[ERROR] RNNoise处理异常: ${e.message}")
            return true // 出错时默认接受
        }
    }
    
    /**
     * 应用噪声门限，去除无用噪音
     * 使用RNNoise进行降噪处理
     */
    override fun applyNoiseGate(audioData: ShortArray): ShortArray {
        // 检查是否在回声抑制时间内
        val currentTime = Clock.System.now().toEpochMilliseconds()
        if (currentTime - lastPlaybackTime < echoSuppressionTimeMs) {
            // 在回声抑制期间，应用更强的噪声门限
            return applyStrongerNoiseGate(audioData)
        }
        
        // 如果不使用RNNoise，使用传统噪声门限方法
        if (!useRNNoise) {
            return applyTraditionalNoiseGate(audioData)
        }
        
        try {
            // 创建输入和输出缓冲区
            val frameCount = audioData.size
            val inputBuffer = nativeHeap.allocArray<ShortVar>(frameCount)
            val outputBuffer = nativeHeap.allocArray<ShortVar>(frameCount)
            
            // 复制音频数据到输入缓冲区
            for (i in 0 until frameCount) {
                inputBuffer[i] = audioData[i]
            }
            
            // 使用缓存的RNNoise包装器
            val rnnWrapper = getRNNoiseWrapper()
            if (rnnWrapper == null) {
                nativeHeap.free(inputBuffer.rawValue)
                nativeHeap.free(outputBuffer.rawValue)
                return audioData // 出错时返回原始音频
            }
            
            // 配置RNNoise - 根据是否在连续语音中调整阈值
            val vadThreshold = if (isInContinuousSpeech) 0.1f else 0.2f
            rnnoise_wrapper_set_vad_threshold(rnnWrapper, vadThreshold)
            rnnoise_wrapper_set_gain(rnnWrapper, 2.0f) // 适中增益
            
            // 处理音频数据
            val processResult = rnnoise_wrapper_process(
                rnnWrapper,
                inputBuffer,
                outputBuffer,
                frameCount,
                null, // 不需要VAD概率
                0
            )
            
            // 检查处理结果
            if (processResult <= 0) {
                nativeHeap.free(inputBuffer.rawValue)
                nativeHeap.free(outputBuffer.rawValue)
                return audioData // 出错时返回原始音频
            }
            
            // 创建输出数组
            val result = ShortArray(frameCount)
            
            // 复制降噪后的音频数据
            for (i in 0 until frameCount) {
                result[i] = outputBuffer[i]
            }
            
            // 释放资源
            nativeHeap.free(inputBuffer.rawValue)
            nativeHeap.free(outputBuffer.rawValue)
            
            return result
        } catch (e: Exception) {
            println("[ERROR] RNNoise降噪处理异常: ${e.message}")
            return audioData // 出错时返回原始音频
        }
    }
    
    /**
     * 在回声抑制期间应用更强的噪声门限
     */
    private fun applyStrongerNoiseGate(audioData: ShortArray): ShortArray {
        val result = ShortArray(audioData.size)
        
        // 计算平均能量
        var sumSquares = 0.0
        for (sample in audioData) {
            sumSquares += (sample * sample)
        }
        val avgEnergy = sqrt(sumSquares / audioData.size)
        
        // 在回声期间使用更高的噪声门限
        val echoNoiseGateThreshold = noiseGateThreshold * 2.0
        
        // 如果平均能量低于噪声门限，则静音
        if (avgEnergy < echoNoiseGateThreshold) {
            return result // 返回全零数组
        }
        
        // 否则应用强噪声门限
        for (i in audioData.indices) {
            val sampleEnergy = abs(audioData[i].toDouble())
            if (sampleEnergy < echoNoiseGateThreshold) {
                // 低于门限的样本衰减更强
                val attenuationFactor = (sampleEnergy / echoNoiseGateThreshold).pow(3)
                result[i] = (audioData[i] * attenuationFactor).toInt().toShort()
            } else {
                // 高于门限的样本保持不变
                result[i] = audioData[i]
            }
        }
        
        return result
    }
    
    /**
     * 传统噪声门限方法
     */
    private fun applyTraditionalNoiseGate(audioData: ShortArray): ShortArray {
        val result = ShortArray(audioData.size)

        // 计算平均能量
        var sumSquares = 0.0
        for (sample in audioData) {
            sumSquares += (sample * sample)
        }
        val avgEnergy = sqrt(sumSquares / audioData.size)
        
        // 如果平均能量低于噪声门限，则静音
        if (avgEnergy < noiseGateThreshold) {
            return result // 返回全零数组
        }
        
        // 否则应用软噪声门限
        for (i in audioData.indices) {
            val sampleEnergy = abs(audioData[i].toDouble())
            if (sampleEnergy < noiseGateThreshold) {
                // 低于门限的样本衰减
                val attenuationFactor = (sampleEnergy / noiseGateThreshold).pow(2)
                result[i] = (audioData[i] * attenuationFactor).toInt().toShort()
            } else {
                // 高于门限的样本保持不变
                result[i] = audioData[i]
            }
        }

        return result
    }

    /**
     * 通知分析器刚刚播放了音频，需要暂时抑制回声
     */
    override fun notifyAudioPlayback(audioData: ShortArray) {
        // 记录播放时间
        lastPlaybackTime = Clock.System.now().toEpochMilliseconds()
        
        // 提取并保存音频特征签名
        val signature = extractAudioSignature(audioData)
        lastPlaybackSignatures[signatureIndex] = signature
        signatureIndex = (signatureIndex + 1) % lastPlaybackSignatures.size
        hasPlaybackSignature = true
        
        // 重置连续语音检测，避免播放的声音被误认为是人声
        consecutiveVoiceFrames = 0
        isInContinuousSpeech = false
    }

    /**
     * 重置分析器状态
     */
    override fun reset() {
        backgroundNoiseLevel = 0.0
        adaptiveRmsThreshold = validVoiceRmsThreshold * 0.5
        silenceCounter = 0
        noiseBaseline = 0.0
        hasEstablishedNoise = false
        
        // 重置能量和ZCR历史
        for (i in 0 until featureHistorySize) {
            energyHistory[i] = 0.0
            zcrHistory[i] = 0.0
        }
        featureHistoryIndex = 0
        
        // 重置噪声历史
        for (i in 0 until noiseHistorySize) {
            noiseHistory[i] = 0.0
        }
        noiseHistoryIndex = 0
        
        // 重置语音检测计数器
        consecutiveVoiceFrames = 0
        
        // 重置能量模式分析
        for (i in 0 until energyPatternSize) {
            recentEnergies[i] = 0.0
        }
        energyPatternIndex = 0
        
        // 重置回声消除相关状态
        hasPlaybackSignature = false
        for (i in lastPlaybackSignatures.indices) {
            for (j in 0 until echoSignatureSize) {
                lastPlaybackSignatures[i][j] = 0.0
            }
        }
        
        // 重置RNNoise相关状态
        lastVadProbability = 0.0f
        
        // 不重置RNNoise包装器缓存，保留以供继续使用
        
        println("[DEBUG] 音频分析器状态已重置")
    }
    
    /**
     * 释放所有资源
     */
    fun dispose() {
        // 释放RNNoise包装器
        if (cachedRNNoiseWrapper != null) {
            rnnoise_wrapper_destroy(cachedRNNoiseWrapper)
            cachedRNNoiseWrapper = null
        }
    }

    /**
     * 检查当前音频是否是连续语音的一部分
     */
    private fun updateContinuousSpeechState(buffer: ShortArray) {
        // 计算音频能量
        var sumSquares = 0.0
        for (sample in buffer) {
            val sampleValue = sample.toDouble()
            sumSquares += (sampleValue * sampleValue)
        }
        val rms = sqrt(sumSquares / buffer.size)
        
        // 获取当前时间
        val currentTime = Clock.System.now().toEpochMilliseconds()
        
        // 有声音活动时，更新时间戳
        if (rms >= minEnergyThreshold) {
            // 如果时间足够近，判定为连续语音
            if (currentTime - lastVoiceActivityTime < voiceContinuityThreshold) {
                isInContinuousSpeech = true
            } else if (currentTime - lastVoiceActivityTime > silencePauseThreshold) {
                // 如果间隔过长，认为是新的语音开始
                isInContinuousSpeech = false
            }
            
            lastVoiceActivityTime = currentTime
        } else if (currentTime - lastVoiceActivityTime > silencePauseThreshold) {
            // 长时间无声，重置连续语音状态
            isInContinuousSpeech = false
        }
    }

    // 提取音频特征签名
    private fun extractAudioSignature(audioData: ShortArray): DoubleArray {
        val signature = DoubleArray(echoSignatureSize)
        
        // 计算RMS能量
        var sumSquares = 0.0
        for (sample in audioData) {
            sumSquares += (sample * sample)
        }
        signature[0] = sqrt(sumSquares / audioData.size)
        
        // 计算ZCR
        var zeroCrossings = 0
        for (i in 1 until audioData.size) {
            if ((audioData[i] > 0 && audioData[i-1] <= 0) ||
                (audioData[i] <= 0 && audioData[i-1] > 0)) {
                zeroCrossings++
            }
        }
        signature[1] = zeroCrossings.toDouble() / audioData.size
        
        // 计算能量分布 - 分3段
        val segmentSize = audioData.size / 3
        for (i in 0 until 3) {
            var segEnergy = 0.0
            val start = i * segmentSize
            val end = min((i + 1) * segmentSize, audioData.size)
            
            for (j in start until end) {
                segEnergy += audioData[j] * audioData[j]
            }
            signature[i + 2] = sqrt(segEnergy / (end - start))
        }
        
        return signature
    }
    
    // 比较当前音频是否与保存的回声特征相似
    private fun isEchoSignature(currentSignature: DoubleArray): Boolean {
        if (!hasPlaybackSignature) {
            return false
        }
        
        for (savedSignature in lastPlaybackSignatures) {
            var similarity = 0.0
            var totalWeight = 0.0
            
            // 能量特征权重更高
            similarity += (1.0 - abs(currentSignature[0] - savedSignature[0]) / max(currentSignature[0], 1.0)) * 3.0
            totalWeight += 3.0
            
            // ZCR特征
            similarity += (1.0 - abs(currentSignature[1] - savedSignature[1]) / max(currentSignature[1], 0.1)) * 2.0
            totalWeight += 2.0
            
            // 能量分布特征
            for (i in 2 until echoSignatureSize) {
                similarity += (1.0 - abs(currentSignature[i] - savedSignature[i]) / max(currentSignature[i], 1.0))
                totalWeight += 1.0
            }
            
            // 归一化相似度
            val normalizedSimilarity = similarity / totalWeight
            
            // 如果相似度超过阈值，认为是回声
            if (normalizedSimilarity > echoSimilarityThreshold) {
                return true
            }
        }
        
        return false
    }

    /**
     * 更新键盘敲击模式历史
     */
    private fun updateKeyboardPatternHistory(rms: Double, zcr: Double) {
        // 只在历史记录达到一定量后才开始分析
        if (keyboardPatternCount < keyboardPatternHistorySize) {
            keyboardPatternCount++
        }
        
        // 记录能量和过零率
        keyboardEnergies[keyboardPatternIndex] = rms
        keyboardZcrs[keyboardPatternIndex] = zcr
        
        // 更新索引
        keyboardPatternIndex = (keyboardPatternIndex + 1) % keyboardPatternHistorySize
    }
    
    /**
     * 检测是否可能是键盘敲击声
     * 键盘敲击声特征：
     * 1. 能量突然上升又迅速下降
     * 2. 高过零率
     * 3. 声音间隔规律
     */
    private fun isLikelyKeyboardNoise(currentRms: Double, currentZcr: Double): Boolean {
        // 需要足够的历史数据才能判断
        if (keyboardPatternCount < keyboardPatternHistorySize / 2) {
            return false
        }
        
        // 检查是否有能量突然高峰和快速衰减的模式
        var hasEnergySpikePattern = false
        var hasRegularInterval = false
        var hasHighZcr = false
        
        // 能量是否有突然高峰
        val lastEnergies = Array(4) { i ->
            val idx = (keyboardPatternIndex - i - 1 + keyboardPatternHistorySize) % keyboardPatternHistorySize
            keyboardEnergies[idx]
        }
        
        // 计算当前帧与历史平均值的比例
        val avgEnergy = lastEnergies.sum() / lastEnergies.size
        val currentToAvgRatio = if (avgEnergy > 0) currentRms / avgEnergy else 1.0
        
        // 判断能量突然上升又快速下降的模式
        val recentEnergyPattern = keyboardEnergies
            .toList()
            .takeLast(5)
            .windowed(3, 1)
            .any { window ->
                // 中间高，两边低是键盘敲击的特征
                window[1] > window[0] * 3.0 && window[1] > window[2] * 2.0
            }
        
        // 检查是否有规律的间隔模式
        val energyPeaks = mutableListOf<Int>()
        for (i in 1 until keyboardPatternHistorySize - 1) {
            if (keyboardEnergies[i] > keyboardEnergies[i-1] * 1.5 && 
                keyboardEnergies[i] > keyboardEnergies[i+1] * 1.5 &&
                keyboardEnergies[i] > 100.0) {
                energyPeaks.add(i)
            }
        }
        
        // 如果有多个峰值，检查间隔是否规律（键盘敲击通常很规律）
        if (energyPeaks.size >= 3) {
            val intervals = mutableListOf<Int>()
            for (i in 1 until energyPeaks.size) {
                intervals.add(energyPeaks[i] - energyPeaks[i-1])
            }
            
            // 计算间隔的标准差，越小越规律
            val avgInterval = intervals.average()
            val stdDev = sqrt(intervals.map { (it - avgInterval).pow(2) }.sum() / intervals.size)
            
            // 规律的间隔标准差应该小
            hasRegularInterval = stdDev / avgInterval < 0.3 && intervals.size >= 2
        }
        
        // 过零率检查 - 键盘声通常过零率较高
        hasHighZcr = currentZcr > 0.3
        
        // 组合判断是否为键盘声
        val isKeyboard = (recentEnergyPattern || currentToAvgRatio > 3.0 || hasRegularInterval) && hasHighZcr
        
        if (isKeyboard) {
            println("[DEBUG] 检测到可能的键盘敲击声: 能量=$currentRms, ZCR=$currentZcr, 能量比=$currentToAvgRatio")
        }
        
        return isKeyboard
    }
}