@file:OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)

package com.airobot.device.yanapi.voice.speech

import com.airobot.device.yanapi.voice.analysis.BasicAudioAnalyzer
import com.airobot.device.yanapi.voice.audio.BasicAudioProcessor
import com.airobot.device.yanapi.voice.audio.PortAudioPlayer
import com.airobot.device.yanapi.voice.audio.PortAudioRecorder
import com.airobot.device.yanapi.voice.interfaces.AudioAnalyzer
import com.airobot.device.yanapi.voice.interfaces.AudioPlayer
import com.airobot.device.yanapi.voice.interfaces.AudioProcessor
import com.airobot.device.yanapi.voice.interfaces.AudioRecorder
import com.airobot.device.yanapi.voice.interfaces.SpeechRecognizer
import com.airobot.device.yanapi.voice.interfaces.SpeechSynthesizer
import com.airobot.device.yanapi.voice.interfaces.VoiceAssistant
import com.airobot.device.yanapi.voice.interfaces.WakewordDetector
import com.airobot.device.yanapi.voice.wakeword.EnhancedWakewordDetector
import com.airobot.device.yanapi.voice.wakeword.SnowboyWakewordDetector
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.sin
import kotlin.time.ExperimentalTime

/**
 * 基础语音助手实现
 * 集成了唤醒词检测、语音识别和语音合成功能
 */
class BasicVoiceAssistant : VoiceAssistant {
    // 状态管理
    private val _state = MutableStateFlow(VoiceAssistant.AssistantState.IDLE)
    override val state: StateFlow<VoiceAssistant.AssistantState> = _state.asStateFlow()

    // 组件
    private val audioRecorder: AudioRecorder = PortAudioRecorder()
    private val audioPlayer: AudioPlayer = PortAudioPlayer()
    private val audioProcessor: AudioProcessor = BasicAudioProcessor()
    private val audioAnalyzer: AudioAnalyzer = BasicAudioAnalyzer(
        energyThreshold = 800.0,
        noiseGateThreshold = 300.0,
        validVoiceRmsThreshold = 1000.0,
        validVoiceZcrThreshold = 0.2
    )

    // 使用增强型唤醒词检测器降低误报
    private val baseWakewordDetector = SnowboyWakewordDetector(audioAnalyzer)
    private val wakewordDetector: WakewordDetector = EnhancedWakewordDetector(
        baseWakewordDetector,
        audioAnalyzer
    )

    // 这两个组件需要从外部注入，便于替换不同实现
    private var speechRecognizer: SpeechRecognizer? = null
    private var speechSynthesizer: SpeechSynthesizer? = null

    // 协程作用域和任务
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var assistantJob: Job? = null
    private var listeningTimeout: Job? = null

    // 录音缓冲区和参数
    private val audioBufferSize = 1600 // 100ms @ 16kHz
    private var audioBuffer = ShortArray(audioBufferSize)

    // 命令处理器
    private var commandHandler: (suspend (String) -> String)? = null

    // 参数配置
    private var wakewordSensitivity = 0.6f // 降低灵敏度以减少误触发
    private var listeningTimeoutMs = 6000L // 监听超时时间
    private var isSoundFeedbackEnabled = true // 是否启用声音反馈

    // 音频处理配置
    private var gain = 1.2f           // 默认增益
    private var noiseGate = 120       // 默认噪声门限
    private var lowPassFilter = 0.85f // 默认低通滤波系数

    // 状态标志
    private var isInitialized = false
    private var isStarted = false

    /**
     * 初始化语音助手
     */
    override fun initialize(
        wakewordResource: String,
        wakewordModel: String,
        recognizerModel: String,
        synthesizerModel: String,
        synthesizerConfig: String,
        synthesizerESpeakDataPath: String,
    ): Boolean {
        if (isInitialized) return true

        _state.value = VoiceAssistant.AssistantState.INITIALIZING

        try {
            // 初始化音频录制器
            val recorderInitialized = audioRecorder.initialize(
                sampleRate = 48000,
                channels = 1
            )

            if (!recorderInitialized) {
                println("[ERROR] 音频录制器初始化失败")
                _state.value = VoiceAssistant.AssistantState.ERROR
                return false
            }

            // 初始化音频播放器
            val playerInitialized = audioPlayer.initialize(
                sampleRate = 48000,
                channels = 1
            )

            if (!playerInitialized) {
                println("[ERROR] 音频播放器初始化失败")
                _state.value = VoiceAssistant.AssistantState.ERROR
                return false
            }
            
            // 配置音频处理器
            (audioProcessor as? BasicAudioProcessor)?.let {
                it.setGain(gain)               // 设置增益
                it.setNoiseGate(noiseGate)     // 设置噪声门限
                it.setLowPassFilterCoeff(lowPassFilter) // 设置低通滤波系数
                it.reset()                     // 重置状态
                println("[INFO] 音频处理器已配置")
            }

            // 初始化唤醒词检测器
            val wakewordInitialized = wakewordDetector.initialize(
                resourcePath = wakewordResource,
                modelPath = wakewordModel,
                sensitivity = wakewordSensitivity
            )

            if (!wakewordInitialized) {
                println("[ERROR] 唤醒词检测器初始化失败")
                _state.value = VoiceAssistant.AssistantState.ERROR
                return false
            }
            
            // 初始化语音识别器
            speechRecognizer = VoskSpeechRecognizer()
            val recognizerInitialized = speechRecognizer?.initialize(recognizerModel, "zh-CN") ?: false
            
            if (!recognizerInitialized) {
                println("[WARN] 语音识别器初始化失败，将无法进行语音识别")
                // 由于语音识别是可选的，这里不会导致整个助手初始化失败
                speechRecognizer = null
            } else {
                println("[INFO] 语音识别器初始化成功")
            }
            
            // 初始化语音合成器
            speechSynthesizer = PiperSpeechSynthesizer()
            val synthesizerInitialized = speechSynthesizer?.initialize(
                synthesizerModel,
                synthesizerConfig,
                "zh-CN"
            ) ?: false
            
            if (!synthesizerInitialized) {
                println("[WARN] 语音合成器初始化失败，将无法进行语音合成")
                // 由于语音合成是可选的，这里不会导致整个助手初始化失败
                speechSynthesizer = null
            } else {
                println("[INFO] 语音合成器初始化成功")
            }

            // 设置回调
            setupCallbacks()

            isInitialized = true
            _state.value = VoiceAssistant.AssistantState.IDLE
            println("[INFO] 语音助手初始化成功")
            return true
        } catch (e: Exception) {
            println("[ERROR] 初始化语音助手时发生异常: ${e.message}")
            e.printStackTrace()
            _state.value = VoiceAssistant.AssistantState.ERROR
            return false
        }
    }

    /**
     * 设置回调函数
     */
    private fun setupCallbacks() {
        // 设置录音数据回调
        audioRecorder.setAudioCallback { data, frameCount ->
            processAudioFrame(data, frameCount)
        }

        // 设置唤醒词检测回调
        wakewordDetector.setDetectionCallback { result ->
            if (result == WakewordDetector.DetectionResult.WAKEWORD_DETECTED) {
                handleWakewordDetected()
            }
        }

        // 监听状态变化
        scope.launch {
            wakewordDetector.state.collect { state ->
                when (state) {
                    WakewordDetector.DetectorState.ERROR -> _state.value =
                        VoiceAssistant.AssistantState.ERROR

                    WakewordDetector.DetectorState.DETECTED -> {
                        if (_state.value == VoiceAssistant.AssistantState.LISTENING) {
                            _state.value = VoiceAssistant.AssistantState.ACTIVE

                            // 播放激活提示音
                            if (isSoundFeedbackEnabled) {
                                playActivationSound()
                            }

                            // 启动语音识别
                            startListeningForCommand()
                        }
                    }

                    else -> {}
                }
            }
        }
    }

    /**
     * 处理音频帧
     */
    private fun processAudioFrame(data: ShortArray, frameCount: Int) {
        // 使用BasicAudioProcessor处理原始音频数据
        val processedData = audioProcessor.processAudio(data)
        
        val currentState = _state.value

        // 根据不同状态处理音频
        when (currentState) {
            VoiceAssistant.AssistantState.LISTENING -> {
                // 唤醒词检测
                wakewordDetector.detect(processedData, frameCount)
            }

            VoiceAssistant.AssistantState.ACTIVE -> {
                // 命令识别中，将音频传给识别器
                speechRecognizer?.processAudio(processedData, frameCount)
            }

            else -> {
                // 其他状态不处理音频
            }
        }
    }

    /**
     * 处理唤醒词检测事件
     */
    private fun handleWakewordDetected() {
        if (_state.value != VoiceAssistant.AssistantState.LISTENING) return

        println("[INFO] 检测到唤醒词")
        _state.value = VoiceAssistant.AssistantState.ACTIVE

        // 启动命令监听
        startListeningForCommand()
    }

    /**
     * 开始监听命令
     */
    private fun startListeningForCommand() {
        if (speechRecognizer == null) {
            println("[WARN] 语音识别器未初始化，无法监听命令")
            _state.value = VoiceAssistant.AssistantState.LISTENING
            return
        }

        // 取消之前的超时
        listeningTimeout?.cancel()

        // 开始识别
        speechRecognizer?.startRecognition()

        // 设置超时
        listeningTimeout = scope.launch {
            delay(listeningTimeoutMs)

            // 如果还在活跃状态，则是超时
            if (_state.value == VoiceAssistant.AssistantState.ACTIVE) {
                println("[INFO] 命令监听超时")

                // 停止识别
                val result = speechRecognizer?.stopRecognition()

                // 返回监听状态
                _state.value = VoiceAssistant.AssistantState.LISTENING
            }
        }

        // 监听识别结果
        scope.launch {
            speechRecognizer?.results?.collect { result ->
                println("[INFO] 识别结果: ${result.text}, 置信度: ${result.confidence}")
                if (!result.isPartial && result.confidence > 0.6f) {
                    // 取消超时
                    listeningTimeout?.cancel()

                    // 处理命令
                    handleCommand(result.text)
                }
            }
        }
    }

    /**
     * 处理识别到的命令
     */
    private suspend fun handleCommand(command: String) {
        if (command.isBlank()) {
            _state.value = VoiceAssistant.AssistantState.LISTENING
            return
        }

        println("[INFO] 识别到命令: $command")
        _state.value = VoiceAssistant.AssistantState.THINKING

        // 停止语音识别
        speechRecognizer?.stopRecognition()

        try {
            // 处理命令
            val handler = commandHandler ?: { "对不起，我无法处理这个命令" }
            val response = handler(command)

            // 播放响应
            _state.value = VoiceAssistant.AssistantState.RESPONDING
            val speakSuccess = speak(response)

            // 返回监听状态
            _state.value = VoiceAssistant.AssistantState.LISTENING
        } catch (e: Exception) {
            println("[ERROR] 处理命令时发生异常: ${e.message}")
            e.printStackTrace()
            _state.value = VoiceAssistant.AssistantState.LISTENING
        }
    }

    /**
     * 播放激活提示音
     */
    private fun playActivationSound() {
        // 生成简短的提示音
        val beepData = ShortArray(4000) { i ->
            when {
                i < 1000 -> (Short.MAX_VALUE * 0.2 * sin(i * 0.03)).toInt().toShort()
                else -> 0
            }
        }

        // 使用音频处理器处理提示音
        val processedBeep = audioProcessor.processAudio(beepData)
        
        audioPlayer.playAudio(processedBeep, processedBeep.size)

        // 通知分析器音频播放事件，避免回声误触发
        audioAnalyzer.notifyAudioPlayback()
    }

    /**
     * 启动语音助手
     */
    override fun start(): Boolean {
        if (!isInitialized) {
            println("[ERROR] 请先初始化语音助手")
            return false
        }

        if (isStarted) return true

        try {
            // 启动录音
            if (!audioRecorder.startRecording()) {
                println("[ERROR] 启动录音失败")
                _state.value = VoiceAssistant.AssistantState.ERROR
                return false
            }

            // 重置音频处理器
            audioProcessor.reset()

            // 设置状态为监听
            _state.value = VoiceAssistant.AssistantState.LISTENING
            isStarted = true

            println("[INFO] 语音助手已启动，正在监听唤醒词")
            return true
        } catch (e: Exception) {
            println("[ERROR] 启动语音助手时发生异常: ${e.message}")
            e.printStackTrace()
            _state.value = VoiceAssistant.AssistantState.ERROR
            return false
        }
    }

    /**
     * 停止语音助手
     */
    override fun stop() {
        if (!isStarted) return

        try {
            // 停止录音
            audioRecorder.stopRecording()

            // 停止唤醒词检测
            wakewordDetector.stopDetection()

            // 停止语音识别
            speechRecognizer?.cancelRecognition()

            // 取消超时任务
            listeningTimeout?.cancel()

            // 设置状态为空闲
            _state.value = VoiceAssistant.AssistantState.IDLE
            isStarted = false

            println("[INFO] 语音助手已停止")
        } catch (e: Exception) {
            println("[ERROR] 停止语音助手时发生异常: ${e.message}")
            e.printStackTrace()
            _state.value = VoiceAssistant.AssistantState.ERROR
        }
    }

    /**
     * 手动激活语音助手
     */
    override fun activate() {
        if (_state.value != VoiceAssistant.AssistantState.LISTENING) {
            println("[WARN] 只能在监听状态下手动激活语音助手")
            return
        }

        handleWakewordDetected()
    }

    /**
     * 提交文本命令
     */
    override suspend fun submitTextCommand(command: String) {
        if (_state.value == VoiceAssistant.AssistantState.IDLE ||
            _state.value == VoiceAssistant.AssistantState.ERROR
        ) {
            println("[WARN] 语音助手未启动或状态错误")
            return
        }

        // 直接处理命令，跳过语音识别
        handleCommand(command)
    }

    /**
     * 设置命令处理器
     */
    override fun setCommandHandler(handler: suspend (String) -> String) {
        this.commandHandler = handler
    }

    /**
     * 播放文本
     */
    override suspend fun speak(text: String): Boolean {
        if (speechSynthesizer == null) {
            println("[WARN] 语音合成器未初始化，无法播放文本")
            return false
        }

        try {
            println("[INFO] 播放文本: $text")

            // 使用语音合成器生成语音
            val success = speechSynthesizer?.speak(text, "default") ?: false

            // 通知分析器音频播放事件，避免回声误触发
            audioAnalyzer.notifyAudioPlayback()

            return success
        } catch (e: Exception) {
            println("[ERROR] 播放文本时发生异常: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    /**
     * 释放资源
     */
    override fun release() {
        stop()

        // 释放资源
        audioRecorder.release()
        audioPlayer.release()
        wakewordDetector.release()
        speechRecognizer?.release()
        speechSynthesizer?.release()

        // 取消协程
        scope.cancel()

        isInitialized = false
        isStarted = false

        println("[INFO] 语音助手资源已释放")
    }

    /**
     * 设置语音识别器
     */
    fun setSpeechRecognizer(recognizer: SpeechRecognizer) {
        this.speechRecognizer = recognizer
    }

    /**
     * 设置语音合成器
     */
    fun setSpeechSynthesizer(synthesizer: SpeechSynthesizer) {
        this.speechSynthesizer = synthesizer
    }

    /**
     * 设置参数
     * @param wakewordSensitivity 唤醒词灵敏度，范围0-1
     * @param listeningTimeoutMs 命令监听超时时间，毫秒
     * @param soundFeedback 是否启用声音反馈
     * @param audioGain 音频增益，大于0的值
     * @param noiseGateThreshold 噪声门限阈值，0-500之间
     * @param lowPassCoefficient 低通滤波系数，0-1之间
     */
    fun setParameters(
        wakewordSensitivity: Float? = null,
        listeningTimeoutMs: Long? = null,
        soundFeedback: Boolean? = null,
        audioGain: Float? = null,
        noiseGateThreshold: Int? = null,
        lowPassCoefficient: Float? = null
    ) {
        wakewordSensitivity?.let { this.wakewordSensitivity = it }
        listeningTimeoutMs?.let { this.listeningTimeoutMs = it }
        soundFeedback?.let { this.isSoundFeedbackEnabled = it }
        
        // 更新音频处理器参数
        (audioProcessor as? BasicAudioProcessor)?.let { processor ->
            audioGain?.let { 
                this.gain = it
                processor.setGain(it) 
            }
            
            noiseGateThreshold?.let { 
                this.noiseGate = it
                processor.setNoiseGate(it) 
            }
            
            lowPassCoefficient?.let { 
                this.lowPassFilter = it
                processor.setLowPassFilterCoeff(it)
            }
        }
    }
} 