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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock.System
import voice.api.assistant.IVoiceAssistant
import voice.core.app.AudioApplication
import voice.core.config.VoiceAssistantConfig
import voice.acquisition.portaudio.PortAudioDevice
import voice.detector.keyword.KeywordDetector
import voice.hal.AudioDevice
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
    private val audioDevice = PortAudioDevice.getInstance() // 使用全局单例
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
        
        try {
            // 初始化关键词检测器
            if (!initKeywordDetector()) {
                logger.error("初始化关键词检测器失败")
                return false
            }
            
            // 初始化音频设备（只初始化一次）
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
            logger.info("正在启动音频设备...")
            
            // 启动音频设备，确保返回true
            if (!audioDevice.start()) {
                logger.error("无法启动音频设备")
                _assistantState.value = IVoiceAssistant.AssistantState.ERROR
                return false
            }
            
            logger.info("音频设备启动成功")
            
            // 启动关键词监听
            if (!keywordDetector.startListening()) {
                logger.error("无法启动关键词监听")
                audioDevice.stop()
                _assistantState.value = IVoiceAssistant.AssistantState.ERROR
                return false
            }
            
            logger.info("关键词监听启动成功")

            // 启动助手任务
            assistantJob?.cancel()
            assistantJob = scope.launch {
                _assistantState.value = IVoiceAssistant.AssistantState.LISTENING_KEYWORD
                isRunning = true

                try {
                    // 打开输入流，使用立体声模式
                    if (!audioDevice.openInputStream(deviceIndex = -1, sampleRate = config.sampleRate, channels = 2)) {
                        logger.error("无法打开音频输入流")
                        throw IllegalStateException("无法打开音频输入流")
                    }
                    
                    logger.info("音频输入流已打开，参数: 采样率=${config.sampleRate}, 通道=2")
                    
                    // 流打开后稍等片刻让系统稳定
                    delay(500)
                    
                    // 主循环 - 监听唤醒词
                    logger.info("已开始监听关键词，等待唤醒...")
                    
                    // 音频帧读取缓冲区 - 分配一次重复使用
                    val frameSize = 1024
                    val buffer = nativeHeap.allocArray<ShortVar>(frameSize)
                    val audioData = ShortArray(frameSize) // 预分配，避免频繁创建对象
                    
                    while (isActive && isRunning) {
                        try {
                            // 从音频设备读取数据
                            val framesRead = audioDevice.readAudio(buffer, frameSize)
                            
                            // 如果没有读取到数据，短暂休眠避免CPU空转，但不要太长
                            if (framesRead <= 0) {
                                delay(5) // 非常短的延迟，5ms左右
                                continue
                            }
                            
                            // 复制数据以便处理 - 复用已分配的空间
                            for (i in 0 until framesRead) {
                                audioData[i] = buffer[i]
                            }
                            
                            // 关键词检测 - 使用有效帧长度
                            val detected = keywordDetector.detect(audioData.copyOfRange(0, framesRead))
                            if (detected) {
                                logger.info("检测到关键词！")
                                
                                // 确保输出流已打开（只打开一次）
                                if (!audioDevice.openOutputStream(deviceIndex = -1, sampleRate = config.sampleRate, channels = 2)) {
                                    logger.warn("无法打开输出流，但将继续处理")
                                }
                                
                                // 播放应答提示音
//                                playAcknowledgeTone()
                                
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
                            // 短暂延迟后继续
                            delay(500)
                        }
                    }
                    
                    // 清理资源
                    nativeHeap.free(buffer)
                    
                } catch (e: Exception) {
                    logger.error("语音助手主循环发生致命异常: ${e.message}")
                    e.printStackTrace()
                    _assistantState.value = IVoiceAssistant.AssistantState.ERROR
                } finally {
                    // 确保清理资源
                    isRunning = false
                    _assistantState.value = IVoiceAssistant.AssistantState.IDLE
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
        
        // 创建立体声哔声
        val beep = ShortArray(numSamples * 2)
        
        // 生成哔声（正弦波）- 立体声格式
        for (i in 0 until numSamples) {
            val time = i.toDouble() / sampleRate
            val amplitude = 0.5 // 振幅为最大的50%
            val sampleValue = (Short.MAX_VALUE * amplitude * kotlin.math.sin(2.0 * kotlin.math.PI * toneFrequency * time)).toInt().toShort()
            
            // 左右声道
            beep[i * 2] = sampleValue
            beep[i * 2 + 1] = sampleValue
        }
        
        // 使用音频设备直接播放
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
        
        // 确保输出流已打开
        if (!audioDevice.openOutputStream(deviceIndex = -1, sampleRate = config.sampleRate, channels = 2)) {
            logger.warn("无法打开输出流，但将继续尝试合成")
        }
        
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
        // 不要在此处释放音频设备，因为它是全局单例
        // 应用程序关闭时会释放
        
        _assistantState.value = IVoiceAssistant.AssistantState.IDLE
    }

    /**
     * 播放应答提示音
     */
    private suspend fun playAcknowledgeTone() {
        logger.info("播放应答提示音")
        
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
            val sampleValue = (Short.MAX_VALUE * amplitude * kotlin.math.sin(2.0 * kotlin.math.PI * toneFrequency * time)).toInt().toShort()
            
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
        _assistantState.value = IVoiceAssistant.AssistantState.LISTENING_COMMAND
        
        // 播放一个简短的回应
        speak("我在听")
        
        // 这里可以启动语音识别逻辑来捕获用户命令
        // 简单示例：延迟一段时间后回到关键词监听状态
        delay(5000)
        
        // 回到关键词监听状态
        _assistantState.value = IVoiceAssistant.AssistantState.LISTENING_KEYWORD
        logger.info("回到关键词监听状态")
    }
}
