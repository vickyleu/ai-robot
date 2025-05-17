@file:OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)

package voice.core.service

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.free
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
import kotlinx.datetime.Clock.System
import voice.api.assistant.IVoiceAssistant
import voice.core.app.AudioApplication
import voice.core.config.VoiceAssistantConfig
import voice.hal.PortAudioDevice
import voice.detector.keyword.KeywordDetector
import voice.synthesis.PiperSpeechSynthesizer
import voice.util.LogManager
import kotlin.time.ExperimentalTime

/**
 * Vosk和Piper集成的语音助手
 */
class VoiceAssistant(
    private val config: VoiceAssistantConfig
) : IVoiceAssistant {
    // 日志
    private val logger = LogManager.getLogger("VoiceAssistant")
    
    // 组件
    private val speechSynthesizer = PiperSpeechSynthesizer()
    private val audioDevice = PortAudioDevice()
    private val keywordDetector = KeywordDetector()

    // 状态管理
    private val _assistantState = MutableStateFlow(IVoiceAssistant.AssistantState.IDLE)
    override val assistantState: StateFlow<IVoiceAssistant.AssistantState> =
        _assistantState.asStateFlow()

    // 识别结果
    private val _recognizedText = MutableStateFlow<String?>(null)
    override val recognizedText: StateFlow<String?> = _recognizedText.asStateFlow()

    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Default)
    private var assistantJob: Job? = null
    
    // 标记是否运行
    private var isRunning = false

    /**
     * 初始化语音助手
     * @return 初始化是否成功
     */
    override suspend fun initialize(): Boolean {
        logger.info("正在初始化语音助手...")
        _assistantState.value = IVoiceAssistant.AssistantState.INITIALIZING
        
        // 预热关键组件
        AudioApplication.initialize()
        
        try {
            // 初始化关键词检测器
            if (!initKeywordDetector()) {
                logger.error("初始化关键词检测器失败")
                return false
            }
            
            // 初始化音频设备
            if (!initAudioDevice()) {
                logger.error("初始化音频设备失败")
                return false
            }
            
            // 初始化语音合成
            if (!initSpeechSynthesizer()) {
                logger.warn("初始化语音合成器失败，语音助手将不能语音应答")
                // 继续初始化，因为语音合成不是必需的
            }
            
            logger.info("语音助手初始化成功")
            _assistantState.value = IVoiceAssistant.AssistantState.IDLE
            return true
        } catch (e: Exception) {
            logger.error("语音助手初始化异常: ${e.message}")
            e.printStackTrace()
            _assistantState.value = IVoiceAssistant.AssistantState.ERROR
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
        if (isRunning) {
            logger.warn("语音助手已经在运行中")
            return true
        }

        try {
            // 启动音频设备
            if (!audioDevice.start()) {
                logger.error("无法启动音频设备")
                _assistantState.value = IVoiceAssistant.AssistantState.ERROR
                return false
            }
            
            // 开始关键词监听
            if (!keywordDetector.startListening()) {
                logger.error("无法启动关键词监听")
                _assistantState.value = IVoiceAssistant.AssistantState.ERROR
                return false
            }

            // 启动助手任务
            assistantJob?.cancel()
            assistantJob = scope.launch {
                _assistantState.value = IVoiceAssistant.AssistantState.LISTENING_KEYWORD
                isRunning = true

                try {
                    memScoped {
                        // 分配音频缓冲区
                        val bufferSize = config.bufferSize
                        val bufferArray = nativeHeap.allocArray<ShortVar>(bufferSize)
    
                        try {
                            // 主循环
                            while (isActive) {
                                // 读取音频数据
                                val framesRead = audioDevice.readAudio(bufferArray, bufferSize)
                                if (framesRead <= 0) {
                                    kotlinx.coroutines.delay(20)
                                    continue
                                }
    
                                // 处理音频数据
                                val audioData = ShortArray(framesRead) { bufferArray[it] }
                                processAudio(audioData, framesRead)
    
                                // 主循环延迟
                                kotlinx.coroutines.delay(5)
                            }
                        } finally {
                            nativeHeap.free(bufferArray)
                        }
                    }
                } catch (e: CancellationException) {
                    logger.info("助手任务已取消")
                } catch (e: Exception) {
                    logger.error("助手任务异常: ${e.message}")
                    e.printStackTrace()
                    _assistantState.value = IVoiceAssistant.AssistantState.ERROR
                } finally {
                    isRunning = false
                    audioDevice.stop()
                    keywordDetector.stopListening()
                }
            }

            return true
        } catch (e: Exception) {
            logger.error("启动语音助手异常: ${e.message}")
            e.printStackTrace()
            _assistantState.value = IVoiceAssistant.AssistantState.ERROR
            return false
        }
    }

    /**
     * 处理音频数据
     */
    private fun processAudio(audioData: ShortArray, frameCount: Int) {
        // 处理关键词检测
        if (_assistantState.value == IVoiceAssistant.AssistantState.LISTENING_KEYWORD) {
            // 将数据传递给关键词检测器
            val detected = keywordDetector.processAudioFrame(audioData, frameCount)
            
            // 检测到关键词
            if (detected) {
                logger.info("检测到关键词!")
                _assistantState.value = IVoiceAssistant.AssistantState.LISTENING_COMMAND
                scope.launch {
                    // 播放提示音
                    playActivationSound()
                    
                    // 等待命令超时
                    kotlinx.coroutines.delay(config.commandTimeout)
                    
                    // 如果仍在等待命令，超时返回
                    if (_assistantState.value == IVoiceAssistant.AssistantState.LISTENING_COMMAND) {
                        logger.info("命令监听超时")
                        _assistantState.value = IVoiceAssistant.AssistantState.LISTENING_KEYWORD
                    }
                }
            }
        }
        // TODO: 实现命令识别处理
    }

    /**
     * 播放激活提示音
     */
    private suspend fun playActivationSound() {
        logger.info("播放激活提示音")
        
        // 创建一个简单的哔声
        val beepDuration = 200 // 毫秒
        val sampleRate = config.sampleRate
        val toneFrequency = 880.0 // Hz, A5音
        
        // 计算需要的样本数
        val numSamples = (beepDuration * sampleRate / 1000)
        val beep = ShortArray(numSamples)
        
        // 生成哔声（正弦波）
        for (i in 0 until numSamples) {
            val time = i.toDouble() / sampleRate
            val amplitude = 0.5 // 振幅为最大的50%
            beep[i] = (Short.MAX_VALUE * amplitude * kotlin.math.sin(2.0 * kotlin.math.PI * toneFrequency * time)).toInt().toShort()
        }
        
        // 使用音频设备直接播放
        // 我们现在可以直接调用audioDevice的播放方法，因为它也实现了AudioPlayer接口
        audioDevice.playAudio(beep)
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
        
        _assistantState.value = IVoiceAssistant.AssistantState.IDLE
    }
    
    /**
     * 提交文本命令
     * @param text 文本命令
     * @return 回复内容
     */
    override suspend fun submitTextCommand(text: String): String {
        logger.info("收到文本命令: $text")
        _assistantState.value = IVoiceAssistant.AssistantState.PROCESSING_COMMAND
        
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
        
        _assistantState.value = IVoiceAssistant.AssistantState.SPEAKING
        
        // 合成并播放
        val success = try {
            speechSynthesizer.speak(text)
        } catch (e: Exception) {
            logger.error("语音合成错误: ${e.message}")
            false
        }
        
        _assistantState.value = IVoiceAssistant.AssistantState.LISTENING_KEYWORD
        return success
    }

    /**
     * 释放资源
     */
    override suspend fun release() {
        logger.info("释放语音助手资源")
        stop()
        
        keywordDetector.release()
        speechSynthesizer.release()
        audioDevice.release()
        
        _assistantState.value = IVoiceAssistant.AssistantState.IDLE
    }
}
