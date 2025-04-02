@file:OptIn(ExperimentalForeignApi::class, ExperimentalStdlibApi::class, ExperimentalTime::class)
@file:Suppress("FunctionName", "unused", "UNUSED_PARAMETER")

package com.airobot.device.yanapi.vosk

import com.airobot.alsainterop.EAGAIN
import com.airobot.alsainterop.EBADFD
import com.airobot.alsainterop.EBUSY
import com.airobot.alsainterop.EINTR
import com.airobot.alsainterop.EINVAL
import com.airobot.alsainterop.EIO
import com.airobot.alsainterop.ENODEV
import com.airobot.alsainterop.ENOENT
import com.airobot.alsainterop.ENOMEM
import com.airobot.alsainterop.ENOSYS
import com.airobot.alsainterop.EPIPE
import com.airobot.alsainterop.ESTRPIPE
import com.airobot.alsainterop.SND_MIXER_SCHN_FRONT_LEFT
import com.airobot.alsainterop.SND_PCM_ACCESS_RW_INTERLEAVED
import com.airobot.alsainterop.SND_PCM_FORMAT_S16_LE
import com.airobot.alsainterop.SND_PCM_STATE_DISCONNECTED
import com.airobot.alsainterop.SND_PCM_STATE_DRAINING
import com.airobot.alsainterop.SND_PCM_STATE_OPEN
import com.airobot.alsainterop.SND_PCM_STATE_PAUSED
import com.airobot.alsainterop.SND_PCM_STATE_PREPARED
import com.airobot.alsainterop.SND_PCM_STATE_RUNNING
import com.airobot.alsainterop.SND_PCM_STATE_SETUP
import com.airobot.alsainterop.SND_PCM_STATE_SUSPENDED
import com.airobot.alsainterop.SND_PCM_STATE_XRUN
import com.airobot.alsainterop.SND_PCM_STREAM_CAPTURE
import com.airobot.alsainterop._snd_mixer
import com.airobot.alsainterop._snd_mixer_elem
import com.airobot.alsainterop._snd_pcm
import com.airobot.alsainterop._snd_pcm_state
import com.airobot.alsainterop._snd_pcm_status
import com.airobot.alsainterop.snd_mixer_attach
import com.airobot.alsainterop.snd_mixer_close
import com.airobot.alsainterop.snd_mixer_elem_next
import com.airobot.alsainterop.snd_mixer_first_elem
import com.airobot.alsainterop.snd_mixer_load
import com.airobot.alsainterop.snd_mixer_open
import com.airobot.alsainterop.snd_mixer_selem_get_capture_volume
import com.airobot.alsainterop.snd_mixer_selem_get_capture_volume_range
import com.airobot.alsainterop.snd_mixer_selem_get_name
import com.airobot.alsainterop.snd_mixer_selem_has_capture_switch
import com.airobot.alsainterop.snd_mixer_selem_has_capture_volume
import com.airobot.alsainterop.snd_mixer_selem_register
import com.airobot.alsainterop.snd_mixer_selem_set_capture_switch_all
import com.airobot.alsainterop.snd_mixer_selem_set_capture_volume_all
import com.airobot.alsainterop.snd_pcm_avail_update
import com.airobot.alsainterop.snd_pcm_close
import com.airobot.alsainterop.snd_pcm_drain
import com.airobot.alsainterop.snd_pcm_hw_params
import com.airobot.alsainterop.snd_pcm_hw_params_any
import com.airobot.alsainterop.snd_pcm_hw_params_free
import com.airobot.alsainterop.snd_pcm_hw_params_get_buffer_size
import com.airobot.alsainterop.snd_pcm_hw_params_get_period_size
import com.airobot.alsainterop.snd_pcm_hw_params_malloc
import com.airobot.alsainterop.snd_pcm_hw_params_set_access
import com.airobot.alsainterop.snd_pcm_hw_params_set_buffer_size_near
import com.airobot.alsainterop.snd_pcm_hw_params_set_channels
import com.airobot.alsainterop.snd_pcm_hw_params_set_format
import com.airobot.alsainterop.snd_pcm_hw_params_set_period_size_near
import com.airobot.alsainterop.snd_pcm_hw_params_set_rate_near
import com.airobot.alsainterop.snd_pcm_hw_params_t
import com.airobot.alsainterop.snd_pcm_nonblock
import com.airobot.alsainterop.snd_pcm_open
import com.airobot.alsainterop.snd_pcm_prepare
import com.airobot.alsainterop.snd_pcm_readi
import com.airobot.alsainterop.snd_pcm_recover
import com.airobot.alsainterop.snd_pcm_start
import com.airobot.alsainterop.snd_pcm_state
import com.airobot.alsainterop.snd_pcm_status
import com.airobot.alsainterop.snd_pcm_status_get_avail
import com.airobot.alsainterop.snd_pcm_status_malloc
import com.airobot.alsainterop.snd_pcm_status_t
import com.airobot.alsainterop.snd_pcm_sw_params
import com.airobot.alsainterop.snd_pcm_sw_params_current
import com.airobot.alsainterop.snd_pcm_sw_params_free
import com.airobot.alsainterop.snd_pcm_sw_params_malloc
import com.airobot.alsainterop.snd_pcm_sw_params_set_avail_min
import com.airobot.alsainterop.snd_pcm_sw_params_set_start_threshold
import com.airobot.alsainterop.snd_pcm_sw_params_t
import com.airobot.alsainterop.snd_strerror
import com.airobot.device.yanapi.vosk.YanVoskSpeechService.Companion.executeCommand
import com.airobot.voskinterop.VoskModel
import com.airobot.voskinterop.VoskRecognizer
import com.airobot.voskinterop.vosk_model_free
import com.airobot.voskinterop.vosk_model_new
import com.airobot.voskinterop.vosk_recognizer_accept_waveform
import com.airobot.voskinterop.vosk_recognizer_accept_waveform_f
import com.airobot.voskinterop.vosk_recognizer_accept_waveform_s
import com.airobot.voskinterop.vosk_recognizer_free
import com.airobot.voskinterop.vosk_recognizer_new
import com.airobot.voskinterop.vosk_recognizer_partial_result
import com.airobot.voskinterop.vosk_recognizer_reset
import com.airobot.voskinterop.vosk_recognizer_result
import com.airobot.voskinterop.vosk_recognizer_set_max_alternatives
import com.airobot.voskinterop.vosk_recognizer_set_words
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.CPointerVarOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.cValuesOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.AtomicInt
import kotlin.math.absoluteValue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * YAN设备语音识别服务 - 使用ALSA和Vosk实现
 *
 * 该类提供了使用ALSA捕获麦克风音频并使用Vosk进行实时语音识别的功能。
 * 主要特性：
 * 1. 使用ALSA API直接访问麦克风硬件
 * 2. 使用Vosk进行离线语音识别
 * 3. 支持实时语音识别结果流
 * 4. 支持配置识别参数（如采样率、通道数等）
 */
class YanVoskSpeechRecognizer {
    companion object {
        // ALSA 错误码及其描述
        private val alsaErrorDescriptions = mapOf(
            -EPIPE to ("EPIPE" to "Broken pipe - 通常表示缓冲区下溢或上溢 (Broken pipe - usually indicates buffer underrun or overrun)"),
            -EBADFD to ("EBADFD" to "PCM descriptor is bad (PCM 描述符无效)"),
            -ESTRPIPE to ("ESTRPIPE" to "Stream is suspended (流已挂起)"),
            -EAGAIN to ("EAGAIN" to "Resource temporarily unavailable (try again) (资源暂时不可用，请重试)"),
            -EINTR to ("EINTR" to "Interrupted system call (系统调用被中断)"),
            -EIO to ("EIO" to "Input/output error (输入/输出错误)"),
            -ENODEV to ("ENODEV" to "No such device (无此设备)"),
            -ENOENT to ("ENOENT" to "No such file or directory (无此文件或目录)"),
            -EBUSY to ("EBUSY" to "Device or resource busy (设备或资源忙)"),
            -EINVAL to ("EINVAL" to "Invalid argument (无效参数)"),
            -ENOMEM to ("ENOMEM" to "Out of memory (内存不足)"),
            -ENOSYS to ("ENOSYS" to "Function not implemented (功能未实现)")
            // 可以根据需要添加更多错误码
            // 参考: https://www.alsa-project.org/alsa-doc/alsa-lib/group___p_c_m.html 和 errno-base.h
        )

        /**
         * 根据ALSA错误码获取中英文描述
         *
         * @param errorCode ALSA函数返回的错误码 (负值)
         * @return Pair<String, String> 包含英文和中文描述，如果未找到则返回默认信息
         */
        fun getAlsaErrorDescription(errorCode: Int): Pair<String, String> {
            return alsaErrorDescriptions[errorCode]
                ?: ("Unknown Error Code" to "未知错误码 ($errorCode)")
        }
    }

    // ALSA配置参数 - 优化以避免Broken pipe错误
    private var deviceName = "default"  // ALSA设备名称
    private var sampleRate = 16000      // 采样率 (Hz)
    private var channels = 1           // 通道数 (单声道)
    private var bufferSize = 8192       // 缓冲区大小 - 调整为更常用的值
    private var periodSize = 2048       // 周期大小 - 保持不变，但确保与缓冲区大小兼容
    private var periods = 4             // 周期数 - 根据新的缓冲区和周期大小计算
    private var micVolume = 100         // 麦克风音量 (0-100)

    // 错误恢复配置
    private var maxErrorRetries = 10    // 最大错误重试次数 - 增加重试次数
    private var errorRecoveryDelay = 1000L // 错误恢复延迟(毫秒) - 增加延迟时间
    private var brokenPipeRetryDelay = 2000L // Broken pipe特定恢复延迟(毫秒)

    // Vosk模型配置
    private var modelPath = "/usr/local/share/yanshee-model"  // Vosk模型路径

//    // 新增模型路径验证
//    private fun validateModelPath(): Boolean {
//        return File(modelPath).run {
//            when {
//                !exists() -> {
//                    println("[MODEL_ERROR] 模型路径不存在: $modelPath")
//                    false
//                }
//                !isDirectory -> {
//                    println("[MODEL_ERROR] 模型路径不是目录: $modelPath")
//                    false
//                }
//                !canRead() -> {
//                    println("[MODEL_ERROR] 模型目录不可读，请检查权限: $modelPath")
//                    false
//                }
//                else -> {
//                    println("[MODEL_DEBUG] 模型路径验证通过，包含文件:\n${listFiles()?.joinToString("\n") { it.name } ?: "空目录"}")
//                    true
//                }
//            }
//        }
//    }

    // 状态管理
    private val _recognitionState = MutableStateFlow(RecognitionState.IDLE)
    val recognitionState: StateFlow<RecognitionState> = _recognitionState

    // 识别结果
    private val _recognitionResult = MutableStateFlow<RecognitionResult?>(null)
    val recognitionResult: StateFlow<RecognitionResult?> = _recognitionResult

    // 协程作用域和任务
    private var recognitionJob: Job? = null
    private val recognitionScope = CoroutineScope(Dispatchers.Default)

    // ALSA和Vosk资源
    private var pcmHandle: CPointer<_snd_pcm>? = null
    private var mixerHandle: CPointer<_snd_mixer>? = null
    private var voskModel: CPointer<VoskModel>? = null
    private var voskRecognizer: CPointer<VoskRecognizer>? = null

    // 控制标志
    private val isRunning = AtomicInt(0)

    /**
     * 初始化语音识别器
     *
     * @param deviceName ALSA设备名称，默认为"default"
     * @param modelPath Vosk模型路径
     * @param sampleRate 音频采样率
     * @param micVolume 麦克风音量 (0-100)
     * @return 初始化是否成功
     */
    fun initialize(
        deviceName: String = this.deviceName,
        modelPath: String = this.modelPath,
        sampleRate: Int = this.sampleRate,
        micVolume: Int = this.micVolume
    ): Boolean {
        this.deviceName = deviceName
        this.modelPath = modelPath
        this.sampleRate = sampleRate
        this.micVolume = micVolume.coerceIn(0, 100) // 确保音量在有效范围内

        return initVoskModel() && initAlsa() && initMixer()
    }

    /**
     * 初始化Vosk模型
     *
     * @return 初始化是否成功
     */
    private fun initVoskModel(): Boolean {
        if (voskModel != null) {
            // 模型已经初始化
            return true
        }

        memScoped {
            // 加载Vosk模型
            voskModel = vosk_model_new(modelPath)
            if (voskModel == null) {
                _recognitionState.value = RecognitionState.ERROR
                return false
            }

            return true
        }
    }

    /**
     * 初始化ALSA音频捕获
     *
     * @return 初始化是否成功
     */
    private fun initAlsa(): Boolean {
        if (pcmHandle != null) {
            // ALSA已经初始化，检查设备状态
            val status = snd_pcm_state(pcmHandle)
            if (status == SND_PCM_STATE_OPEN || status == SND_PCM_STATE_SETUP ||
                status == SND_PCM_STATE_PREPARED || status == SND_PCM_STATE_RUNNING
            ) {
                // 设备状态正常，执行预防性检查以避免潜在的Broken pipe
                val err = snd_pcm_prepare(pcmHandle)
                if (err < 0) {
                    println("[WARNING] 预防性PCM准备失败: ${snd_strerror(err)?.toKString()}，重新初始化设备")
                    snd_pcm_close(pcmHandle)
                    pcmHandle = null
                    // 继续执行初始化
                } else {
                    return true
                }
            }else if (status == SND_PCM_STATE_XRUN) {
                // xcrun 状态，尝试恢复
                println("[WARNING] ALSA设备状态异常 (${getPcmStateName(status)})，尝试恢复")
                val recoverResult = snd_pcm_recover(pcmHandle, -EPIPE, 1)
                println("[ALSA-RECOVERY] 执行错误恢复操作，返回码: $recoverResult (${snd_strerror(recoverResult)?.toKString()})")
                if (recoverResult < 0) {
                    println("[ERROR] ALSA设备恢复失败: ${snd_strerror(recoverResult)?.toKString()}")
                    snd_pcm_close(pcmHandle)
                    pcmHandle = null
                    // 继续执行初始化
                } else {
                    println("[INFO] ALSA设备恢复成功")
                    return true
                }
            } else {
                // 设备状态异常，需要重新初始化
                println("[WARNING] ALSA设备状态异常 (${getPcmStateName(status)})，尝试重新初始化")
                snd_pcm_close(pcmHandle)
                pcmHandle = null
                // 继续执行初始化
            }
        }
        println("[DEBUG] 初始化ALSA音频捕获，设备: $deviceName, 采样率: $sampleRate Hz, 缓冲区: $bufferSize, 周期: $periodSize")
        // 新增设备状态检查
        val configCmd = "arecord -D $deviceName --dump-hw-params 2>&1"
//        println("[HARDWARE] 正在检查音频设备配置:\n${
//            runCatching {
//                withContext(Di) {  }
//            val arecordOutput = executeCommand(configCmd, timeoutMs = 500)
//            println("[INFO] 系统麦克风设备检查结果:")
//            println(arecordOutput)
//            Runtime.getRuntime().exec(arrayOf("sh", "-c", configCmd))
//            .inputStream.bufferedReader().use { it.readText() }
//            }
//                .getOrElse { "执行失败: ${it.message}" }}")
        memScoped {
            // 正确使用方法 https://github.com/UmaRajamani/korge/blob/main/korge-core/src/common/korlibs/audio/sound/backend/ALSA.kt
            val pcmHandlePtr = nativeHeap.allocPointerTo<_snd_pcm>()
            // 打开PCM设备进行捕获，使用阻塞模式并添加错误恢复标志
            var err = snd_pcm_open(
                pcm = pcmHandlePtr.ptr,
                name = deviceName,
                stream = SND_PCM_STREAM_CAPTURE,
                mode = 0 // 使用阻塞模式，更可靠的初始化
            )
            if (err < 0) {
                val (errName, errDesc) = getAlsaErrorDescription(err)
                println("[ALSA-ERROR] 设备打开失败 | 代码: $err (${errName}) | 描述: $errDesc")
                println("[TROUBLESHOOTING] 解决方案:\n1. 执行 'arecord -l' 确认设备列表\n2. 检查用户组权限: groups | grep audio\n3. 尝试指定其他设备如plughw:0,0")
                // 尝试使用默认设备
                if (deviceName != "default") {
                    println("[RECOVERY] 尝试使用默认音频设备...")
                    deviceName = "default"
                    err = snd_pcm_open(
                        pcm = pcmHandlePtr.ptr,
                        name = deviceName,
                        stream = SND_PCM_STREAM_CAPTURE,
                        mode = 0
                    )

                    if (err < 0) {
                        println("[ERROR] 无法打开默认音频设备: ${snd_strerror(err)?.toKString()}")
                        _recognitionState.value = RecognitionState.ERROR
                        return false
                    }
                } else {
                    _recognitionState.value = RecognitionState.ERROR
                    return false
                }
            }
            val tPcmHandle = pcmHandlePtr.value ?: error("分配失败")
            pcmHandle = tPcmHandle
            // 移除 snd_pcm_set_params 调用，直接进行手动硬件参数配置

            // 分配硬件参数结构
            val paramsPtr = nativeHeap.allocPointerTo<snd_pcm_hw_params_t>()
            err = snd_pcm_hw_params_malloc(paramsPtr.ptr)
            if (err < 0) {
                println("[ERROR] 无法分配hw参数内存: ${snd_strerror(err)?.toKString()}")
                snd_pcm_close(pcmHandle)
                pcmHandle = null
                return false
            }
            val hwParams = paramsPtr.value ?: run {
                println("[ERROR] hw参数指针为空")
                snd_pcm_close(pcmHandle)
                pcmHandle = null
                return false
            }

            // 初始化硬件参数结构为设备支持的所有可能值
            err = snd_pcm_hw_params_any(pcmHandle, hwParams)
            if (err < 0) {
                println("[ERROR] 无法初始化hw参数结构: ${snd_strerror(err)?.toKString()}")
                snd_pcm_hw_params_free(hwParams)
                snd_pcm_close(pcmHandle)
                pcmHandle = null
                return false
            }

            // --- 开始设置硬件参数 ---

            // 1. 设置访问类型 (交错模式)
            err = snd_pcm_hw_params_set_access(pcmHandle, hwParams, SND_PCM_ACCESS_RW_INTERLEAVED)
            if (err < 0) {
                println("[ERROR] 无法设置访问类型: ${snd_strerror(err)?.toKString()}")
                // 可以考虑尝试其他访问类型，但 RW_INTERLEAVED 是最常见的
            }

            // 2. 设置采样格式 (16位小端整数)
            err = snd_pcm_hw_params_set_format(pcmHandle, hwParams, SND_PCM_FORMAT_S16_LE)
            if (err < 0) {
                println("[ERROR] 无法设置采样格式: ${snd_strerror(err)?.toKString()}")
                // 可以尝试其他格式，但 S16_LE 是 Vosk 常用的格式
            }

            // 3. 设置采样率 (尝试精确值，如果失败则尝试最近值)
            var actualRate = sampleRate.toUInt()
            err = snd_pcm_hw_params_set_rate_near(pcmHandle, hwParams, cValuesOf(actualRate), null)
            if (err < 0) {
                println("[ERROR] 无法设置采样率 $sampleRate Hz: ${snd_strerror(err)?.toKString()}")
            } else {
                if (actualRate != sampleRate.toUInt()) {
                    println("[WARNING] 实际采样率与请求值不同: 请求=$sampleRate Hz, 实际=$actualRate Hz")
                    // 更新 sampleRate 变量以反映实际值，如果后续逻辑需要
                    // this.sampleRate = actualRate.toInt()
                }
            }

            // 4. 设置通道数
            err = snd_pcm_hw_params_set_channels(pcmHandle, hwParams, channels.toUInt())
            if (err < 0) {
                println("[ERROR] 无法设置通道数 $channels: ${snd_strerror(err)?.toKString()}")
            }

            // 5. 设置周期大小 (Period Size)
            var actualPeriodSize = periodSize.toUInt()
            err = snd_pcm_hw_params_set_period_size_near(
                pcmHandle,
                hwParams,
                cValuesOf(actualPeriodSize),
                null
            )
            if (err < 0) {
                println("[ERROR] 无法设置周期大小 $periodSize: ${snd_strerror(err)?.toKString()}")
            } else {
                if (actualPeriodSize != periodSize.toUInt()) {
                    println("[WARNING] 实际周期大小与请求值不同: 请求=$periodSize, 实际=$actualPeriodSize")
                    this@YanVoskSpeechRecognizer.periodSize = actualPeriodSize.toInt() // 更新内部变量
                }
            }

            // 6. 设置缓冲区大小 (Buffer Size)
            var actualBufferSize = bufferSize.toUInt()
            err = snd_pcm_hw_params_set_buffer_size_near(
                pcmHandle,
                hwParams,
                cValuesOf(actualBufferSize)
            )
            if (err < 0) {
                println("[ERROR] 无法设置缓冲区大小 $bufferSize: ${snd_strerror(err)?.toKString()}")
            } else {
                if (actualBufferSize != bufferSize.toUInt()) {
                    println("[WARNING] 实际缓冲区大小与请求值不同: 请求=$bufferSize, 实际=$actualBufferSize")
                    this@YanVoskSpeechRecognizer.bufferSize = actualBufferSize.toInt() // 更新内部变量
                }
            }

            // --- 验证并应用硬件参数 ---
            err = snd_pcm_hw_params(pcmHandle, hwParams)
            if (err < 0) {
                println("[ERROR] 无法应用硬件参数: ${snd_strerror(err)?.toKString()}")
                snd_pcm_hw_params_free(hwParams)
                snd_pcm_close(pcmHandle)
                pcmHandle = null
                _recognitionState.value = RecognitionState.ERROR
                return false
            }

            // 获取并打印最终生效的硬件参数
            val finalBufferSize = nativeHeap.alloc<UIntVar>()
            val finalPeriodSize = nativeHeap.alloc<UIntVar>()
            snd_pcm_hw_params_get_buffer_size(hwParams, finalBufferSize.ptr)
            snd_pcm_hw_params_get_period_size(hwParams, finalPeriodSize.ptr, null)
            println("[HARDWARE] 最终生效硬件参数: bufferSize=${finalBufferSize.value}, periodSize=${finalPeriodSize.value}")
            // 更新内部变量以匹配实际值
            this@YanVoskSpeechRecognizer.bufferSize = finalBufferSize.value.toInt()
            this@YanVoskSpeechRecognizer.periodSize = finalPeriodSize.value.toInt()
            this@YanVoskSpeechRecognizer.periods =
                (this@YanVoskSpeechRecognizer.bufferSize / this@YanVoskSpeechRecognizer.periodSize) // 重新计算周期数
            println("[HARDWARE] 计算得到的周期数: ${this@YanVoskSpeechRecognizer.periods}")

            // 释放硬件参数结构内存
            snd_pcm_hw_params_free(hwParams)

            // --- 配置软件参数 ---

            // 统一设置软件参数
            memScoped {
                val swParamsPtr = allocPointerTo<snd_pcm_sw_params_t>()
                snd_pcm_sw_params_malloc(swParamsPtr.ptr)
                val swParams = swParamsPtr.value ?: error("软件参数分配失败")

                snd_pcm_sw_params_current(pcmHandle, swParams)

                // 合并参数设置流程
                arrayOf(
                    // 设置 avail_min 为一个周期的大小，确保至少有一个周期的数据可用时才唤醒
                    snd_pcm_sw_params_set_avail_min(pcmHandle, swParams, this@YanVoskSpeechRecognizer.periodSize.convert()),
                    // 设置 start_threshold 为 1，尽快开始传输
                    // 或者设置为 periodSize / 2 来平衡延迟和稳定性
                    snd_pcm_sw_params_set_start_threshold(pcmHandle, swParams, 1U) // 尝试设置为1以降低延迟
                ).forEach { result ->
                    if (result < 0) {
                        println("软件参数设置错误: ${snd_strerror(result)?.toKString()}")
                    }
                }

                // 应用参数设置
                val applyResult = snd_pcm_sw_params(pcmHandle, swParams)
                if (applyResult < 0) {
                    println("[ERROR] 无法应用软件参数: ${snd_strerror(applyResult)?.toKString()}")
                }

                snd_pcm_sw_params_free(swParams)
            }

            // 准备PCM - 增强错误处理以防止Broken pipe
            err = snd_pcm_prepare(pcmHandle)
            if (err < 0) {
                println("[ERROR] 无法准备PCM: ${snd_strerror(err)?.toKString()}")
                val recoverResult = snd_pcm_recover(pcmHandle, err, 1)
                if (recoverResult < 0) {
                    println("[ERROR] PCM恢复失败: ${snd_strerror(recoverResult)?.toKString()}")
                    snd_pcm_close(pcmHandle)
                    pcmHandle = null
                    _recognitionState.value = RecognitionState.ERROR
                    return false
                }
                println("[INFO] PCM恢复成功，重新准备PCM")
                err = snd_pcm_prepare(pcmHandle)
                if (err < 0) {
                    println("[ERROR] 二次准备PCM失败: ${snd_strerror(err)?.toKString()}")
                    snd_pcm_close(pcmHandle)
                    pcmHandle = null
                    _recognitionState.value = RecognitionState.ERROR
                    return false
                }
            }

            // 启用自动恢复和缓冲区监控
            snd_pcm_nonblock(pcmHandle, 0)

            // 预填充缓冲区以避免下溢
            allocArray<ShortVar>(periodSize.toInt() * channels.toInt()).apply {
                // 将缓冲区填充为0
                val indices = 0 until (periodSize.toInt() * channels.toInt())
                // 使用原生内存操作填充缓冲区
                for (i in indices) this[i] = 0
            }

            // 最终准备状态检查
            // 增加状态转换检查
            val prepareResult = snd_pcm_prepare(pcmHandle)
            if (prepareResult < 0) {
                println("[WARNING] 最终PCM准备失败: ${snd_strerror(prepareResult)?.toKString()}")
                val currentState = snd_pcm_state(pcmHandle)
                println("[STATE] 当前PCM状态: ${getPcmStateName(currentState)}")

                // 状态恢复流程
                when (currentState) {
                    SND_PCM_STATE_OPEN, SND_PCM_STATE_SETUP -> {
                        println("[RECOVERY] 检测到异常状态($currentState)，执行硬件重初始化")
                        snd_pcm_close(pcmHandle)
                        pcmHandle = null
                        if (!initAlsa()) {
                            throw IllegalStateException("硬件重初始化失败")
                        }
                    }

                    else -> {
                        val retryResult = snd_pcm_recover(pcmHandle, prepareResult, 1)
                        if (retryResult < 0) {
                            println("[ERROR] 状态恢复失败: ${snd_strerror(retryResult)?.toKString()}")
                        }
                    }
                }

                // 二次准备检查
                val recoveredState = snd_pcm_state(pcmHandle)
                println("[RECOVERY] 恢复后PCM状态: $recoveredState")
            }

            println("[INFO] ALSA初始化完成! 缓冲区大小: $bufferSize, 周期大小: $periodSize, 周期数: $periods")
            return true
        }
    }

    /**
     * 初始化Vosk识别器
     *
     * @return 初始化是否成功
     */
    private fun initVoskRecognizer(): Boolean {
        if (voskRecognizer != null) {
            // 识别器已经初始化
            return true
        }

        if (voskModel == null) {
            if (!initVoskModel()) {
                return false
            }
        }

        // 创建识别器
        voskRecognizer = vosk_recognizer_new(voskModel, sampleRate.toFloat())
        if (voskRecognizer == null) {
            _recognitionState.value = RecognitionState.ERROR
            return false
        }

        // 配置识别器参数
        vosk_recognizer_set_max_alternatives(voskRecognizer, 1)
        vosk_recognizer_set_words(voskRecognizer, 1)  // 启用词级时间戳

        // 设置更高的灵敏度，降低识别门限
        println("[DEBUG] 设置Vosk识别器参数，提高灵敏度")

        return true
    }

    /**
     * 开始语音识别
     *
     * @return 是否成功启动识别
     */
    fun startRecognition(): Boolean {
        if (isRunning.value == 1) {
            // 已经在运行
            return true
        }
        if (!initAlsa() || !initVoskRecognizer()) {
            return false
        }
        if(pcmHandle!=null){
            val start = snd_pcm_start(pcmHandle)
            if(start<0){
                println("[ERROR] 启动PCM失败: ${snd_strerror(start)?.toKString()}")
                val currentState = snd_pcm_state(pcmHandle)
                println("[STATE] 当前PCM状态: ${getPcmStateName(currentState)}")
                snd_pcm_close(pcmHandle)
                pcmHandle = null
                _recognitionState.value = RecognitionState.ERROR
                stopRecognition(true) // 停止识别以避免进一步错误
                return false
            }else{
                println("[INFO] PCM启动成功")
                val currentState = snd_pcm_state(pcmHandle)
                println("[STATE] 当前PCM状态: ${getPcmStateName(currentState)}")
                isRunning.value = 1
                _recognitionState.value = RecognitionState.LISTENING
                // 启动识别协程
                recognitionJob = recognitionScope.launch {
                    captureAndRecognize()
                }
                return true
            }

        }
        pcmHandle = null
        _recognitionState.value = RecognitionState.ERROR
        return false
    }

    /**
     * 停止语音识别
     */
    fun stopRecognition(cleanUp: Boolean = false) {
        isRunning.value = 0
        recognitionJob?.cancel("Recognition stopped")
        recognitionJob = null
        _recognitionState.value = RecognitionState.IDLE
        println("[DEBUG] 停止语音识别")
        if (cleanUp) {
            // 清理资源
            cleanup()
        }
    }

    /**
     * 捕获音频并进行识别的主循环
     */
    private suspend fun captureAndRecognize() {
        memScoped {
            val buffer = allocArray<ShortVar>(length = (periodSize.toInt()) * (channels.toInt()))
            val processedBuffer = allocArray<ShortVar>(length = (periodSize.toInt()) * (channels.toInt()))

            // 音频处理参数
            val safeAmplificationFactor = 1.5f // 安全的音频信号放大倍数
            val noiseThreshold = 30        // 噪声阈值
            var consecutiveSilentFrames = 0  // 连续静音帧计数
            var errorCount = 0              // 错误计数器
            var lastErrorTime = 0L          // 上次错误时间
            var lastSuccessfulRead = 0L     // 上次成功读取时间
            val pcmHealthCheckInterval = 10000L // PCM健康检查间隔(毫秒)
            val maxValidAmplitude = 20000 // 根据实际情况调整

            withContext(Dispatchers.Unconfined) {
                while (isRunning.value == 1 && recognitionScope.isActive) {
                    val currentTime = Clock.System.now().toEpochMilliseconds()
                    if (currentTime - lastSuccessfulRead > pcmHealthCheckInterval) {
                        val status = snd_pcm_state(pcmHandle)
                        if (status != SND_PCM_STATE_RUNNING && status != SND_PCM_STATE_PREPARED) {
                            println("[PREVENTIVE] PCM状态异常(${getPcmStateName(status)})，执行预防性准备")
                            snd_pcm_prepare(pcmHandle)
                            delay(100)
                            continue
                        }
                    }

                    val readSize = periodSize.convert<UInt>()

                    val pcmStatusVar = snd_pcm_state(pcmHandle)
                    if (pcmStatusVar != SND_PCM_STATE_RUNNING && pcmStatusVar != SND_PCM_STATE_PREPARED) {
                        println("[PREVENTIVE] 读取前PCM状态异常(${getPcmStateName(pcmStatusVar)})，执行预防性准备")
                        val prepareResult = snd_pcm_prepare(pcmHandle)
                        if (prepareResult < 0) {
                            println("[ERROR] PCM准备失败: ${snd_strerror(prepareResult)?.toKString()}")
                            delay(100)
                            continue
                        }
                    }

                    if (isRunning.value == 0) {
                        println("[DEBUG] 识别已停止，跳过读取")
                        break
                    }
                    if(pcmHandle==null)continue

                    // 获取可用的音频帧数
                    val availFrames = snd_pcm_avail_update(pcmHandle)
                    if (availFrames > 0) {
                        val statusPtr = nativeHeap.allocPointerTo<_snd_pcm_status>()
                        val mallocErr = snd_pcm_status_malloc(statusPtr.ptr)
                        if(mallocErr < 0 || statusPtr.value == null){
                            println("[ERROR] snd_pcm_status_malloc失败: ${snd_strerror(mallocErr)?.toKString()}")
                            delay(100)
                            continue
                        }
                        val err = snd_pcm_status(pcmHandle, statusPtr.value)
                        if (err < 0) {
                            // 处理错误
                            println("[ERROR] snd_pcm_status失败: ${snd_strerror(err)?.toKString()}")
                            delay(100)
                            continue
                        }
                        val accurateAvail = snd_pcm_status_get_avail(statusPtr.value)
                        if(accurateAvail.toInt()<=0){
//                            println("[WARNING] 虽然有可用帧,可用音频帧数为负值，可能发生错误")
                            delay(100)
                            continue
                        }
                    }else{
//                        println("[WARNING] 可用音频帧数为负值，可能发生错误")
                        delay(100)
                        continue
                    }
                    // 检查可用帧数
                    if (availFrames < readSize.toLong()) {
                        // 如果可用帧数小于期望读取的帧数，等待一小段时间
                        // 这有助于避免在缓冲区未完全填满时读取，减少XRUN的可能性
                        // 等待时间可以根据采样率和周期大小调整，例如一个周期的持续时间
                        val waitTime = (periodSize * 1000 / sampleRate).toLong().coerceIn(10, 50) // 计算周期时间(ms)，限制在10-50ms
                        delay(waitTime)
                        continue
                    }

                    // 读取音频数据，返回的frames为帧数
                    // 读取实际可用的帧数，但不超过缓冲区大小（readSize）
                    val framesToRead = availFrames.toUInt().coerceAtMost(readSize)
                    val frames = snd_pcm_readi(pcmHandle, buffer, framesToRead)

                    if (frames < 0) {
                        val err = frames.toInt()
                        val (errName, errDesc) = getAlsaErrorDescription(err)
                        println("[ALSA-READ-ERROR] snd_pcm_readi 失败 | 代码: $err ($errName) | 描述: $errDesc | 当前状态: ${getPcmStateName(snd_pcm_state(pcmHandle))}")
                        handlePcmError(err) // 尝试恢复
                        val delayMillis = if (err == -EPIPE) brokenPipeRetryDelay else errorRecoveryDelay
                        delay(delayMillis)
                        errorCount++
                        lastErrorTime = Clock.System.now().toEpochMilliseconds()
                        continue
                    } else if (frames == 0) {
                        // 读取到0帧通常不是错误，只是表示当前没有数据可读
                        // println("[DEBUG] snd_pcm_readi 返回 0 帧")
                        delay(50) // 短暂等待
                        continue
                    } else if (frames.toUInt() < framesToRead) {
                        // 读取到的帧数少于请求的帧数，这可能预示着XRUN即将发生或已经发生
                        println("[WARNING] 读取到的帧数 ($frames) 少于请求的帧数 ($framesToRead)，可能发生XRUN")
                        // 可以在这里添加额外的检查或恢复逻辑
                    }

                    // 如果读取成功，重置错误计数器并记录成功时间
                    errorCount = 0
                    lastSuccessfulRead = Clock.System.now().toEpochMilliseconds()

                    errorCount = 0
                    lastSuccessfulRead = Clock.System.now().toEpochMilliseconds()

                    // --- 音频处理（基于帧数×通道数处理每个样本） ---
                    var maxAmplitude = 0
                    var sumAmplitude = 0L
                    var hasSpeech = false
                    val sampleCount = frames * channels

                    // 检查每个样本的振幅，判断是否有语音
                    for (i in 0 until sampleCount) {
                        val originalAmplitude = buffer[i].convert<Int>()
                        val amplitude = originalAmplitude.absoluteValue
                        maxAmplitude = maxOf(maxAmplitude, amplitude)
                        sumAmplitude += amplitude
                        if (amplitude > noiseThreshold) hasSpeech = true
                    }
                    if (!hasSpeech) {
                        continue
                    }

                    var invalidSamples = 0
                    // 对每个样本进行处理：放大、平滑和降噪
                    for (i in 0 until sampleCount) {
                        val originalAmplitude = buffer[i].convert<Int>()
                        val amplitude = originalAmplitude.absoluteValue
                        var processedValue = originalAmplitude
                        if (amplitude > noiseThreshold) {
                            val smoothFactor = 0.8f
                            val amplifiedValue = originalAmplitude.toFloat() * safeAmplificationFactor
                            processedValue = (amplifiedValue * smoothFactor + originalAmplitude * (1 - smoothFactor)).toInt()
                        } else {
                            processedValue = (originalAmplitude.toFloat() * 0.2f).toInt()
                        }
                        if (processedValue > Short.MAX_VALUE || processedValue < Short.MIN_VALUE) {
                            invalidSamples++
                            processedBuffer[i] = 0
                        } else {
                            processedBuffer[i] = processedValue.toShort()
                        }
                    }
                    if (invalidSamples > 0) {
                        println("[WARNING] 检测到 $invalidSamples 个无效音频样本，已修复")
                    }

                    val avgAmplitude = if (sampleCount > 0) sumAmplitude / sampleCount else 0
                    println("[AUDIO-DEBUG] 音频帧振幅范围: 0-$maxAmplitude")
                    if (maxAmplitude < 100) {
                        println("[WARNING] 检测到无效音频输入（可能静音或麦克风故障）")
                    }
                    if (maxAmplitude > maxValidAmplitude || avgAmplitude > maxValidAmplitude / 2) {
                        println("[WARNING] 检测到异常音频幅度，跳过此帧 maxAmplitude:$maxAmplitude " +
                                "maxValidAmplitude:$maxValidAmplitude " +
                                "avgAmplitude:$avgAmplitude")
                        continue
                    }

                    if (frames % 5 == 0) {
//                        println("[DEBUG] 音频数据: 帧数=$frames, 最大振幅=$maxAmplitude, 平均振幅=$avgAmplitude, 检测到语音=${if (hasSpeech) "是" else "否"}")
                        // 麦克风状态检测和自动调整
                        if (maxAmplitude < 20) {
                            consecutiveSilentFrames++
                            if (consecutiveSilentFrames >= 8) {
                                println("[WARNING] 检测到的声音振幅较低 ($maxAmplitude)，可能存在以下问题:")
//                                println("[WARNING] 1. 麦克风未正确连接或被静音")
//                                println("[WARNING] 2. 麦克风音量设置过低 (当前: ${micVolume}%)")
//                                println("[WARNING] 3. 环境过于安静或麦克风距离声源太远")
//                                println("[WARNING] 4. 麦克风硬件故障")
                                if (micVolume < 90) {
                                    val newVolume = (micVolume + 10).coerceAtMost(100)
                                    println("[AUTO-FIX] 尝试自动增加麦克风音量: ${micVolume}% -> ${newVolume}%")
                                    setMicrophoneVolume(newVolume)
                                }
                                consecutiveSilentFrames = 0
                            }
                        } else {
                            consecutiveSilentFrames = 0
                            if (maxAmplitude > 2000 && frames % 20 == 0) {
                                println("[INFO] 麦克风工作正常，检测到良好的音频信号 (振幅: $maxAmplitude)")
                            }
                        }
                    }

                    // 将处理后的数据传递给Vosk识别器，注意 length 为样本总数，即 frames * channels
                    try {
                        if (voskRecognizer != null) {
                            val result = try {
                                vosk_recognizer_accept_waveform_s(
                                    recognizer = voskRecognizer,
                                    data = if (hasSpeech) processedBuffer else buffer, // 如果检测到语音，使用处理后的数据
                                    length = (sampleCount).convert()
                                )
                            } catch (e: Exception) {
                                println("[ERROR] Vosk识别器处理音频时出错: ${e.message}")
                                println("[RECOVERY] 重置Vosk识别器")
                                vosk_recognizer_reset(voskRecognizer)
                                -1
                            }

                            val pcmStatusAfter = snd_pcm_state(pcmHandle)
                            if (pcmStatusAfter != SND_PCM_STATE_RUNNING && pcmStatusAfter != SND_PCM_STATE_PREPARED) {
                                println("[WARNING] PCM状态异常(${getPcmStateName(pcmStatusAfter)})，执行预防性准备")
                                snd_pcm_prepare(pcmHandle)
                            }
                            when (result) {
                                0 -> {
                                    try {
                                        val partialJson = vosk_recognizer_partial_result(voskRecognizer)?.toKString()
                                        if (!partialJson.isNullOrEmpty()) {
                                            processPartialResult(partialJson)
                                        }
                                    } catch (e: Exception) {
                                        println("[WARNING] 获取部分结果时出错: ${e.message}")
                                    }
                                }
                                1 -> {
                                    try {
                                        val finalJson = vosk_recognizer_result(voskRecognizer)?.toKString()
                                        if (!finalJson.isNullOrEmpty()) {
                                            processFinalResult(finalJson)
                                        }
                                    } catch (e: Exception) {
                                        println("[WARNING] 获取最终结果时出错: ${e.message}")
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        println("[WARNING] 处理音频数据时出错: ${e.message}")
                        val pcmStatusAfter = snd_pcm_state(pcmHandle)
                        if (pcmStatusAfter != SND_PCM_STATE_RUNNING && pcmStatusAfter != SND_PCM_STATE_PREPARED) {
                            snd_pcm_prepare(pcmHandle)
                        }
                    }
                }
            }
        }
    }
    /**
     * 处理最终识别结果
     *
     * @param jsonResult JSON格式的最终识别结果
     */
    private fun processFinalResult(jsonResult: String) {
        println("[DEBUG] processFinalResult: $jsonResult")
        try {
            val jsonObject = Json.parseToJsonElement(jsonResult).jsonObject
            val text = jsonObject["text"]?.jsonPrimitive?.content ?: ""
            // 解析单词列表和时间戳（如果有）
            val wordsList = mutableListOf<RecognizedWord>()
            jsonObject["result"]?.jsonObject?.let { resultObj ->
                // 处理单词级别的结果
            }

            if (text.isNotBlank()) {
                println("[DEBUG] 识别结果: $text")
                _recognitionResult.value = RecognitionResult(
                    text = text,
                    isPartial = false,
                    confidence = 1.0f,  // Vosk目前不提供整体置信度
                    words = wordsList
                )

                // 识别完成后重置状态
                _recognitionState.value = RecognitionState.IDLE
            }
        } catch (e: Exception) {
            // 解析错误处理
        }
    }

    /**
     * 初始化ALSA混音器
     *
     * @return 初始化是否成功
     */
    private fun initMixer(): Boolean {
        if (mixerHandle != null) {
            // 混音器已经初始化
            return true
        }

        println("[DEBUG] 初始化ALSA混音器，设置麦克风音量: $micVolume%")

        memScoped {
            val mixerHandlePtr = nativeHeap.allocPointerTo<_snd_mixer>()

            // 打开混音器
            var err = snd_mixer_open(mixerHandlePtr.ptr, 0)
            if (err < 0) {
                println("无法打开混音器: ${snd_strerror(err)?.toKString()}")
                return false
            }

            val tMixerHandle = mixerHandlePtr.value ?: error("分配失败")
            mixerHandle = tMixerHandle

            // 附加到设备
            err = snd_mixer_attach(mixerHandle, deviceName)
            if (err < 0) {
                println("无法附加混音器到设备: ${snd_strerror(err)?.toKString()}")
                snd_mixer_close(mixerHandle)
                mixerHandle = null
                return false
            }

            // 注册混音器
            err = snd_mixer_selem_register(mixerHandle, null, null)
            if (err < 0) {
                println("无法注册混音器: ${snd_strerror(err)?.toKString()}")
                snd_mixer_close(mixerHandle)
                mixerHandle = null
                return false
            }

            // 加载混音器
            err = snd_mixer_load(mixerHandle)
            if (err < 0) {
                println("无法加载混音器: ${snd_strerror(err)?.toKString()}")
                snd_mixer_close(mixerHandle)
                mixerHandle = null
                return false
            }

            // 设置麦克风音量
            setMicrophoneVolume(micVolume)

            return true
        }
    }
    /**
     * 处理PCM错误，尝试恢复设备
     *
     * @param err snd_pcm_readi返回的错误码
     */
    private fun handlePcmError(err: Int) {
        val (errName, errMsg) = getAlsaErrorDescription(err)
        println("[RECOVERY] 检测到PCM错误: $err ($errName / $errMsg)，尝试恢复...")

        val recoverResult = snd_pcm_recover(
            pcm = pcmHandle,
            err = err,
            silent = 0 /* 0 = enable verbose messages */)
        if (recoverResult == 0) {
            println("[INFO] PCM恢复成功")
            // 特别针对 EPIPE 错误，在恢复后尝试准备 PCM
            if (err == -EPIPE) {
                println("[RECOVERY] EPIPE 错误后，尝试显式准备 PCM...")
                val prepareResult = snd_pcm_prepare(pcmHandle)
                if (prepareResult < 0) {
                    println("[ERROR] EPIPE 恢复后 PCM 准备失败: ${snd_strerror(prepareResult)?.toKString()}")
                } else {
                    println("[RECOVERY] EPIPE 恢复后 PCM 准备成功")
                    val start = snd_pcm_start(pcmHandle)
                    if(start<0) {
                        println("[ERROR] 启动PCM失败: ${snd_strerror(start)?.toKString()}")
                        val currentState = snd_pcm_state(pcmHandle)
                        println("[STATE] 当前PCM状态: ${getPcmStateName(currentState)}")
                        snd_pcm_close(pcmHandle)
                        pcmHandle = null
                        _recognitionState.value = RecognitionState.ERROR
                        stopRecognition() // 停止识别以避免进一步错误
                    }else{
                        println("[RECOVERY] PCM启动成功")
                        val currentState = snd_pcm_state(pcmHandle)
                        println("[STATE] 当前PCM状态: ${getPcmStateName(currentState)}")
                    }
                }
            }
        } else {
            println("[ERROR] PCM恢复失败: ${snd_strerror(recoverResult)?.toKString()}")
            // 可以在这里添加更复杂的错误处理逻辑，例如尝试重新初始化ALSA
            _recognitionState.value = RecognitionState.ERROR
            stopRecognition() // 停止识别以避免进一步错误
        }
    }
    /**
     * 设置麦克风音量
     *
     * @param volume 音量值 (0-100)
     * @return 设置是否成功
     */
    fun setMicrophoneVolume(volume: Int): Boolean {
        val safeVolume = volume.coerceIn(0, 100)
        this.micVolume = safeVolume

        if (mixerHandle == null) {
            println("[INFO] 混音器未初始化，尝试初始化...")
            if (!initMixer()) {
                println("[ERROR] 无法初始化混音器，麦克风音量设置失败")
                return false
            }
        }

        println("[DEBUG] 设置麦克风音量: $safeVolume%")

        memScoped {
            // 尝试多个可能的混音器元素名称
            listOf("Capture", "Mic", "Microphone", "Input Source", "Digital")
            var foundCapture = false
            var elemCount = 0

            // 查找捕获元素
            var elem: CPointer<_snd_mixer_elem>? = snd_mixer_first_elem(mixerHandle)
            while (elem != null) {
                elemCount++
                val elemName = snd_mixer_selem_get_name(elem)?.toKString() ?: "未知"

                // 检查是否为捕获元素
                if (snd_mixer_selem_has_capture_volume(elem) != 0) {
                    foundCapture = true
                    println("[DEBUG] 找到麦克风控制: $elemName")

                    // 获取音量范围
                    val minPtr = nativeHeap.alloc<IntVar>()
                    val maxPtr = nativeHeap.alloc<IntVar>()

                    snd_mixer_selem_get_capture_volume_range(elem, minPtr.ptr, maxPtr.ptr)
                    val min = minPtr.value
                    val max = maxPtr.value

                    // 计算音量值
                    val volumeValue = min + ((max - min) * safeVolume) / 100
                    println("[DEBUG] 设置音量: $volumeValue (范围: $min-$max)")

                    // 设置所有通道的音量
                    val result = snd_mixer_selem_set_capture_volume_all(elem, volumeValue)
                    if (result < 0) {
                        println("[WARNING] 设置音量失败: ${snd_strerror(result)?.toKString()}")
                    } else {
                        // 检查是否成功设置
                        val currentPtr = nativeHeap.alloc<IntVar>()
                        snd_mixer_selem_get_capture_volume(
                            elem,
                            SND_MIXER_SCHN_FRONT_LEFT,
                            currentPtr.ptr
                        )
                        val current = currentPtr.value
                        println("[DEBUG] 当前音量值: $current (目标: $volumeValue)")

                        // 确保麦克风未静音
                        if (snd_mixer_selem_has_capture_switch(elem) != 0) {
                            snd_mixer_selem_set_capture_switch_all(elem, 1) // 1 = 未静音
                        }
                    }

                    return true
                }
                elem = snd_mixer_elem_next(elem)
            }

            if (!foundCapture) {
                println("[WARNING] 未找到麦克风音量控制 (检查了 $elemCount 个混音器元素)")
                println("[INFO] 尝试重新初始化混音器...")

                // 尝试重新初始化混音器
                snd_mixer_close(mixerHandle)
                mixerHandle = null
                if (initMixer()) {
                    // 递归调用，但只尝试一次，避免无限循环
                    return setMicrophoneVolume(safeVolume)
                }
            }

            return false
        }
    }

    /**
     * 处理部分识别结果
     *
     * @param jsonResult JSON格式的部分识别结果
     */
    private fun processPartialResult(jsonResult: String) {
        try {
            val jsonObject = Json.parseToJsonElement(jsonResult).jsonObject
            val partial = jsonObject["partial"]?.jsonPrimitive?.content
            if (!partial.isNullOrBlank()) {
                println("[DEBUG] processPartialResult: $partial")
                _recognitionResult.value = RecognitionResult(
                    text = partial,
                    isPartial = true,
                    confidence = 0.0f,
                    words = emptyList()
                )
            }
        } catch (e: Exception) {
            // 解析错误处理
        }
    }

    /**
     * 将ALSA PCM状态码转换为可读字符串
     *
     * @param state snd_pcm_state() 返回的状态码
     * @return 状态的字符串表示
     */
    private fun getPcmStateName(state: _snd_pcm_state): String {
        return when (state) {
            SND_PCM_STATE_OPEN -> "OPEN"
            SND_PCM_STATE_SETUP -> "SETUP"
            SND_PCM_STATE_PREPARED -> "PREPARED"
            SND_PCM_STATE_RUNNING -> "RUNNING"
            SND_PCM_STATE_XRUN -> "XRUN (underrun/overrun)"
            SND_PCM_STATE_DRAINING -> "DRAINING"
            SND_PCM_STATE_PAUSED -> "PAUSED"
            SND_PCM_STATE_SUSPENDED -> "SUSPENDED"
            SND_PCM_STATE_DISCONNECTED -> "DISCONNECTED"
            else -> "UNKNOWN_STATE ($state)"
        }
    }

    /**
     * 获取当前麦克风音量
     *
     * @return 当前音量值 (0-100)
     */
    fun getMicrophoneVolume(): Int {
        return micVolume
    }

    /**
     * 清理资源
     */
    private fun cleanup() {
        // 清理Vosk资源
        if (voskRecognizer != null) {
            vosk_recognizer_free(voskRecognizer)
            voskRecognizer = null
        }

        // 清理ALSA资源
        if (pcmHandle != null) {
            snd_pcm_drain(pcmHandle)
            snd_pcm_close(pcmHandle)
            pcmHandle = null
        }

        // 清理混音器资源
        if (mixerHandle != null) {
            snd_mixer_close(mixerHandle)
            mixerHandle = null
        }
    }

    /**
     * 释放所有资源
     */
    fun release() {
        stopRecognition(true)
        recognitionScope.cancel("Recognizer released")

        // 释放Vosk模型
        if (voskModel != null) {
            vosk_model_free(voskModel)
            voskModel = null
        }
    }

    /**
     * 语音识别状态
     */
    enum class RecognitionState {
        IDLE,        // 空闲状态
        LISTENING,   // 正在监听
        PROCESSING,  // 正在处理
        ERROR        // 错误状态
    }

    /**
     * 语音识别结果数据类
     */
    data class RecognitionResult(
        val text: String,              // 识别的文本
        val isPartial: Boolean,        // 是否为部分结果
        val confidence: Float,         // 置信度
        val words: List<RecognizedWord> // 单词级别的结果
    )

    /**
     * 识别的单词数据类
     */
    data class RecognizedWord(
        val word: String,     // 单词
        val startTime: Float, // 开始时间（秒）
        val endTime: Float,   // 结束时间（秒）
        val confidence: Float // 置信度
    )
}