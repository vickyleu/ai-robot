@file:OptIn(ExperimentalForeignApi::class)

package snowboyPiper.interop

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import com.airobot.speexdspinterop.SpeexEchoState
import com.airobot.speexdspinterop.SpeexPreprocessState
import com.airobot.speexdspinterop.speex_echo_cancellation
import com.airobot.speexdspinterop.speex_echo_capture
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
import com.airobot.speexdspinterop.SPEEX_PREPROCESS_SET_DENOISE
import com.airobot.speexdspinterop.SPEEX_PREPROCESS_SET_AGC
import com.airobot.speexdspinterop.SPEEX_PREPROCESS_SET_VAD
import com.airobot.speexdspinterop.SPEEX_PREPROCESS_SET_NOISE_SUPPRESS
import com.airobot.speexdspinterop.SPEEX_PREPROCESS_GET_PROB
import com.airobot.speexdspinterop.SPEEX_PREPROCESS_SET_AGC_LEVEL
import com.airobot.speexdspinterop.SPEEX_PREPROCESS_SET_AGC_MAX_GAIN
import com.airobot.speexdspinterop.SPEEX_ECHO_SET_SAMPLING_RATE

/**
 * SpeexDSP处理器
 * 提供高质量的回声消除(AEC)、自动增益控制(AGC)、降噪和VAD功能
 */
class SpeexDspProcessor {
    // 状态变量
    private var initialized = false
    private var sampleRate = 48000
    private var frameSize = 480  // 30ms @ 16kHz
    private var filterLength = 1600  // 根据房间回声特性调整
    
    // Speex预处理器状态
    private var preprocessState: CPointer<SpeexPreprocessState>? = null
    
    // Speex回声消除器状态
    private var echoState: CPointer<SpeexEchoState>? = null
    
    // 回声消除参考缓冲
    private var hasReference = false
    private var playbackReference = ShortArray(0)
    
    // 播放参考信号缓冲（用于延迟补偿）
    private val playbackBuffer = ArrayList<ShortArray>(5)  // 保存最近5帧播放数据
    private val bufferMaxSize = 5  // 最多缓存5帧以处理不同延迟
    
    // 性能计数器
    private var processedFrames = 0
    
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
        sampleRate: Int = 48000,
        frameSize: Int = 480,
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
        this.filterLength = (sampleRate / 1000) * 100  // 100ms回声尾
        
        try {
            // 初始化预处理器
            preprocessState = speex_preprocess_state_init(frameSize, sampleRate)
            
            // 配置预处理器
            val denoiseEnabled = nativeHeap.alloc<UInt>()
            denoiseEnabled.value = if (enableDenoise) 1 else 0
            if (enableDenoise) 1 else 0
            val agcEnabled = if (enableAgc) 1 else 0
            val vadEnabled = if (enableVad) 1 else 0
            
            speex_preprocess_ctl(preprocessState, SPEEX_PREPROCESS_SET_DENOISE, denoiseEnabled.ptr)
            speex_preprocess_ctl(preprocessState, SPEEX_PREPROCESS_SET_AGC, agcEnabled)
            speex_preprocess_ctl(preprocessState, SPEEX_PREPROCESS_SET_VAD, vadEnabled)
            
            // 设置降噪强度 (dB, 越小降噪越强，-15到-40范围) 
            val noiseSuppress = -30  // 较强的降噪
            speex_preprocess_ctl(preprocessState, SPEEX_PREPROCESS_SET_NOISE_SUPPRESS, noiseSuppress)
            
            // 初始化回声消除器
            if (enableEcho) {
                echoState = speex_echo_state_init(frameSize, filterLength)
                
                // 配置回声消除器
                speex_echo_ctl(echoState, SPEEX_ECHO_SET_SAMPLING_RATE, sampleRate)
                speex_echo_ctl(echoState, SPEEX_ECHO_SET_FRAME_SIZE, frameSize)
                
                // 设置回声抑制强度 (dB)
                val echoSuppress = -32  // 静音期间的抑制
                val echoSuppressActive = -36  // 语音期间的抑制
                speex_echo_ctl(echoState, SPEEX_ECHO_SET_SUPPRESS, echoSuppress)
                speex_echo_ctl(echoState, SPEEX_ECHO_SET_SUPPRESS_ACTIVE, echoSuppressActive)
                
                // 如果需要，将回声消除器与预处理器链接起来
                // speex_preprocess_ctl(preprocessState, SPEEX_PREPROCESS_SET_ECHO_STATE, echoState)
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
            processedFrames++
            
            // 创建输入和输出缓冲区
            val inputBuffer = nativeHeap.allocArray<ShortVar>(frameSize)
            val outputBuffer = nativeHeap.allocArray<ShortVar>(frameSize)
            
            // 复制输入音频到缓冲区
            for (i in 0 until frameSize) {
                inputBuffer[i] = audioData[i]
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
                val vadResult = speex_preprocess_run(preprocessState, outputBuffer)
                
                // 获取VAD概率（仅供调试）
                if (processedFrames % 500 == 0) {
                    val probPtr = nativeHeap.alloc<Int>()
                    speex_preprocess_ctl(preprocessState, SPEEX_PREPROCESS_GET_PROB, probPtr.ptr)
                    val speechProb = probPtr.value
                    nativeHeap.free(probPtr.rawValue)
                    // println("[DEBUG] SpeexDSP VAD概率: $speechProb%, 语音=$vadResult")
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
            while (playbackBuffer.size > bufferMaxSize) {
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
     * 设置降噪级别
     * @param level 降噪级别 (0-10)，越大越强
     */
    fun setDenoiseLevel(level: Int) {
        if (!initialized || preprocessState == null) {
            return
        }
        
        try {
            // 将级别0-10映射到-15到-45 dB
            val suppressDb = -15 - (level * 3)
            speex_preprocess_ctl(preprocessState, SPEEX_PREPROCESS_SET_NOISE_SUPPRESS, suppressDb)
        } catch (e: Exception) {
            println("[ERROR] 设置SpeexDSP降噪级别异常: ${e.message}")
        }
    }
    
    /**
     * 设置AGC目标电平
     * @param level 目标电平
     */
    fun setAgcLevel(level: Int) {
        if (!initialized || preprocessState == null) {
            return
        }
        
        try {
            speex_preprocess_ctl(preprocessState, SPEEX_PREPROCESS_SET_AGC_LEVEL, level)
        } catch (e: Exception) {
            println("[ERROR] 设置SpeexDSP AGC电平异常: ${e.message}")
        }
    }
    
    /**
     * 设置AGC最大增益
     * @param maxGain 最大增益 (dB)
     */
    fun setAgcMaxGain(maxGain: Float) {
        if (!initialized || preprocessState == null) {
            return
        }
        
        try {
            speex_preprocess_ctl(preprocessState, SPEEX_PREPROCESS_SET_AGC_MAX_GAIN, maxGain.toInt())
        } catch (e: Exception) {
            println("[ERROR] 设置SpeexDSP AGC最大增益异常: ${e.message}")
        }
    }
    
    /**
     * 设置回声抑制级别
     * @param level 回声抑制级别 (dB)
     */
    fun setEchoSuppress(level: Int) {
        if (!initialized || echoState == null) {
            return
        }
        
        try {
            speex_echo_ctl(echoState, SPEEX_ECHO_SET_SUPPRESS, level)
        } catch (e: Exception) {
            println("[ERROR] 设置SpeexDSP回声抑制级别异常: ${e.message}")
        }
    }
    
    /**
     * 设置语音期间的回声抑制级别
     * @param level 回声抑制级别 (dB)
     */
    fun setEchoSuppressActive(level: Int) {
        if (!initialized || echoState == null) {
            return
        }
        
        try {
            speex_echo_ctl(echoState, SPEEX_ECHO_SET_SUPPRESS_ACTIVE, level)
        } catch (e: Exception) {
            println("[ERROR] 设置SpeexDSP活动回声抑制级别异常: ${e.message}")
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