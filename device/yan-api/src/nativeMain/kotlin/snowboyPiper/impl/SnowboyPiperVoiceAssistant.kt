@file:OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)

package snowboyPiper.impl

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import snowboyPiper.AudioApplication
import snowboyPiper.config.VoiceAssistantConfig
import snowboyPiper.interfaces.AudioAnalyzer
import snowboyPiper.interfaces.AudioBufferManager
import snowboyPiper.interfaces.VoiceAssistantService
import snowboyPiper.interfaces.VoiceStateManager
import kotlin.time.ExperimentalTime

/**
 * Snowboy和Piper集成的语音助手
 */
class SnowboyPiperVoiceAssistant(
    private val config: VoiceAssistantConfig,
    private val audioAnalyzer: AudioAnalyzer = BasicAudioAnalyzer(
        energyThreshold = config.energyThreshold,
        noiseGateThreshold = config.noiseGateThreshold,
        validVoiceRmsThreshold = config.validVoiceRmsThreshold,
        validVoiceZcrThreshold = config.validVoiceZcrThreshold
    ),
    private val bufferManager: AudioBufferManager = AudioBufferManagerImpl(),
    private val voiceStateManager: VoiceStateManager = VoiceStateManagerImpl(config)
) : VoiceAssistantService {
    // 组件
    private val speechSynthesizer = PiperSpeechSynthesizer()
    private val speechRecognizer = VoskSpeechRecognizer()
    private val audioDevice = PortAudioDevice(speechRecognizer)
    private val audioPlayer = audioDevice
    private val keywordDetector = SnowboyKeywordDetector(
        audioAnalyzer,
        voiceStateManager,
        bufferManager
    )

    // 状态管理
    private val _assistantState = MutableStateFlow(VoiceAssistantService.AssistantState.IDLE)
    override val assistantState: StateFlow<VoiceAssistantService.AssistantState> =
        _assistantState.asStateFlow()

    // 识别结果
    private val _recognizedText = MutableStateFlow<String?>(null)
    override val recognizedText: StateFlow<String?> = _recognizedText.asStateFlow()

    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Default)
    private var assistantJob: Job? = null

    /**
     * 初始化语音助手
     * @return 初始化是否成功
     */
    override suspend fun initialize(): Boolean {
        println("[INFO] 正在初始化语音助手...")
        _assistantState.value = VoiceAssistantService.AssistantState.INITIALIZING
        // 预热关键组件
        AudioApplication.initialize()
        try {
            // 初始化关键词检测器
            if (!initKeywordDetector()) {
                println("[ERROR] 初始化关键词检测器失败")
                return false
            }
            // 初始化音频设备
            if (!initAudioDevice()) {
                println("[ERROR] 初始化音频设备失败")
                return false
            }
            // 初始化语音合成
            if (!initSpeechSynthesizer()) {
                println("[WARN] 初始化语音合成器失败，语音助手将不能语音应答")
                // 继续初始化，因为语音合成不是必需的
            }
            println("[INFO] 语音助手初始化成功")
            _assistantState.value = VoiceAssistantService.AssistantState.IDLE
            return true
        } catch (e: Exception) {
            println("[ERROR] 语音助手初始化异常: ${e.message}")
            e.printStackTrace()
            _assistantState.value = VoiceAssistantService.AssistantState.ERROR
            return false
        }
    }

    /**
     * 初始化关键词检测器
     */
    private fun initKeywordDetector(): Boolean {
        return keywordDetector.initialize(
            config.resourcePath,
            config.modelPath,
            config.snowboySensitivity
        )
    }

    /**
     * 初始化音频设备
     */
    private suspend fun initAudioDevice(): Boolean {
        return audioDevice.initialize()
    }

    /**
     * 初始化语音合成器
     */
    private fun initSpeechSynthesizer(): Boolean {
        return speechSynthesizer.initialize(
            config.piperModelPath,
            config.piperConfigPath,
            config.piperESpeakDataPath,
            0
        )
    }

    /**
     * 启动语音助手
     */
    override suspend fun start(): Boolean {
        if (_assistantState.value == VoiceAssistantService.AssistantState.LISTENING_KEYWORD ||
            _assistantState.value == VoiceAssistantService.AssistantState.LISTENING_COMMAND
        ) {
            println("[WARN] 语音助手已经在运行中")
            return true
        }

        try {
            // 打开音频流
            if (!audioDevice.openInputStream(-1, config.sampleRate, config.channels) ||
                !audioDevice.openOutputStream(-1, config.sampleRate, config.channels)
            ) {
                println("[ERROR] 无法打开音频流")
                _assistantState.value = VoiceAssistantService.AssistantState.ERROR
                return false
            }

            // 重置状态
            voiceStateManager.reset()
            bufferManager.clear()

            // 启动助手任务
            assistantJob?.cancel()
            assistantJob = scope.launch {
                _assistantState.value = VoiceAssistantService.AssistantState.LISTENING_KEYWORD

                try {
                    // 分配音频缓冲区
                    val buffer = nativeHeap.allocArray<ShortVar>(config.bufferSize)

                    try {
                        // 主循环
                        while (isActive) {
                            // 读取音频数据
                            val framesRead = audioDevice.readAudio(buffer, config.bufferSize)
                            if (framesRead <= 0) {
                                kotlinx.coroutines.delay(20)
                                continue
                            }

                            // 处理音频数据
                            val audioData = ShortArray(framesRead) { buffer[it] }
                            processAudio(audioData)

                            // 主循环延迟
                            kotlinx.coroutines.delay(5)
                        }
                    } finally {
                        nativeHeap.free(buffer.rawValue)
                    }
                } catch (e: CancellationException) {
                    println("[INFO] 助手任务已取消")
                } catch (e: Exception) {
                    println("[ERROR] 助手任务异常: ${e.message}")
                    e.printStackTrace()
                    _assistantState.value = VoiceAssistantService.AssistantState.ERROR
                } finally {
                    audioDevice.closeStreams()
                }
            }

            return true
        } catch (e: Exception) {
            println("[ERROR] 启动语音助手异常: ${e.message}")
            e.printStackTrace()
            _assistantState.value = VoiceAssistantService.AssistantState.ERROR
            return false
        }
    }

    /**
     * 处理音频数据
     */
    private fun processAudio(audioData: ShortArray) {
        // 应用噪声门限
        val processedAudio = audioAnalyzer.applyNoiseGate(audioData)

        // 关键词检测
        if (_assistantState.value == VoiceAssistantService.AssistantState.LISTENING_KEYWORD) {
            // 添加到缓冲区
            for (sample in processedAudio) {
                bufferManager.add(sample)
            }

            // 当缓冲区积累足够数据时进行检测
            if (bufferManager.size >= config.accumulationThreshold) {
                val audioBuffer = bufferManager.get()
                val result = keywordDetector.detect(
                    audioPlayer,
                    audioBuffer,
                    audioBuffer.size,
                    config.sampleRate,
                    config.channels
                )

                // 检测到关键词
                if (result.value > 0) {
                    println("[INFO] 检测到关键词!")
                    _assistantState.value = VoiceAssistantService.AssistantState.LISTENING_COMMAND
                    scope.launch {
                        speak("我在听")
                    }
                    bufferManager.clear()
                }

                // 保留部分数据用于连续检测
                bufferManager.retainOverlap(config.overlapSize)
            }
        }
        // 命令监听状态
        else if (_assistantState.value == VoiceAssistantService.AssistantState.LISTENING_COMMAND) {
            // 检测语音活动
            val hasVoice = audioAnalyzer.hasVoiceActivity(processedAudio)
            voiceStateManager.processVoiceActivity(hasVoice, true)

            // 只在有语音时添加到缓冲区
            if (voiceStateManager.speechStarted) {
                for (sample in processedAudio) {
                    bufferManager.add(sample)
                }
            }

            // 静音检测
            if (voiceStateManager.isSilenceThresholdReached(config.silenceFramesThreshold) &&
                voiceStateManager.speechStarted
            ) {
                println("[INFO] 检测到语音结束")

                // 处理命令
                val command = processCommand(bufferManager.get())
                if (command != null) {
                    println("[INFO] 识别到命令: $command")
                    _recognizedText.value = command
                    scope.launch {
                        speak("收到命令: $command")
                    }
                }

                // 回到关键词监听状态
                _assistantState.value = VoiceAssistantService.AssistantState.LISTENING_KEYWORD
                bufferManager.clear()
                voiceStateManager.reset()
            }
        }
    }

    /**
     * 处理语音命令
     * 示例实现，实际项目中需要接入真正的语音识别API
     */
    private fun processCommand(audioData: ShortArray): String? {
        // 示例命令处理逻辑
        return "示例命令"
    }

    /**
     * 停止语音助手
     */
    override suspend fun stop() {
        assistantJob?.cancel()
        assistantJob = null
        audioDevice.closeStreams()
        _assistantState.value = VoiceAssistantService.AssistantState.IDLE
    }

    /**
     * 播放文本
     */
    override suspend fun speak(text: String): Boolean {
        try {
            _assistantState.value = VoiceAssistantService.AssistantState.RESPONDING

            // 语音合成
            val (audioData, audioLength) = speechSynthesizer.synthesize(text) ?: return false

            // 播放语音
            audioPlayer.playAudio(audioData!!, audioLength)

            // 释放资源
            memScoped {
                com.airobot.piperinterop.piper_wrapper_free_audio(audioData)
            }

            return true
        } catch (e: Exception) {
            println("[ERROR] 播放语音异常: ${e.message}")
            return false
        } finally {
            if (_assistantState.value == VoiceAssistantService.AssistantState.RESPONDING) {
                _assistantState.value = VoiceAssistantService.AssistantState.LISTENING_KEYWORD
            }
        }
    }

    /**
     * 释放资源
     */
    override suspend fun release() {
        stop()
        keywordDetector.release()
        speechSynthesizer.release()
        audioDevice.release()
        bufferManager.release()
    }

    /**
     * 非挂起版本的释放方法 - 为兼容可能的其他调用方式
     */
    fun releaseSync() {
        // 通过协程调用suspend版本
        scope.launch {
            release()
        }
    }
}