@file:OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)

package snowboyPiper.impl

import com.airobot.device.yanapi.snowboyPiper.config.VoiceAssistantConfig
import com.airobot.device.yanapi.snowboyPiper.impl.AudioBufferManagerImpl
import com.airobot.device.yanapi.snowboyPiper.impl.BasicAudioAnalyzer
import com.airobot.device.yanapi.snowboyPiper.impl.VoiceStateManagerImpl
import com.airobot.device.yanapi.snowboyPiper.interfaces.AudioAnalyzer
import com.airobot.device.yanapi.snowboyPiper.interfaces.AudioBufferManager
import com.airobot.device.yanapi.snowboyPiper.interfaces.VoiceStateManager
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import snowboyPiper.interfaces.KeywordDetector.DetectorState
import snowboyPiper.interfaces.VoiceAssistantService
import kotlin.time.Clock
import kotlin.time.ExperimentalTime


/**
 * Snowboy和Piper集成的语音助手服务实现
 * 整合关键词检测、语音识别、语音合成等功能
 */
class SnowboyPiperVoiceAssistant(
    private val config: VoiceAssistantConfig
) : VoiceAssistantService {
    // 辅助管理组件
    private val audioAnalyzer: AudioAnalyzer = BasicAudioAnalyzer(
        energyThreshold = config.energyThreshold,
        noiseGateThreshold = config.noiseGateThreshold,
        validVoiceRmsThreshold = config.validVoiceRmsThreshold,
        validVoiceZcrThreshold= config.validVoiceZcrThreshold
    )
    // 组件
    private val speechSynthesizer = PiperSpeechSynthesizer()
    private val speechRecognizer = VoskSpeechRecognizer()
    private val voiceStateManager: VoiceStateManager = VoiceStateManagerImpl(config)
    private val keywordDetector = SnowboyKeywordDetector(audioAnalyzer,voiceStateManager)

    private val audioBufferManager: AudioBufferManager = AudioBufferManagerImpl()

    // 助手状态
    private val _assistantState = MutableStateFlow(VoiceAssistantService.AssistantState.IDLE)
    override val assistantState: StateFlow<VoiceAssistantService.AssistantState> = _assistantState.asStateFlow()

    // 识别的文本
    private val _recognizedText = MutableStateFlow<String?>(null)
    override val recognizedText: StateFlow<String?> = _recognizedText.asStateFlow()

    // 时间戳控制
    private var lastKeywordDetectionTime = 0L
    private var lastCommandProcessingTime = 0L
    private var lastErrorLogTime = 0L
    
    // 关键词检测去抖动控制
    private var lastKeywordTriggerTime = 0L
    private val keywordDebouncePeriodMs = 500L // 关键词触发后的去抖动时间（毫秒）

    // 协程作用域和任务
    private val scope = CoroutineScope(Dispatchers.Default)
    private var assistantJob: Job? = null

    /**
     * 初始化语音助手
     */
    override suspend fun initialize(): Boolean {
        println("[INFO] 开始初始化系统...")
        _assistantState.value = VoiceAssistantService.AssistantState.INITIALIZING

        // 初始化音频设备
        if (!speechRecognizer.recordDevice().initialize()) {
            println("[ERROR] 音频设备初始化失败")
            _assistantState.value = VoiceAssistantService.AssistantState.ERROR
            return false
        }
        // 初始化关键词检测器
        if (!keywordDetector.initialize(config.resourcePath, config.modelPath)) {
            println("[ERROR] 关键词检测器初始化失败")
            speechRecognizer.recordDevice().release()
            _assistantState.value = VoiceAssistantService.AssistantState.ERROR
            return false
        }

        // 初始化语音合成器
        if (!speechSynthesizer.initialize(config.piperModelPath, config.piperConfigPath, config.piperESpeakDataPath)) {
            println("[ERROR] 语音合成器初始化失败")
            keywordDetector.release()
            speechRecognizer.recordDevice().release()
            _assistantState.value = VoiceAssistantService.AssistantState.ERROR
            return false
        }

        // 初始化语音识别器
        if (!speechRecognizer.initialize(speechRecognizer.recordDevice(),config.voskModelPath)) {
            println("[ERROR] 语音识别器初始化失败")
            speechSynthesizer.release()
            keywordDetector.release()
            speechRecognizer.recordDevice().release()
            _assistantState.value = VoiceAssistantService.AssistantState.ERROR
            return false
        }

        // 监听识别结果
        scope.launch {
            speechRecognizer.recognitionText.collectLatest { text ->
                if (!text.isNullOrBlank()) {
                    _recognizedText.value = text
                }
            }
        }

        println("[INFO] 初始化完成")
        _assistantState.value = VoiceAssistantService.AssistantState.IDLE
        return true
    }

    /**
     * 启动语音助手
     * @return 是否成功启动
     */
    override suspend fun start(): Boolean {
        if (_assistantState.value == VoiceAssistantService.AssistantState.LISTENING_KEYWORD ||
            _assistantState.value == VoiceAssistantService.AssistantState.LISTENING_COMMAND) {
            println("[WARN] 语音助手已经在运行中")
            return true
        }

        println("[INFO] 启动关键词检测...")

        try {

            // 打开音频输入流
            if (!speechRecognizer.recordDevice().openInputStream(-1, config.sampleRate, config.channels)) {
                println("[ERROR] 无法打开音频输入流")
                _assistantState.value = VoiceAssistantService.AssistantState.ERROR
                return false
            }
            // 打开音频输出流
            if (!speechRecognizer.recordDevice().openOutputStream(-1, config.sampleRate, config.channels)) {
                println("[ERROR] 无法打开音频输出流")
                return false
            }

            // 初始化时间戳
            val currentTime = Clock.System.now().toEpochMilliseconds()
            lastKeywordDetectionTime = currentTime
            lastCommandProcessingTime = currentTime
            lastErrorLogTime = currentTime

            // 重置状态
            voiceStateManager.reset()
            audioBufferManager.clear()
            (audioAnalyzer as? BasicAudioAnalyzer)?.reset()

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
                            val framesRead = speechRecognizer.recordDevice().readAudio(buffer, config.bufferSize)
                            if (framesRead <= 0) {
                                // 读取失败，短暂延迟后重试
                                kotlinx.coroutines.delay(100)
                                continue
                            }

                            // 将数据转换为ShortArray并应用降噪
                            val rawAudioData = ShortArray(framesRead) { i -> buffer[i] }
                            val audioData = audioAnalyzer.applyNoiseGate(rawAudioData)

                            // 检测语音活动并更新状态
                            val hasVoice = audioAnalyzer.hasVoiceActivity(audioData)
                            val stateChanged = voiceStateManager.processVoiceActivity(
                                hasVoice,
                                _assistantState.value == VoiceAssistantService.AssistantState.LISTENING_COMMAND
                            )

                            // 如果状态从非说话变为说话，并且在命令状态，清空累积器准备接收新语音
                            if (stateChanged && _assistantState.value == VoiceAssistantService.AssistantState.LISTENING_COMMAND) {
                                // 移除调试日志，减少输出
                                audioBufferManager.clear()
                                (voiceStateManager as? VoiceStateManagerImpl)?.startSpeechBuffer()
                            }

                            // 音频数据累积逻辑
                            if (_assistantState.value == VoiceAssistantService.AssistantState.LISTENING_COMMAND) {
                                if (voiceStateManager.speechStarted) {
                                    // 只有在语音会话开始后才累积音频数据
                                    audioBufferManager.addAudio(audioData)
                                }
                            } else {
                                // 关键词检测状态，总是累积音频数据
                                audioBufferManager.addAudio(audioData)
                            }

                            // 静音检测和处理
                            if (voiceStateManager.isSilenceThresholdReached(config.silenceFramesThreshold) &&
                                _assistantState.value == VoiceAssistantService.AssistantState.LISTENING_COMMAND &&
                                voiceStateManager.speechStarted && voiceStateManager.speechBufferStarted) {

                                val currentTime = Clock.System.now().toEpochMilliseconds()
                                // 确保距离上次处理有足够间隔
                                if (currentTime - lastCommandProcessingTime > config.commandProcessingIntervalMs) {
                                    // 用户已停止说话，等待一段时间以确保语音完整捕获
                                    println("[INFO] 检测到语音停止，等待处理...")
                                    kotlinx.coroutines.delay(config.postSilenceWaitTimeMs)

                                    // 所有累积的音频数据处理完毕后，停止语音识别
                                    if (audioBufferManager.size > 0) {
                                        val finalAudioData = audioBufferManager.getAccumulatedAudio()
                                        speechRecognizer.processAudio(finalAudioData)
                                    }

                                    // 停止语音识别并等待结果
                                    speechRecognizer.stopRecognition()

                                    // 等待最终识别结果
                                    kotlinx.coroutines.delay(1000)

                                    // 处理识别结果
                                    val text = _recognizedText.value
                                    if (!text.isNullOrBlank()) {
                                        println("[INFO] 收到完整命令: $text")
                                        _assistantState.value = VoiceAssistantService.AssistantState.PROCESSING

                                        // 处理命令
                                        speak("收到命令：$text")

                                        // 重置识别结果
                                        _recognizedText.value = null
                                    } else {
                                        println("[INFO] 无识别结果，回到关键词监听状态")
                                    }

                                    // 回到关键词监听状态
                                    _assistantState.value = VoiceAssistantService.AssistantState.LISTENING_KEYWORD

                                    // 重置语音检测状态
                                    voiceStateManager.markSpeechStopped()
                                    lastCommandProcessingTime = currentTime

                                    // 清空累积器
                                    audioBufferManager.clear()
                                }
                            }

                            // 关键词检测逻辑
                            val currentTime = Clock.System.now().toEpochMilliseconds()
                            if (_assistantState.value == VoiceAssistantService.AssistantState.LISTENING_KEYWORD &&
                                audioBufferManager.size >= config.accumulationThreshold &&
                                currentTime - lastKeywordDetectionTime >= config.keywordDetectionIntervalMs) {

                                val accumulatedData = audioBufferManager.getAccumulatedAudio()

                                // 首先检查音频是否包含有效语音
                                val hasValidVoice = audioAnalyzer.containsValidVoice(accumulatedData)

                                if (hasValidVoice) {
                                    // 只在有效语音时进行关键词检测
                                    println("[INFO] 存在有效音频, 检测关键词...")
                                    val result = keywordDetector.detect(speechRecognizer.playerDevice(), accumulatedData, accumulatedData.size,config.sampleRate, config.channels)
                                    // 只在结果为正时处理
                                    if (result.value > 0) {
                                        // 检查是否在去抖动期内
                                        if (currentTime - lastKeywordTriggerTime < keywordDebouncePeriodMs) {
                                            // 去掉调试日志，减少输出
                                        } else {
                                            println("[INFO] 检测到关键词！结果: $result")
                                            lastKeywordTriggerTime = currentTime

                                            // 如果当前已经在命令监听状态，先停止当前的语音识别会话
                                            if (_assistantState.value == VoiceAssistantService.AssistantState.LISTENING_COMMAND) {
                                                println("[INFO] 停止当前语音识别会话，准备开始新的会话")
                                                speechRecognizer.stopRecognition()
                                                // 等待语音识别器完全停止
                                                kotlinx.coroutines.delay(500)
                                            }

                                            // 检测到关键词，切换到命令监听状态
                                            println("[INFO] 开始监听命令...")
                                            _assistantState.value =
                                                VoiceAssistantService.AssistantState.LISTENING_COMMAND

                                            // 播放提示音
                                            speak("我在呢!")

                                            // 重置状态
                                            voiceStateManager.reset()

                                            // 清空累积器，准备接收新命令
                                            audioBufferManager.clear()

                                            // 启动语音识别
                                            _recognizedText.value = null
                                            speechRecognizer.startRecognition(config.speechRecognitionTimeoutMs)
                                        }
                                    }else if (result == DetectorState.Silence) {
                                        // 减少错误日志频率，只在必要时输出
                                        if (currentTime - lastErrorLogTime > config.errorLogIntervalMs * 10) { // 增加间隔，减少日志
                                            println("[WARN] 关键词检测错误")
                                            lastErrorLogTime = currentTime
                                        }
                                    }
                                    // 移除无效语音的调试日志
                                }

                                    // 更新关键词检测时间
                                    lastKeywordDetectionTime = currentTime
                                    // 保留一部分音频用于连续检测
                                    audioBufferManager.retainOverlap((config.overlapSize * 1.5).toInt())
                                }
                                // 命令处理逻辑
                                if (_assistantState.value == VoiceAssistantService.AssistantState.LISTENING_COMMAND &&
                                    audioBufferManager.size >= 6000 && // 约0.5秒的音频
                                    voiceStateManager.speechStarted && voiceStateManager.speechBufferStarted) {

                                    if (currentTime - lastCommandProcessingTime >= config.commandProcessingIntervalMs) {
                                        // 处理累积的音频
                                        val processingData = audioBufferManager.getAccumulatedAudio()
                                        speechRecognizer.processAudio(processingData)

                                        // 检查是否超时
                                        if (!voiceStateManager.isSpeaking &&
                                            currentTime - voiceStateManager.lastSpeechDetectedTime > config.speechRecognitionTimeoutMs) {
                                            // 超时，回到关键词监听状态
                                            println("[INFO] 命令监听超时，回到关键词监听状态")
                                            speechRecognizer.stopRecognition()
                                            _assistantState.value = VoiceAssistantService.AssistantState.LISTENING_KEYWORD

                                            // 重置状态
                                            voiceStateManager.reset()
                                        }
                                        // 更新处理时间
                                        lastCommandProcessingTime = currentTime
                                    }
                                }
                                // 主循环延迟
                                kotlinx.coroutines.delay(config.mainLoopDelayMs)
                            }
                    }finally {
                        // 确保缓冲区在循环结束后被释放，而不是在循环内部
                        nativeHeap.free(buffer.rawValue)
                    }
                } catch (e: CancellationException) {
                    println("[INFO] 助手任务已取消")
                } catch (e: Exception) {
                    println("[ERROR] 助手任务异常: ${e.message}")
                    e.printStackTrace()
                    _assistantState.value = VoiceAssistantService.AssistantState.ERROR
                } finally {
                    // 关闭音频流
                    speechRecognizer.recordDevice().closeStreams()
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
     * 停止语音助手
     */
    override suspend fun stop() {
        try {
            assistantJob?.cancel()
            assistantJob = null

            speechRecognizer.stopRecognition()
            speechRecognizer.recordDevice().closeStreams()

            _assistantState.value = VoiceAssistantService.AssistantState.IDLE
            println("[INFO] 语音助手已停止")
        } catch (e: Exception) {
            println("[ERROR] 停止语音助手异常: ${e.message}")
            e.printStackTrace()
            _assistantState.value = VoiceAssistantService.AssistantState.ERROR
        }
    }

    /**
     * 播放文本
     * @param text 要播放的文本
     * @return 是否成功播放
     */
    override suspend fun speak(text: String): Boolean {
        try {
            _assistantState.value = VoiceAssistantService.AssistantState.RESPONDING

            // 合成语音
            val (audioData, audioLength) = speechSynthesizer.synthesize(text) ?: return false.apply{
               println("[ERROR] piper生成错误 text=${text}\n")
            }

            // 打开音频输出流
            if (!speechRecognizer.recordDevice().openOutputStream(-1, config.sampleRate, config.channels)) {
                println("[ERROR] 无法打开音频输出流")
                return false
            }

            // 播放语音
            val result = speechRecognizer.playerDevice().playAudio(audioData!!, audioLength)

            // 释放音频数据
            memScoped {
                com.airobot.piperinterop.piper_wrapper_free_audio(audioData)
            }
            return result > 0
        } catch (e: Exception) {
            println("[ERROR] 播放语音异常: ${e.message}")
            e.printStackTrace()
            return false
        } finally {
            // 恢复之前的状态
            if (_assistantState.value == VoiceAssistantService.AssistantState.RESPONDING) {
                _assistantState.value = VoiceAssistantService.AssistantState.LISTENING_KEYWORD
            }
        }
    }

    /**
     * 释放资源
     */
    override suspend fun release() {
        runBlocking {
            try {
                // 停止助手
                stop()

                // 释放所有资源
                speechRecognizer.recordDevice().release()
                speechRecognizer.release()
                speechSynthesizer.release()
                keywordDetector.release()


                _assistantState.value = VoiceAssistantService.AssistantState.IDLE
                println("[INFO] 所有资源已释放")
            } catch (e: Exception) {
                println("[ERROR] 释放资源异常: ${e.message}")
                e.printStackTrace()
                _assistantState.value = VoiceAssistantService.AssistantState.ERROR
            }
        }
    }
}