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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import voice.api.KeywordDetectorApi
import voice.audio.processing.AudioProcessingFactory
import voice.audio.processing.AudioProcessingManager

/**
 * 语音助手实现
 * 使用Vosk进行语音识别
 */
class VoiceAssistant(
    private val config: VoiceAssistantConfig = VoiceAssistantConfig()
) : VoiceAssistantApi {
    private val logger = LogManager.getLogger("VoiceAssistant")

    // 状态控制
    private val _assistantState = MutableStateFlow(VoiceAssistantApi.AssistantState.IDLE)
    override val assistantState: StateFlow<VoiceAssistantApi.AssistantState> = _assistantState.asStateFlow()
    
    // 内部状态
    private var isInitialized = false
    private var isActivated = false
    private var isRunning = false

    // 核心组件
    private val keywordDetector = KeywordDetector()

    // 协程作用域
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var stateMonitorJob: Job? = null
    private var timeoutJob: Job? = null

    // 回调
    private var speechCallback: ((String) -> Unit)? = null
    private var stateChangeCallback: ((VoiceAssistantApi.AssistantState) -> Unit)? = null

    // 超时设置 (毫秒)
    private val activeListeningTimeout = 10000L
    private val keywords = mutableListOf<String>()

    /**
     * 初始化语音助手
     * @param modelPath 模型路径，包含Vosk模型
     * @return 初始化是否成功
     */
    override fun initialize(modelPath: String): Boolean {
        logger.info("VoiceAssistant.initialize() 被调用")

        if (isInitialized) {
            logger.warn("语音助手已经初始化")
            return true
        }

        try {
            // 初始化关键词检测器
            if (!keywordDetector.initialize(modelPath, config.keywordSensitivity)) {
                logger.error("关键词检测器初始化失败")
                return false
            }

            // 设置关键词检测回调
            (keywordDetector as? KeywordDetector)?.setKeywordCallback { keyword ->
                handleKeywordDetected(keyword)
            }

            // 添加关键词
            keywords.addAll(config.keywords)
            keywords.forEach { keyword ->
                keywordDetector.addKeyword(keyword)
            }

            // 启动状态监控
            startMonitoring()

            isInitialized = true
            _assistantState.value = VoiceAssistantApi.AssistantState.IDLE
            logger.info("语音助手初始化成功")
            return true

        } catch (e: Exception) {
            logger.error("语音助手初始化异常: ${e.message}")
            cleanup()
            return false
        }
    }

    /**
     * 启动状态监控协程
     */
    private fun startMonitoring() {
        stateMonitorJob?.cancel()
        stateMonitorJob = scope.launch {
            // 监控关键词检测器状态
            keywordDetector.detectorState.collectLatest { state ->
                when (state) {
                    KeywordDetectorApi.DetectorState.DETECTED -> {
                        if (_assistantState.value == VoiceAssistantApi.AssistantState.LISTENING_FOR_KEYWORD) {
                            activateAssistant()
                        }
                    }
                    else -> { /* 其他状态不处理 */ }
                }
            }
        }
    }

    /**
     * 开始监听关键词
     * @return 是否成功启动监听
     */
    override suspend fun startListeningForKeyword(): Boolean {
        logger.info("开始监听关键词")

        if (!isInitialized) {
            logger.error("语音助手未初始化")
            return false
        }

        if (_assistantState.value != VoiceAssistantApi.AssistantState.IDLE) {
            logger.warn("语音助手已在活动状态: ${_assistantState.value}")
            return false
        }

        try {
            // 启动关键词检测
            if (!keywordDetector.startListening()) {
                logger.error("无法启动关键词检测")
                return false
            }

            isRunning = true
            _assistantState.value = VoiceAssistantApi.AssistantState.LISTENING_FOR_KEYWORD
            stateChangeCallback?.invoke(_assistantState.value)
            logger.info("成功开始监听关键词")
            return true

        } catch (e: Exception) {
            logger.error("启动关键词监听异常: ${e.message}")
            return false
        }
    }

    /**
     * 激活语音助手开始监听语音命令
     * @return 是否成功激活
     */
    private suspend fun activateAssistant(): Boolean {
        logger.info("激活语音助手")

        if (_assistantState.value != VoiceAssistantApi.AssistantState.LISTENING_FOR_KEYWORD) {
            logger.warn("语音助手状态不正确: ${_assistantState.value}")
            return false
        }

        try {
            _assistantState.value = VoiceAssistantApi.AssistantState.LISTENING_FOR_SPEECH
            stateChangeCallback?.invoke(_assistantState.value)
            isActivated = true

            // 启动超时任务
            startTimeout()

            logger.info("语音助手已激活，等待语音输入")
            return true

        } catch (e: Exception) {
            logger.error("激活语音助手异常: ${e.message}")
            return false
        }
    }

    /**
     * 启动监听超时任务
     */
    private fun startTimeout() {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(activeListeningTimeout)
            if (_assistantState.value == VoiceAssistantApi.AssistantState.LISTENING_FOR_SPEECH) {
                logger.info("语音命令监听超时")
                deactivateAssistant()
            }
        }
    }

    /**
     * 设置语音识别结果回调
     * @param callback 回调函数
     */
    override fun setSpeechRecognizedCallback(callback: (String) -> Unit) {
        this.speechCallback = callback
        logger.info("已设置语音识别回调")
    }

    /**
     * 设置状态改变回调
     * @param callback 回调函数
     */
    override fun setStateChangeCallback(callback: (VoiceAssistantApi.AssistantState) -> Unit) {
        this.stateChangeCallback = callback
        logger.info("已设置状态改变回调")
    }

    /**
     * 关键词检测回调处理
     * @param keyword 检测到的关键词
     */
    private fun handleKeywordDetected(keyword: String) {
        logger.info("检测到关键词: $keyword")
        scope.launch {
            activateAssistant()
        }
    }

    /**
     * 语音处理回调处理
     * @param text 识别到的文本
     */
    private fun handleSpeechProcessed(text: String) {
        logger.info("识别到语音: $text")

        if (isActivated && _assistantState.value == VoiceAssistantApi.AssistantState.LISTENING_FOR_SPEECH) {
            // 取消超时任务
            timeoutJob?.cancel()

            // 调用语音回调
            speechCallback?.invoke(text)

            // 切换到处理状态
            _assistantState.value = VoiceAssistantApi.AssistantState.PROCESSING
            stateChangeCallback?.invoke(_assistantState.value)

            // 处理完成后自动返回关键词监听状态
            scope.launch {
                delay(500) // 给系统一点时间处理，避免过快切换状态
                deactivateAssistant()
            }
        }
    }

    /**
     * 停用语音助手，返回关键词监听状态
     */
    private fun deactivateAssistant() {
        if (isActivated) {
            isActivated = false
            _assistantState.value = VoiceAssistantApi.AssistantState.LISTENING_FOR_KEYWORD
            stateChangeCallback?.invoke(_assistantState.value)
            logger.info("语音助手已返回关键词监听状态")
        }
    }

    /**
     * 停止语音助手
     */
    override fun stop() {
        logger.info("VoiceAssistant.stop() 被调用")

        if (!isInitialized) {
            logger.warn("语音助手未初始化")
            return
        }

        // 取消所有任务
        timeoutJob?.cancel()
        stateMonitorJob?.cancel()

        // 停止组件
        try {
            keywordDetector.stopListening()
        } catch (e: Exception) {
            logger.error("停止组件时出错: ${e.message}")
        }

        isActivated = false
        isRunning = false
        _assistantState.value = VoiceAssistantApi.AssistantState.IDLE
        stateChangeCallback?.invoke(_assistantState.value)
        logger.info("语音助手已停止")
    }

    /**
     * 释放资源
     */
    override fun release() {
        logger.info("VoiceAssistant.release() 被调用")

        // 停止所有运行中的组件
        stop()

        // 释放资源
        cleanup()

        isInitialized = false
        logger.info("语音助手资源已释放")
    }

    /**
     * 清理资源
     */
    private fun cleanup() {
        try {
            keywordDetector.release()
        } catch (e: Exception) {
            logger.error("清理资源时出错: ${e.message}")
        }
    }

    /**
     * 生成诊断报告
     * @return 诊断文本
     */
    fun generateDiagnosis(): String {
        val sb = StringBuilder()
        sb.appendLine("==== 语音助手诊断 ====")
        sb.appendLine("初始化状态: $isInitialized")
        sb.appendLine("当前状态: ${_assistantState.value}")
        sb.appendLine("已激活: $isActivated")
        sb.appendLine("正在运行: $isRunning")
        sb.appendLine("关键词: ${keywords.joinToString(", ")}")
        
        // 添加关键词检测器诊断
        if (keywordDetector is KeywordDetector) {
            sb.appendLine("\n--- 关键词检测器诊断 ---")
            sb.appendLine(keywordDetector.generateDiagnostics())
        }
        
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
