@file:OptIn(ExperimentalForeignApi::class)

package snowboyPiper.interop

import com.airobot.speexdspinterop.SPEEX_ECHO_SET_SAMPLING_RATE
import com.airobot.speexdspinterop.SPEEX_PREPROCESS_SET_AGC
import com.airobot.speexdspinterop.SPEEX_PREPROCESS_SET_AGC_LEVEL
import com.airobot.speexdspinterop.SPEEX_PREPROCESS_SET_AGC_MAX_GAIN
import com.airobot.speexdspinterop.SPEEX_PREPROCESS_SET_DENOISE
import com.airobot.speexdspinterop.SPEEX_PREPROCESS_SET_ECHO_STATE
import com.airobot.speexdspinterop.SPEEX_PREPROCESS_SET_ECHO_SUPPRESS
import com.airobot.speexdspinterop.SPEEX_PREPROCESS_SET_ECHO_SUPPRESS_ACTIVE
import com.airobot.speexdspinterop.SPEEX_PREPROCESS_SET_NOISE_SUPPRESS
import com.airobot.speexdspinterop.SPEEX_PREPROCESS_SET_VAD
import com.airobot.speexdspinterop.SpeexEchoState_
import com.airobot.speexdspinterop.SpeexPreprocessState_
import com.airobot.speexdspinterop.speex_echo_cancellation
import com.airobot.speexdspinterop.speex_echo_ctl
import com.airobot.speexdspinterop.speex_echo_playback
import com.airobot.speexdspinterop.speex_echo_state_destroy
import com.airobot.speexdspinterop.speex_echo_state_init
import com.airobot.speexdspinterop.speex_echo_state_reset
import com.airobot.speexdspinterop.speex_preprocess_ctl
import com.airobot.speexdspinterop.speex_preprocess_estimate_update
import com.airobot.speexdspinterop.speex_preprocess_run
import com.airobot.speexdspinterop.speex_preprocess_state_destroy
import com.airobot.speexdspinterop.speex_preprocess_state_init
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import platform.posix.time
import platform.posix.NULL
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * SpeexDSP处理器
 * 提供高质量的回声消除(AEC)、自动增益控制(AGC)、降噪和VAD功能
 * 特别针对树莓派等资源受限设备优化
 */
class SpeexDspProcessor {
    // 状态变量
    private var initialized = false
    private var sampleRate = 16000
    private var frameSize = 320     // 20ms @ 16kHz
    private var filterLength = 1024 // 降低滤波器长度减轻CPU负担

    // Speex预处理器状态
    private var preprocessState: CPointer<SpeexPreprocessState_>? = null

    // Speex回声消除器状态
    private var echoState: CPointer<SpeexEchoState_>? = null

    // 回声消除参考缓冲
    private var hasReference = false
    private var playbackReference = ShortArray(0)

    // 播放参考信号缓冲（用于延迟补偿）
    private val playbackBuffer = ArrayList<ShortArray>(3)

    // 性能优化变量
    private var processCount = 0
    private val updateInterval = 10 // 提高更新频率，以更快适应变化的环境

    // 增益控制参数
    private var agcLevel = 15000        // 默认AGC目标电平
    private var maxGain = 12.0f         // 最大增益(dB)
    private var adaptiveGainEnabled = true
    
    // 噪声控制参数
    private var noiseSuppress = -25     // 默认噪声抑制级别(dB)
    private var echoSuppress = -35      // 默认回声抑制级别(dB)
    private var echoSuppressActive = -45 // 语音时回声抑制级别(dB)
    
    // 环境自适应参数
    private val energyHistory = FloatArray(100) { 0.0f }
    private var energyHistoryIndex = 0
    private var environmentType = EnvironmentType.NORMAL
    private var lastEnvChangeTime = 0L
    private var envChangeDelayMs = 3000 // 3秒的环境变化稳定期
    
    // 回声延迟估计
    private var echoDelayEstimate = 0
    private var echoDelayConfidence = 0.0f
    
    /**
     * 音频环境类型枚举
     */
    enum class EnvironmentType {
        QUIET,      // 安静环境
        NORMAL,     // 正常环境
        NOISY,      // 嘈杂环境
        REVERBERANT // 混响环境
    }

    private fun Int.toPointer(): kotlinx.cinterop.CValuesRef<IntVar> {
        return nativeHeap.alloc<IntVar>().apply {
            this.value = this@toPointer
        }.ptr
    }

    /**
     * 初始化SpeexDSP处理器
     * @param sampleRate 采样率
     * @param frameSize 每帧样本数
     * @param enableDenoise 是否启用降噪
     * @param enableAgc 是否启用自动增益控制
     * @param enableVad 是否启用语音活动检测
     * @param enableEcho 是否启用回声消除
     * @param deviceProfile 设备配置文件(可选)
     */
    fun initialize(
        sampleRate: Int = 16000,
        frameSize: Int = 320,
        enableDenoise: Boolean = true,
        enableAgc: Boolean = true,
        enableVad: Boolean = true,
        enableEcho: Boolean = true,
        deviceProfile: String = "raspberry_pi"
    ): Boolean {
        if (initialized) {
            reset()  // 重置现有状态
        }

        this.sampleRate = sampleRate
        this.frameSize = frameSize
        
        // 根据实际采样率调整滤波器长度
        this.filterLength = when {
            sampleRate <= 8000 -> 512
            sampleRate <= 16000 -> 1024
            else -> 2048
        }

        try {
            // 初始化预处理器
            preprocessState = speex_preprocess_state_init(frameSize, sampleRate)

            // 配置预处理器
            speex_preprocess_ctl(
                preprocessState,
                SPEEX_PREPROCESS_SET_DENOISE,
                (if (enableDenoise) 1 else 0).toPointer()
            )
            speex_preprocess_ctl(
                preprocessState,
                SPEEX_PREPROCESS_SET_AGC,
                (if (enableAgc) 1 else 0).toPointer()
            )
            speex_preprocess_ctl(
                preprocessState,
                SPEEX_PREPROCESS_SET_VAD,
                (if (enableVad) 1 else 0).toPointer()
            )

            // 根据设备配置文件应用优化参数
            applyDeviceProfile(deviceProfile)

            // 初始化回声消除器
            if (enableEcho) {
                echoState = speex_echo_state_init(frameSize, filterLength)
                speex_echo_ctl(echoState, SPEEX_ECHO_SET_SAMPLING_RATE, sampleRate.toPointer())
                
                // 将回声状态关联到预处理器
                if (preprocessState != null && echoState != null) {
                    speex_preprocess_ctl(
                        preprocessState,
                        SPEEX_PREPROCESS_SET_ECHO_STATE,
                        echoState
                    )
                }
            }
            initialized = true
            println("[INFO] SpeexDSP处理器已初始化: 采样率=$sampleRate, 帧长=$frameSize, 滤波器长度=$filterLength, 设备配置=$deviceProfile")
            return true
        } catch (e: Exception) {
            println("[ERROR] SpeexDSP初始化失败: ${e.message}")
            release()  // 确保释放任何已分配的资源
            return false
        }
    }
    
    /**
     * 应用设备特定的优化配置
     */
    private fun applyDeviceProfile(profile: String) {
        // 对不同的设备应用不同的参数
        when (profile.lowercase()) {
            "raspberry_pi" -> {
                // 树莓派优化参数
                noiseSuppress = -30       // 更强的降噪
                echoSuppress = -40        // 更强的回声抑制
                echoSuppressActive = -50  // 语音时更强的回声抑制
                agcLevel = 14000          // 略低的AGC目标，减少增益导致的回声
                maxGain = 10.0f           // 降低最大增益以减少失真
                
                // 应用参数到SpeexDSP
                applyParameters()
                
                println("[INFO] 已应用树莓派优化配置")
            }
            "noisy_environment" -> {
                // 嘈杂环境优化
                noiseSuppress = -35       // 更强的降噪
                echoSuppress = -45        // 更强的回声抑制
                echoSuppressActive = -55  // 语音时更强的回声抑制
                agcLevel = 16000          // 更高的AGC目标，确保在噪声中可以听清声音
                maxGain = 14.0f           // 增加最大增益以克服噪声
                
                // 应用参数到SpeexDSP
                applyParameters()
                
                println("[INFO] 已应用嘈杂环境优化配置")
            }
            "quiet_environment" -> {
                // 安静环境优化
                noiseSuppress = -20       // 较弱的降噪，保留更多语音细节
                echoSuppress = -30        // 中等回声抑制
                echoSuppressActive = -40  // 语音时中等回声抑制
                agcLevel = 12000          // 较低的AGC目标，避免放大环境噪声
                maxGain = 8.0f            // 降低最大增益以减少噪声放大
                
                // 应用参数到SpeexDSP
                applyParameters()
                
                println("[INFO] 已应用安静环境优化配置")
            }
            else -> {
                // 通用配置
                noiseSuppress = -25
                echoSuppress = -35
                echoSuppressActive = -45
                agcLevel = 15000
                maxGain = 12.0f
                
                // 应用参数到SpeexDSP
                applyParameters()
                
                println("[INFO] 已应用通用配置文件")
            }
        }
    }
    
    /**
     * 将当前参数应用到SpeexDSP
     */
    private fun applyParameters() {
        if (!initialized || preprocessState == null) return
        
        try {
            // 应用噪声抑制设置
            speex_preprocess_ctl(
                preprocessState,
                SPEEX_PREPROCESS_SET_NOISE_SUPPRESS,
                noiseSuppress.toPointer()
            )
            
            // 应用AGC设置
            speex_preprocess_ctl(
                preprocessState,
                SPEEX_PREPROCESS_SET_AGC_LEVEL,
                agcLevel.toPointer()
            )
            
            speex_preprocess_ctl(
                preprocessState,
                SPEEX_PREPROCESS_SET_AGC_MAX_GAIN,
                maxGain.toInt().toPointer()
            )
            
            // 应用回声抑制设置
            speex_preprocess_ctl(
                preprocessState,
                SPEEX_PREPROCESS_SET_ECHO_SUPPRESS,
                echoSuppress.toPointer()
            )
            
            speex_preprocess_ctl(
                preprocessState,
                SPEEX_PREPROCESS_SET_ECHO_SUPPRESS_ACTIVE,
                echoSuppressActive.toPointer()
            )
        } catch (e: Exception) {
            println("[ERROR] 应用SpeexDSP参数失败: ${e.message}")
        }
    }

    /**
     * 设置降噪级别
     * @param level 降噪级别(0-10)，值越大降噪越强，但可能影响语音质量
     */
    fun setDenoiseLevel(level: Int) {
        if (!initialized || preprocessState == null) return
        try {
            // 将自定义级别(0-10)转换为SpeexDSP的降噪抑制值(-40到0 dB)
            val clampedLevel = max(0, min(10, level))
            val suppressValue = -(clampedLevel * 4) // 0->0, 10->-40
            
            // 保存新设置
            noiseSuppress = suppressValue
            
            // 应用设置
            speex_preprocess_ctl(
                preprocessState,
                SPEEX_PREPROCESS_SET_NOISE_SUPPRESS,
                suppressValue.toPointer()
            )
            println("[INFO] SpeexDSP降噪级别设置为: $clampedLevel (抑制: $suppressValue dB)")
        } catch (e: Exception) {
            println("[ERROR] 设置SpeexDSP降噪级别异常: ${e.message}")
        }
    }

    /**
     * 设置自动增益控制目标电平
     * @param level 目标音量电平(0-32767)
     */
    fun setAgcLevel(level: Int) {
        if (!initialized || preprocessState == null) return
        try {
            // 验证范围
            val clampedLevel = max(1000, min(32000, level))
            
            // 保存新设置
            agcLevel = clampedLevel
            
            // 应用设置
            speex_preprocess_ctl(
                preprocessState,
                SPEEX_PREPROCESS_SET_AGC_LEVEL,
                clampedLevel.toPointer()
            )
            println("[INFO] SpeexDSP AGC目标电平设置为: $clampedLevel")
        } catch (e: Exception) {
            println("[ERROR] 设置SpeexDSP AGC电平异常: ${e.message}")
        }
    }

    /**
     * 设置自动增益控制最大增益
     * @param maxGainValue 最大增益(dB)
     */
    fun setAgcMaxGain(maxGainValue: Float) {
        if (!initialized || preprocessState == null) return
        try {
            // 验证范围
            val clampedGain = max(1.0f, min(30.0f, maxGainValue))
            
            // 保存新设置
            maxGain = clampedGain
            
            // 应用设置
            speex_preprocess_ctl(
                preprocessState,
                SPEEX_PREPROCESS_SET_AGC_MAX_GAIN,
                clampedGain.toInt().toPointer()
            )
            println("[INFO] SpeexDSP AGC最大增益设置为: $clampedGain dB")
        } catch (e: Exception) {
            println("[ERROR] 设置SpeexDSP AGC最大增益异常: ${e.message}")
        }
    }

    /**
     * 设置回声抑制级别
     * @param suppressLevel 回声抑制级别(dB)，负值，如-40
     */
    fun setEchoSuppress(suppressLevel: Int) {
        if (!initialized || preprocessState == null) return
        try {
            // 验证范围
            val clampedSuppress = max(-60, min(-10, suppressLevel))
            
            // 保存新设置
            echoSuppress = clampedSuppress
            
            // 应用设置
            speex_preprocess_ctl(
                preprocessState,
                SPEEX_PREPROCESS_SET_ECHO_SUPPRESS,
                clampedSuppress.toPointer()
            )
            println("[INFO] SpeexDSP回声抑制级别设置为: $clampedSuppress dB")
        } catch (e: Exception) {
            println("[ERROR] 设置SpeexDSP回声抑制级别异常: ${e.message}")
        }
    }

    /**
     * 设置有语音时的回声抑制级别
     * @param suppressActiveLevel 有语音时的回声抑制级别(dB)，负值，如-45
     */
    fun setEchoSuppressActive(suppressActiveLevel: Int) {
        if (!initialized || preprocessState == null) return
        try {
            // 验证范围
            val clampedSuppressActive = max(-65, min(-15, suppressActiveLevel))
            
            // 保存新设置
            echoSuppressActive = clampedSuppressActive
            
            // 应用设置
            speex_preprocess_ctl(
                preprocessState,
                SPEEX_PREPROCESS_SET_ECHO_SUPPRESS_ACTIVE,
                clampedSuppressActive.toPointer()
            )
            println("[INFO] SpeexDSP语音时回声抑制级别设置为: $clampedSuppressActive dB")
        } catch (e: Exception) {
            println("[ERROR] 设置SpeexDSP语音时回声抑制级别异常: ${e.message}")
        }
    }
    
    /**
     * 启用或禁用自适应环境处理
     * @param enabled 是否启用
     */
    fun setAdaptiveProcessingEnabled(enabled: Boolean) {
        adaptiveGainEnabled = enabled
        println("[INFO] SpeexDSP自适应环境处理${if (enabled) "已启用" else "已禁用"}")
    }

    /**
     * 处理音频帧
     * 应用回声消除（如果有参考信号）、降噪和AGC
     * @param audioData 输入音频帧
     * @return 处理后的音频帧
     */
    fun process(audioData: ShortArray): ShortArray {
        if (!initialized || audioData.size != frameSize) {
            // 如果未初始化或帧长度不匹配，返回原始音频
            if (audioData.size != frameSize) {
                println("[WARN] SpeexDSP帧长不匹配: 期望=$frameSize, 实际=${audioData.size}")
            }
            return audioData
        }

        try {
            processCount++

            // 创建输入和输出缓冲区
            val inputBuffer = nativeHeap.allocArray<ShortVar>(frameSize)
            val outputBuffer = nativeHeap.allocArray<ShortVar>(frameSize)

            // 复制输入音频到缓冲区
            for (i in 0 until frameSize) {
                inputBuffer[i] = audioData[i]
            }
            
            // 计算音频能量并更新环境检测
            if (adaptiveGainEnabled) {
                val energy = calculateEnergy(audioData)
                updateEnvironmentDetection(energy)
            }

            // 1. 应用回声消除(如果启用且有参考信号)
            if (echoState != null && hasReference) {
                // 准备回声参考信号的缓冲区
                val refBuffer = nativeHeap.allocArray<ShortVar>(frameSize)

                // 复制最新的参考信号
                for (i in 0 until frameSize) {
                    refBuffer[i] = playbackReference[i]
                }

                // 执行回声消除
                speex_echo_cancellation(echoState, inputBuffer, refBuffer, outputBuffer)

                // 释放参考缓冲区
                nativeHeap.free(refBuffer.rawValue)

                // 重置参考信号标志
                hasReference = false
            } else {
                // 没有回声消除，直接复制输入到输出
                for (i in 0 until frameSize) {
                    outputBuffer[i] = inputBuffer[i]
                }
            }

            // 2. 应用预处理（降噪、AGC、VAD）
            if (preprocessState != null) {
                // 在SpeexDSP中，预处理直接修改输入缓冲区
                speex_preprocess_run(preprocessState, outputBuffer)

                // 降低噪声估计更新频率，但保持足够的更新率以适应环境变化
                if (processCount % updateInterval == 0) {
                    speex_preprocess_estimate_update(preprocessState, outputBuffer)
                }
            }

            // 3. 创建结果数组
            val result = ShortArray(frameSize)
            for (i in 0 until frameSize) {
                result[i] = outputBuffer[i]
            }

            // 释放缓冲区
            nativeHeap.free(inputBuffer.rawValue)
            nativeHeap.free(outputBuffer.rawValue)

            return result
        } catch (e: Exception) {
            println("[ERROR] SpeexDSP处理异常: ${e.message}")
            return audioData  // 错误时返回原始音频
        }
    }
    
    /**
     * 计算音频帧的能量
     */
    private fun calculateEnergy(audioData: ShortArray): Float {
        var sum = 0.0f
        for (sample in audioData) {
            sum += (sample * sample)
        }
        return kotlin.math.sqrt(sum / audioData.size)
    }
    
    /**
     * 更新环境检测
     * 根据音频能量判断环境类型并调整参数
     */
    private fun updateEnvironmentDetection(currentEnergy: Float) {
        // 更新能量历史
        energyHistory[energyHistoryIndex] = currentEnergy
        energyHistoryIndex = (energyHistoryIndex + 1) % energyHistory.size
        
        // 每100帧分析一次环境
        if (processCount % 100 == 0) {
            // 计算能量统计
            var sum = 0.0f
            var count = 0
            var max = 0.0f
            
            for (energy in energyHistory) {
                if (energy > 0) {
                    sum += energy
                    count++
                    if (energy > max) max = energy
                }
            }
            
            if (count > 0) {
                val avgEnergy = sum / count
                
                // 判断环境类型
                val newEnvironmentType = when {
                    avgEnergy < 500 -> EnvironmentType.QUIET
                    avgEnergy < 2000 -> EnvironmentType.NORMAL
                    avgEnergy < 5000 -> EnvironmentType.NOISY
                    else -> EnvironmentType.REVERBERANT
                }
                
                // 如果环境类型变化，而且距离上次变化超过稳定期
                val currentTime = platform.posix.time(null).toInt() * 1000L
                if (newEnvironmentType != environmentType && 
                    (currentTime - lastEnvChangeTime) > envChangeDelayMs) {
                    
                    environmentType = newEnvironmentType
                    lastEnvChangeTime = currentTime
                    
                    // 根据环境类型调整参数
                    when (environmentType) {
                        EnvironmentType.QUIET -> {
                            // 安静环境：较弱的降噪、回声抑制和AGC
                            setDenoiseLevel(3)
                            setEchoSuppress(-25)
                            setEchoSuppressActive(-35)
                            setAgcLevel(12000)
                            setAgcMaxGain(8.0f)
                            println("[INFO] 检测到安静环境，已调整音频处理参数")
                        }
                        EnvironmentType.NORMAL -> {
                            // 正常环境：中等设置
                            setDenoiseLevel(5)
                            setEchoSuppress(-35)
                            setEchoSuppressActive(-45)
                            setAgcLevel(15000)
                            setAgcMaxGain(12.0f)
                            println("[INFO] 检测到正常环境，已调整音频处理参数")
                        }
                        EnvironmentType.NOISY -> {
                            // 嘈杂环境：较强的降噪和AGC
                            setDenoiseLevel(8)
                            setEchoSuppress(-40)
                            setEchoSuppressActive(-50)
                            setAgcLevel(18000)
                            setAgcMaxGain(15.0f)
                            println("[INFO] 检测到嘈杂环境，已调整音频处理参数")
                        }
                        EnvironmentType.REVERBERANT -> {
                            // 混响环境：最强的降噪和回声抑制
                            setDenoiseLevel(10)
                            setEchoSuppress(-45)
                            setEchoSuppressActive(-55)
                            setAgcLevel(16000)
                            setAgcMaxGain(10.0f)
                            println("[INFO] 检测到混响环境，已调整音频处理参数")
                        }
                    }
                }
            }
        }
    }

    /**
     * 设置回声消除的播放参考信号
     * @param playbackData 播放的音频数据
     */
    fun setPlaybackReference(playbackData: ShortArray) {
        if (!initialized || echoState == null) {
            return
        }

        try {
            // 保存参考信号到成员变量
            playbackReference = playbackData.copyOf()
            hasReference = true

            // 将参考信号添加到缓冲区
            playbackBuffer.add(playbackData.copyOf())

            // 保持缓冲区大小在限制内
            while (playbackBuffer.size > 3) {
                playbackBuffer.removeAt(0)
            }

            // 直接发送参考信号到回声消除器
            if (echoState != null && playbackData.size == frameSize) {
                val refBuffer = nativeHeap.allocArray<ShortVar>(frameSize)

                // 复制参考信号
                for (i in 0 until frameSize) {
                    refBuffer[i] = playbackData[i]
                }
                // 将参考信号注入回声消除器
                speex_echo_playback(echoState, refBuffer)
                // 释放参考缓冲区
                nativeHeap.free(refBuffer.rawValue)
            }
        } catch (e: Exception) {
            println("[ERROR] SpeexDSP设置回声参考异常: ${e.message}")
        }
    }

    /**
     * 重置处理器状态
     */
    fun reset() {
        if (!initialized) {
            return
        }

        try {
            if (echoState != null) {
                speex_echo_state_reset(echoState)
            }
            // 清空缓冲区
            playbackBuffer.clear()
            playbackReference = ShortArray(0)
            hasReference = false
            
            // 重置环境检测
            for (i in energyHistory.indices) {
                energyHistory[i] = 0.0f
            }
            energyHistoryIndex = 0
            environmentType = EnvironmentType.NORMAL
            
            // 重置回声延迟估计
            echoDelayEstimate = 0
            echoDelayConfidence = 0.0f

            println("[INFO] SpeexDSP处理器已重置")
        } catch (e: Exception) {
            println("[ERROR] 重置SpeexDSP处理器异常: ${e.message}")
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        if (!initialized) {
            return
        }

        try {
            if (preprocessState != null) {
                speex_preprocess_state_destroy(preprocessState)
                preprocessState = null
            }

            if (echoState != null) {
                speex_echo_state_destroy(echoState)
                echoState = null
            }

            playbackBuffer.clear()
            initialized = false
            println("[INFO] SpeexDSP处理器资源已释放")
        } catch (e: Exception) {
            println("[ERROR] 释放SpeexDSP资源异常: ${e.message}")
        }
    }
    
    /**
     * 获取当前处理器状态报告
     * @return 状态报告字符串
     */
    fun getStatusReport(): String {
        val sb = StringBuilder()
        sb.append("=== SpeexDSP处理器状态 ===\n")
        sb.append("初始化状态: $initialized\n")
        sb.append("采样率: $sampleRate, 帧长: $frameSize\n")
        sb.append("降噪级别: ${-noiseSuppress/4}/10, 回声抑制: ${echoSuppress}dB\n")
        sb.append("AGC目标电平: $agcLevel, 最大增益: ${maxGain}dB\n")
        sb.append("环境类型: $environmentType\n")
        sb.append("自适应处理: ${if (adaptiveGainEnabled) "启用" else "禁用"}\n")
        sb.append("处理计数: $processCount\n")
        return sb.toString()
    }
} 