@file:OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)

package voice.core.service

import com.airobot.core.utils.thread.getThreadName
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.free
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock.System
import voice.acquisition.portaudio.PortAudioDevice
import voice.api.VoiceAssistantApi
import voice.core.config.VoiceAssistantConfig
import voice.detector.keyword.KeywordDetector
import voice.synthesis.PiperSpeechSynthesizer
import voice.util.LogManager
import kotlin.time.ExperimentalTime

/**
 * Vosk和Piper集成的语音助手
 */
class VoiceAssistant(
    private val config: VoiceAssistantConfig
) : VoiceAssistantApi {
    // 日志
    private val logger = LogManager.getLogger("VoiceAssistant")

    // 组件
    private val speechSynthesizer = PiperSpeechSynthesizer()
    private val audioDevice = PortAudioDevice.getInstance() // 使用全局单例
    private val keywordDetector = KeywordDetector()

    // 状态管理
    private val _assistantState = MutableStateFlow(VoiceAssistantApi.AssistantState.IDLE)
    override val assistantState: StateFlow<VoiceAssistantApi.AssistantState> =
        _assistantState.asStateFlow()

    // 识别结果
    private val _recognizedText = MutableStateFlow<String?>(null)
    override val recognizedText: StateFlow<String?> = _recognizedText.asStateFlow()

    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Default)
    private var assistantJob: Job? = null

    // 标记是否运行
    private var isRunning = false
    
    // 关键词检测回调
    var onKeywordDetectedCallback: ((String) -> Unit)? = null

    init {
        logger.info("VoiceAssistant 实例化")
    }

    /**
     * 初始化语音助手
     * @return 初始化是否成功
     */
    override suspend fun initialize(): Boolean {
        logger.info("VoiceAssistant.initialize() 被调用")
        _assistantState.value = VoiceAssistantApi.AssistantState.INITIALIZING

        try {
            // 初始化关键词检测器
            logger.info("initKeywordDetector 被调用")
            if (!initKeywordDetector()) {
                logger.error("初始化关键词检测器失败")
                return false
            }

            // 初始化音频设备（只初始化一次）
            logger.info("initAudioDevice 被调用")
            if (!initAudioDevice()) {
                logger.error("初始化音频设备失败")
                return false
            }

            // 初始化语音合成
            logger.info("initSpeechSynthesizer 被调用")
            if (!initSpeechSynthesizer()) {
                logger.warn("初始化语音合成器失败，语音助手将不能语音应答")
                // 继续初始化，因为语音合成不是必需的
            }

            logger.info("语音助手初始化成功")
            _assistantState.value = VoiceAssistantApi.AssistantState.IDLE
            return true
        } catch (e: Exception) {
            logger.error("语音助手初始化异常: ${e.message}")
            e.printStackTrace()
            _assistantState.value = VoiceAssistantApi.AssistantState.ERROR
            return false
        }
    }

    /**
     * 初始化关键词检测器
     */
    private fun initKeywordDetector(): Boolean {
        // 新版KeywordDetector
        return keywordDetector.initialize(
            modelPath = config.voskModelPath,
            sensitivity = config.sensitivity
        )
    }

    /**
     * 初始化音频设备
     */
    private fun initAudioDevice(): Boolean {
        return audioDevice.initialize(
            deviceName = "default",
            sampleRate = config.sampleRate
        )
    }

    /**
     * 初始化语音合成器
     */
    private fun initSpeechSynthesizer(): Boolean {
        return speechSynthesizer.initialize(
            modelPath = config.piperModelPath,
            configPath = config.piperConfigPath,
            espeakDataPath = config.piperESpeakDataPath,
            speakerId = 0
        )
    }

    /**
     * 启动语音助手
     */
    override suspend fun start(): Boolean {
        logger.info("VoiceAssistant.start() 被调用")
        
        try {
            if (isRunning) {
                logger.warn("语音助手已经在运行中")
                return true
            }

            // 启动音频设备
            logger.info("准备调用 audioDevice.start() ...")
            if (!audioDevice.start()) {
                logger.error("无法启动音频设备")
                _assistantState.value = VoiceAssistantApi.AssistantState.ERROR
                return false
            }
            logger.info("⭐⭐⭐ 音频设备start()调用成功，继续执行...")
            
            // 添加延迟确保设备状态已稳定
            logger.info("延迟300ms确保音频设备状态稳定...")
            delay(300)
            logger.info("延迟结束，当前设备状态: ${audioDevice.deviceState.value}")
            
            // 启动关键词监听
            logger.info("⭐⭐⭐ 准备启动关键词监听...")
            try {
                logger.info("调用 keywordDetector.startListening() 开始...")
                val result = keywordDetector.startListening()
                logger.info("⭐⭐⭐ keywordDetector.startListening() 返回结果: $result")
                if (!result) {
                    logger.error("无法启动关键词监听")
                    audioDevice.stop()
                    _assistantState.value = VoiceAssistantApi.AssistantState.ERROR
                    return false
                }
                logger.info("关键词监听启动成功")
            } catch (e: Exception) {
                logger.error("启动关键词监听时发生异常: ${e.message}")
                e.printStackTrace()
                audioDevice.stop()
                _assistantState.value = VoiceAssistantApi.AssistantState.ERROR
                return false
            }

            // 启动助手任务
            try {
                logger.info("⭐⭐⭐ 准备启动助手主循环协程...")
                assistantJob?.cancel()
                val deferred = CompletableDeferred<Boolean>()
                assistantJob = scope.launch {
                    logger.info("主循环协程已启动")
                    try {
                        withContext(Dispatchers.Unconfined) {
                            try {
                                logger.info("⭐⭐⭐ 设置状态为LISTENING_KEYWORD")
                                _assistantState.value = VoiceAssistantApi.AssistantState.LISTENING_KEYWORD
                                isRunning = true

                                // 让 KeywordDetector / PortAudioAcquisition 自行打开输入流，避免在这里阻塞
                                logger.info("⚠️ 跳过 VoiceAssistant 内部打开输入流，交由 KeywordDetector 处理")

                                // 流打开后稍等片刻让系统稳定
                                logger.info("延迟500ms让音频流稳定...")
                                delay(500)
                                logger.info("延迟结束，准备进入主循环")

                                // 确认状态更新
                                logger.info("⭐⭐⭐ 语音助手状态: ${_assistantState.value}")
                                logger.info("⭐⭐⭐ 开始监听关键词...")
                                
                                // 主循环 - 监听唤醒词
                                logger.info("⭐⭐⭐ 已开始监听关键词，等待唤醒...")

                                // 音频帧读取缓冲区 - 分配一次重复使用
                                val frameSize = 1024
                                logger.info("准备分配音频缓冲区...")
                                val buffer = nativeHeap.allocArray<ShortVar>(frameSize)
                                val audioData = ShortArray(frameSize) // 预分配，避免频繁创建对象
                                logger.info("音频缓冲区分配完成")

                                logger.info("⭐⭐⭐ 进入主检测循环...")
                                var frameCounter = 0
                                while (isActive && isRunning) {
                                    try {
                                        frameCounter++
                                        if (frameCounter == 1 || frameCounter % 100 == 0) {
                                            logger.info("主循环迭代次数: $frameCounter")
                                        }
                                        
                                        // 从音频设备读取数据 - 使用 suspend 版本
                                        val framesRead = audioDevice.readAudioSuspend(buffer, frameSize)
                                        
                                        // 偶尔记录一次读取状态，避免日志过多
                                        if (frameCounter == 1 || frameCounter % 100 == 0) {
                                            logger.info("主循环读取音频: $framesRead 帧")
                                        }

                                        // 如果没有读取到数据，短暂休眠避免CPU空转
                                        if (framesRead <= 0) {
                                            if (frameCounter % 50 == 0) {
                                                logger.warn("没有读取到音频数据 ($framesRead)")
                                            }
                                            delay(5) // 5ms短延迟
                                            continue
                                        }

                                        // 复制数据以便处理
                                        for (i in 0 until framesRead) {
                                            audioData[i] = buffer[i]
                                        }

                                        // 关键词检测
                                        if (frameCounter == 1 || frameCounter % 100 == 0) {
                                            logger.info("准备调用keywordDetector.detect进行检测...")
                                        }
                                        val detected = keywordDetector.detect(audioData.copyOfRange(0, framesRead))
                                        
                                        // 如果检测到关键词
                                        if (detected) {
                                            logger.info("检测到关键词!")
                                            
                                            // 进入对话状态
                                            onKeywordDetected()
                                        }
                                    } catch (e: CancellationException) {
                                        // 协程被取消，正常退出
                                        logger.info("语音助手协程被取消")
                                        break
                                    } catch (e: Exception) {
                                        // 捕获并记录其他异常，但不中断循环
                                        logger.error("监听唤醒词时发生异常: ${e.message}")
                                        e.printStackTrace()
                                        // 短暂延迟后继续
                                        delay(500)
                                    }
                                }
                                
                                // 清理资源
                                logger.info("主循环结束，释放资源")
                                nativeHeap.free(buffer)
                                deferred.complete(true)
                            } catch (e: Exception) {
                                logger.error("协程内 withContext 块发生异常: ${e.message}")
                                e.printStackTrace()
                                deferred.complete(false)
                            }
                        }
                    } catch (e: Exception) {
                        logger.error("语音助手主循环发生异常: ${e.message}")
                        e.printStackTrace()
                        _assistantState.value = VoiceAssistantApi.AssistantState.ERROR
                        deferred.complete(false)
                    } finally {
                        // 确保清理资源
                        logger.info("主循环finally块: 清理资源")
                        isRunning = false
                        _assistantState.value = VoiceAssistantApi.AssistantState.IDLE
                    }
                }
                logger.info("⭐⭐⭐ 助手主循环协程已创建，等待协程完成初始化")
                
                // 等待足够时间让主循环启动
                logger.info("延迟1000ms等待主循环启动...")
                delay(1000)
                logger.info("延迟结束，检查助手状态")
                
                // 检查状态并返回结果
                logger.info("⭐⭐⭐ 当前助手状态: ${_assistantState.value}")
                if (_assistantState.value == VoiceAssistantApi.AssistantState.ERROR) {
                    logger.error("启动过程中出现错误，助手状态为ERROR")
                    return false
                }
                
                logger.info("⭐⭐⭐ 语音助手启动完成，状态: ${_assistantState.value}")
                return true
            } catch (e: Exception) {
                logger.error("创建助手主循环协程时发生异常: ${e.message}")
                e.printStackTrace()
                audioDevice.stop()
                keywordDetector.stopListening()
                _assistantState.value = VoiceAssistantApi.AssistantState.ERROR
                return false
            }
        } catch (e: Throwable) {
            logger.error("VoiceAssistant.start() 发生严重错误: ${e::class.simpleName}: ${e.message}")
            e.printStackTrace()
            _assistantState.value = VoiceAssistantApi.AssistantState.ERROR
            return false
        }
    }

    /**
     * 生成诊断报告
     * 整合所有组件的诊断信息
     */
    fun generateDiagnostics(): String {
        val sb = StringBuilder()
        sb.appendLine("========== 语音助手诊断报告 ==========")
        sb.appendLine("助手状态: ${assistantState.value}")
        
        // 添加关键词检测器状态
        sb.appendLine("\n-- 关键词检测器状态 --")
        sb.appendLine(keywordDetector.generateDiagnostics())
        
        // 添加音频设备状态
        sb.appendLine("\n-- 音频设备状态 --")
        sb.appendLine("设备状态: ${audioDevice.deviceState.value}")
        
        return sb.toString()
    }

    /**
     * 播放激活提示音
     */
    private suspend fun playActivationSound() {
        logger.info("播放激活提示音")

        // 检查输入流状态
        val inputStreamActive = PortAudioDevice.isInputStreamActive()
        
        if (inputStreamActive) {
            // 输入流活动，使用系统命令播放嘟嘟声
            val beepCmd = "echo -e \"\\007\\007\" > /dev/console || echo 'beep' > /dev/null"
            try {
                platform.posix.system(beepCmd)
                logger.debug("系统命令播放激活提示音完成")
            } catch (e: Exception) {
                logger.error("使用系统命令播放激活提示音失败: ${e.message}")
            }
            return
        }

        // 如果输入流不活跃，尝试使用PortAudio播放
        // 创建一个简单的哔声
        val beepDuration = 200 // 毫秒
        val sampleRate = config.sampleRate
        val toneFrequency = 880.0 // Hz, A5音

        // 计算需要的样本数
        val numSamples = (beepDuration * sampleRate / 1000)

        // 创建立体声哔声
        val beep = ShortArray(numSamples * 2)

        // 生成哔声（正弦波）- 立体声格式
        for (i in 0 until numSamples) {
            val time = i.toDouble() / sampleRate
            val amplitude = 0.5 // 振幅为最大的50%
            val sampleValue =
                (Short.MAX_VALUE * amplitude * kotlin.math.sin(2.0 * kotlin.math.PI * toneFrequency * time)).toInt()
                    .toShort()

            // 左右声道
            beep[i * 2] = sampleValue
            beep[i * 2 + 1] = sampleValue
        }

        // 播放提示音
        try {
            audioDevice.playAudio(beep)
            logger.debug("提示音播放完成")
        } catch (e: Exception) {
            logger.error("播放提示音失败: ${e.message}")
        }
    }

    /**
     * 停止语音助手
     */
    override suspend fun stop() {
        logger.info("停止语音助手")
        assistantJob?.cancel()
        assistantJob = null

        keywordDetector.stopListening()
        audioDevice.stop()

        _assistantState.value = VoiceAssistantApi.AssistantState.IDLE
    }

    /**
     * 提交文本命令
     * @param text 文本命令
     * @return 回复内容
     */
    override suspend fun submitTextCommand(text: String): String {
        logger.info("收到文本命令: $text")
        _assistantState.value = VoiceAssistantApi.AssistantState.PROCESSING_COMMAND

        // 简单的命令处理
        val response = when {
            text.contains("你好") -> "你好，我是小样，有什么可以帮助你的吗？"
            text.contains("时间") -> "现在时间是${System.now()}"
            text.contains("名字") -> "我的名字是小样，是一个AI语音助手"
            else -> "抱歉，我还不理解这个命令"
        }

        // 朗读回复
        speak(response)

        return response
    }

    /**
     * 朗读文本
     * @param text 要朗读的文本
     * @return 播放是否成功
     */
    override suspend fun speak(text: String): Boolean {
        logger.info("朗读文本: $text")

        _assistantState.value = VoiceAssistantApi.AssistantState.SPEAKING

        try {
            // 检查输入流状态
            val inputStreamActive = PortAudioDevice.isInputStreamActive()
            
            if (inputStreamActive) {
                // 输入流活动时，直接使用系统命令合成和播放
                logger.info("检测到输入流活动，使用系统命令播放音频...")
                
                // 使用系统命令合成并直接播放
                val cmd = "echo \"$text\" | piper --model ${config.piperModelPath} --config ${config.piperConfigPath} --output-raw | aplay -f S16_LE -r 16000 -c 1 -D hw:0,0 2>/dev/null"
                try {
                    val result = platform.posix.system(cmd)
                    logger.info("使用系统命令合成并播放完成，结果: $result")
                    
                    _assistantState.value = VoiceAssistantApi.AssistantState.LISTENING_KEYWORD
                    return result == 0
                } catch (e: Exception) {
                    logger.error("使用系统命令合成播放失败: ${e.message}")
                    _assistantState.value = VoiceAssistantApi.AssistantState.LISTENING_KEYWORD
                    return false
                }
            } else {
                // 仅在输入流不活动时才尝试使用PortAudio播放
                // 使用额外尝试次数增加合成成功率
                var success = false
                var attempts = 0
                val maxAttempts = 3

                while (!success && attempts < maxAttempts) {
                    attempts++
                    
                    // 再次检查输入流状态
                    if (PortAudioDevice.isInputStreamActive()) {
                        logger.warn("尝试播放时检测到输入流已变为活动状态，取消播放")
                        break
                    }
                    
                    // 尝试检查输出流
                    val outputStreamActive = PortAudioDevice.isOutputStreamActive()
                    
                    if (!outputStreamActive) {
                        // 尝试打开输出流
                        val outputStreamOpened = audioDevice.openOutputStream(
                            deviceIndex = -1,
                            sampleRate = config.sampleRate,
                            channels = 2
                        )
                        
                        if (!outputStreamOpened) {
                            logger.warn("无法打开输出流（尝试 #$attempts），等待后重试")
                            delay(500) // 等待一段时间再重试
                            continue
                        }
                    }
                    
                    // 合成并播放
                    try {
                        success = speechSynthesizer.speak(text)
                        if (!success) {
                            logger.warn("语音合成失败（尝试 #$attempts），等待后重试")
                            delay(500) // 等待一段时间再重试
                        }
                    } catch (e: Exception) {
                        logger.error("语音合成错误（尝试 #$attempts）: ${e.message}")
                        delay(500) // 等待一段时间再重试
                    }
                }

                if (!success) {
                    logger.error("多次尝试后语音合成仍然失败")
                }
                
                _assistantState.value = VoiceAssistantApi.AssistantState.LISTENING_KEYWORD
                return success
            }
        } catch (e: Exception) {
            logger.error("语音合成过程中发生异常: ${e.message}")
            _assistantState.value = VoiceAssistantApi.AssistantState.LISTENING_KEYWORD
            return false
        }
    }

    /**
     * 释放资源
     */
    override suspend fun release() {
        logger.info("释放语音助手资源")
        stop()
        
        try {
            keywordDetector.release()
            speechSynthesizer.release()
            // 不释放全局的audioDevice，它可能被其他组件使用
        } catch (e: Exception) {
            logger.error("释放资源时发生异常: ${e.message}")
        }
    }

    /**
     * 播放应答提示音
     */
    private suspend fun playAcknowledgeTone() {
        logger.info("播放应答提示音")

        // 检查输入流状态
        val inputStreamActive = PortAudioDevice.isInputStreamActive()
        
        if (inputStreamActive) {
            // 输入流活动，使用系统命令播放嘟嘟声
            val beepCmd = "echo -e \"\\007\" > /dev/console || echo 'beep' > /dev/null"
            try {
                platform.posix.system(beepCmd)
                logger.debug("系统命令播放应答提示音完成")
            } catch (e: Exception) {
                logger.error("使用系统命令播放应答提示音失败: ${e.message}")
            }
            return
        }

        // 如果输入流不活跃，尝试使用PortAudio播放
        // 创建一个简单的哔声
        val beepDuration = 200 // 毫秒
        val sampleRate = config.sampleRate
        val toneFrequency = 980.0 // Hz, B5音，比激活音高一些

        // 计算需要的样本数
        val numSamples = (beepDuration * sampleRate / 1000)

        // 创建立体声哔声
        val beep = ShortArray(numSamples * 2)

        // 生成哔声（正弦波），增加音量淡入淡出效果
        for (i in 0 until numSamples) {
            val time = i.toDouble() / sampleRate
            // 计算淡入淡出的振幅包络
            val fadeTime = 0.2 // 淡入淡出时间占比
            val normalizedPos = i.toDouble() / numSamples
            val envelope = when {
                normalizedPos < fadeTime -> normalizedPos / fadeTime // 淡入
                normalizedPos > (1.0 - fadeTime) -> (1.0 - normalizedPos) / fadeTime // 淡出
                else -> 1.0 // 中间部分保持最大音量
            }

            val amplitude = 0.6 * envelope // 振幅为最大的60%
            val sampleValue =
                (Short.MAX_VALUE * amplitude * kotlin.math.sin(2.0 * kotlin.math.PI * toneFrequency * time)).toInt()
                    .toShort()

            // 左右声道
            beep[i * 2] = sampleValue
            beep[i * 2 + 1] = sampleValue
        }

        // 使用音频设备播放
        audioDevice.playAudio(beep)
    }

    /**
     * 处理唤醒词检测到的事件
     */
    private suspend fun onKeywordDetected() {
        logger.info("关键词被检测到，进入命令识别状态")
        _assistantState.value = VoiceAssistantApi.AssistantState.LISTENING_COMMAND

        // 根据配置决定使用内部响应还是外部回调
        if (config.useInternalResponse) {
            // 使用内部响应
            speak("我在听")
        } else if (onKeywordDetectedCallback != null) {
            // 使用外部回调处理响应
            onKeywordDetectedCallback?.invoke("小样")
        } else {
            // 默认行为：如果没有配置且没有回调，使用内部响应
            speak("我在听")
        }

        // 这里可以启动语音识别逻辑来捕获用户命令
        // 简单示例：延迟一段时间后回到关键词监听状态
        delay(5000)

        // 回到关键词监听状态
        _assistantState.value = VoiceAssistantApi.AssistantState.LISTENING_KEYWORD
        logger.info("回到关键词监听状态")
    }

    /**
     * 设置关键词检测回调
     * @param callback 检测到关键词时的回调函数
     */
    fun setKeywordDetectedCallback(callback: (String) -> Unit) {
        onKeywordDetectedCallback = callback
        logger.info("已设置关键词检测回调")
    }
}
