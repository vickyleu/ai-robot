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

/**
 * SpeexDSP处理器
 * 提供高质量的回声消除(AEC)、自动增益控制(AGC)、降噪和VAD功能
 */
class SpeexDspProcessor {
    // 状态变量
    private var initialized = false
    private var sampleRate = 16000  // 降低默认采样率
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
    private val playbackBuffer = ArrayList<ShortArray>(3)  // 减少缓存帧数

    // 性能优化变量
    private var processCount = 0
    private val updateInterval = 50 // 降低更新频率节省CPU

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
     */
    fun initialize(
        sampleRate: Int = 16000,
        frameSize: Int = 320,
        enableDenoise: Boolean = true,
        enableAgc: Boolean = true,
        enableVad: Boolean = true,
        enableEcho: Boolean = true
    ): Boolean {
        if (initialized) {
            reset()  // 重置现有状态
        }

        this.sampleRate = sampleRate
        this.frameSize = frameSize
        this.filterLength = minOf(sampleRate / 16, 1024) // 最多64ms，不超过1024

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

            // 降噪设置
            speex_preprocess_ctl(
                preprocessState,
                SPEEX_PREPROCESS_SET_NOISE_SUPPRESS,
                (-25).toPointer()
            )

            // AGC设置
            if (enableAgc) {
                speex_preprocess_ctl(
                    preprocessState,
                    SPEEX_PREPROCESS_SET_AGC_LEVEL,
                    15000.toPointer()
                )
                speex_preprocess_ctl(
                    preprocessState,
                    SPEEX_PREPROCESS_SET_AGC_MAX_GAIN,
                    12.toPointer()
                )
            }

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
            println("[INFO] SpeexDSP处理器已初始化: 采样率=$sampleRate, 帧长=$frameSize, 滤波器长度=$filterLength")
            return true
        } catch (e: Exception) {
            println("[ERROR] SpeexDSP初始化失败: ${e.message}")
            release()  // 确保释放任何已分配的资源
            return false
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
            val suppressValue = -(level * 4) // 0->0, 10->-40
            speex_preprocess_ctl(
                preprocessState,
                SPEEX_PREPROCESS_SET_NOISE_SUPPRESS,
                suppressValue.toPointer()
            )
            println("[INFO] SpeexDSP降噪级别设置为: $level (抑制: $suppressValue dB)")
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
            speex_preprocess_ctl(
                preprocessState,
                SPEEX_PREPROCESS_SET_AGC_LEVEL,
                level.toPointer()
            )
            println("[INFO] SpeexDSP AGC目标电平设置为: $level")
        } catch (e: Exception) {
            println("[ERROR] 设置SpeexDSP AGC电平异常: ${e.message}")
        }
    }

    /**
     * 设置自动增益控制最大增益
     * @param maxGain 最大增益(dB)
     */
    fun setAgcMaxGain(maxGain: Float) {
        if (!initialized || preprocessState == null) return
        try {
            speex_preprocess_ctl(
                preprocessState,
                SPEEX_PREPROCESS_SET_AGC_MAX_GAIN,
                maxGain.toInt().toPointer()
            )
            println("[INFO] SpeexDSP AGC最大增益设置为: $maxGain dB")
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
            // 使用预处理器控制接口设置回声抑制级别
            speex_preprocess_ctl(
                preprocessState,
                SPEEX_PREPROCESS_SET_ECHO_SUPPRESS,
                suppressLevel.toPointer()
            )
            println("[INFO] SpeexDSP回声抑制级别设置为: $suppressLevel dB")
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
            // 使用预处理器控制接口设置有语音时的回声抑制级别
            speex_preprocess_ctl(
                preprocessState,
                SPEEX_PREPROCESS_SET_ECHO_SUPPRESS_ACTIVE,
                suppressActiveLevel.toPointer()
            )
            println("[INFO] SpeexDSP语音时回声抑制级别设置为: $suppressActiveLevel dB")
        } catch (e: Exception) {
            println("[ERROR] 设置SpeexDSP语音时回声抑制级别异常: ${e.message}")
        }
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

            // 简化处理：由于回声和预处理无法使用，直接复制输入到输出
            for (i in 0 until frameSize) {
                outputBuffer[i] = inputBuffer[i]
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

                // 降低噪声估计更新频率
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
} 