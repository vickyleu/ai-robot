@file:OptIn(ExperimentalForeignApi::class)

package snowboyPiper.impl

import com.airobot.core.service.VisionService.RecognitionResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import snowboyPiper.interfaces.AudioAnalyzer
import snowboyPiper.interfaces.AudioDevice
import snowboyPiper.interfaces.AudioPlayer
import snowboyPiper.interfaces.SpeechRecognizer
import snowboyPiper.interop.SpeexDspProcessor

/**
 * Vosk语音识别器实现
 * 负责将语音转换为文本
 */
class VoskSpeechRecognizer : SpeechRecognizer {

    // Vosk语音识别服务
    private val voskService = VoskSpeechService(this)
    private val audioRecordDevice = PortAudioDevice(this)

    // 音频播放器
    private val audioPlayer = PortAudioPlayer(this)

    // 识别状态
    private val _recognitionState = MutableStateFlow(SpeechRecognizer.RecognitionState.IDLE)
    override val recognitionState: StateFlow<SpeechRecognizer.RecognitionState> =
        _recognitionState.asStateFlow()

    // 识别结果
    private val _recognitionText = MutableStateFlow<String?>(null)
    override val recognitionText: StateFlow<String?> = _recognitionText.asStateFlow()

    // 识别结果
    private val _recognitionResult = MutableStateFlow<RecognitionResult?>(null)
    val recognitionResult: StateFlow<RecognitionResult?> = _recognitionResult

    // 协程作用域和任务
    private val scope = CoroutineScope(Dispatchers.Default)
    private var recognitionJob: Job? = null

    // 音频处理计数器
    private var audioProcessCounter = 0

    // SpeexDSP 处理器
    private val speexDsp = SpeexDspProcessor()

    // 音频分析器 (RNNoise)
    // 初始化音频分析器 (RNNoise)，调低阈值提高灵敏度
    val audioAnalyzer: AudioAnalyzer = BasicAudioAnalyzer(
        energyThreshold = 25.0,  // 降低能量阈值
        noiseGateThreshold = 15.0, // 降低噪声门限
        validVoiceRmsThreshold = 40.0, // 降低有效语音RMS阈值
        validVoiceZcrThreshold = 0.12  // 降低过零率阈值
    )

    init {

        // 初始化SpeexDSP - 配置预处理器
        speexDsp.initialize(
            sampleRate = 16000,
            frameSize = 480,  // 30ms @ 16kHz
            enableDenoise = true,
            enableAgc = true,  // 自动增益控制
            enableVad = true,  // 语音活动检测
            enableEcho = true  // 回声消除
        )

        // 启用SpeexDSP高级参数设置
        speexDsp.setDenoiseLevel(8) // 更高强度降噪 (0-10)
        speexDsp.setAgcLevel(24000) // 目标音量电平
        speexDsp.setAgcMaxGain(30.0f) // 最大增益
        speexDsp.setEchoSuppress(-40) // 增强回声抑制 (dB)
        speexDsp.setEchoSuppressActive(-45) // 有语音时的抑制 (dB)
    }

    /**
     * 实现SpeechRecognizer接口的initialize方法
     */
    override fun initialize(
        audioRecordDevice: AudioDevice,
        modelPath: String,
        deviceName: String,
        sampleRate: Int,
        micVolume: Int
    ): Boolean {
        try {
            println("[INFO] 正在初始化VoskSpeechRecognizer: 模型=$modelPath, 设备=$deviceName, 采样率=$sampleRate")

            // 初始化Vosk服务
            val initialized = voskService.initialize(modelPath)
            if (!initialized) {
                println("[ERROR] Vosk语音服务初始化失败")
                return false
            }

            // 重置音频处理组件
            audioAnalyzer.reset()
            speexDsp.reset()

            println("[INFO] VoskSpeechRecognizer初始化成功")
            return true
        } catch (e: Exception) {
            println("[ERROR] VoskSpeechRecognizer初始化异常: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    fun recordDevice(): AudioDevice {
        return audioRecordDevice
    }

    fun playerDevice(): AudioPlayer {
        return audioPlayer
    }

    // 核心音频处理方法，集成SpeexDSP和RNNoise
    fun processAudioForRecognition(audioData: ShortArray): Pair<ShortArray, Boolean> {
        // 1. SpeexDSP预处理 (AEC, AGC, 预降噪等)
        val speexProcessedAudio = speexDsp.process(audioData)

        // 2. RNNoise精确降噪
        val denoisedAudio = audioAnalyzer.applyNoiseGate(speexProcessedAudio)

        // 3. VAD检测语音活动
        val hasVoice = audioAnalyzer.hasVoiceActivity(denoisedAudio)

        return Pair(denoisedAudio, hasVoice)
    }

    // 为VoskService使用的完整处理函数
    fun recognizeAudio(audioData: ShortArray): String {
        // 跟踪连续有语音的帧数，避免短暂停顿造成识别断断续续
        val (processedAudio, hasVoice) = processAudioForRecognition(audioData)

        // 如果没有检测到语音活动，短帧直接跳过
        if (!hasVoice && audioData.size < 1600) {
            return ""
        }

        // 即使没有检测到语音，也将处理后的音频发送给Vosk
        // 这样可以让Vosk处理那些VAD可能错过的弱语音
        // 但会通过日志输出识别状态
        if (!hasVoice) {
            // 只处理不记录
            voskService.processAudio(processedAudio)
            return ""
        }

        // 有语音活动，进行实际识别
        return if (voskService.processAudio(processedAudio)) {
            val result = voskService.recognitionText.value ?: ""
            if (result.isNotEmpty()) {
                println("[INFO] 高置信度识别: $result")
            }
            result
        } else {
            ""
        }
    }

    // 添加对回声参考信号的处理，这个方法应该在音频播放时调用
    fun processPlaybackReference(playbackData: ShortArray) {
        // 将播放音频传递给SpeexDSP作为回声参考
        speexDsp.setPlaybackReference(playbackData)

        // 同时通知RNNoise分析器有播放正在进行
        audioAnalyzer.notifyAudioPlayback(playbackData)
    }

    // 优化现有的processAudio方法，集成VAD和降噪
    override fun processAudio(audioData: ShortArray): Boolean {
        if (_recognitionState.value != SpeechRecognizer.RecognitionState.LISTENING) {
            return false
        }

        try {
            // 检查音频数据是否有效
            if (audioData.isEmpty()) {
                return false
            }

            // 使用优化的音频处理链
            val (processedAudio, hasVoice) = processAudioForRecognition(audioData)

            // 增加计数器
            audioProcessCounter++

            // 只有在有语音活动时才进行识别
            if (hasVoice) {
                return voskService.processAudio(processedAudio)
            }

            // 仍然返回true表示处理成功，但跳过实际识别
            return true
        } catch (e: Exception) {
            println("[ERROR] 处理音频数据异常: ${e.message}")
            return false
        }
    }

    /**
     * 开始识别
     * @param timeoutMs 超时时间（毫秒）
     * @return 是否成功开始识别
     */
    override fun startRecognition(timeoutMs: Long): Boolean {
        if (_recognitionState.value == SpeechRecognizer.RecognitionState.LISTENING) {
            println("[WARN] 语音识别已经在运行中")
            return true
        }

        try {
            // 重置识别结果
            _recognitionText.value = null

            // 重置音频处理器状态
            audioAnalyzer.reset()
            speexDsp.reset()

            // 启动识别任务
            recognitionJob?.cancel()
            recognitionJob = scope.launch {
                _recognitionState.value = SpeechRecognizer.RecognitionState.LISTENING
                println("[INFO] 开始语音识别，超时时间: ${timeoutMs}ms")

                // 启动Vosk识别
                voskService.startRecognition()

                // 设置超时
                val result = withTimeoutOrNull(timeoutMs) {
                    while (true) {
                        val text = voskService.recognitionText.value
                        if (!text.isNullOrBlank()) {
                            _recognitionText.value = text
                            println("[INFO] 识别结果: $text")
                            break
                        }
                        kotlinx.coroutines.delay(100)
                    }
                    true
                }

                // 停止识别
                voskService.stopRecognition()

                if (result == null) {
                    println("[INFO] 语音识别超时")
                    _recognitionState.value = SpeechRecognizer.RecognitionState.IDLE
                } else {
                    _recognitionState.value = SpeechRecognizer.RecognitionState.PROCESSING
                    _recognitionState.value = SpeechRecognizer.RecognitionState.IDLE
                }
            }

            return true
        } catch (e: Exception) {
            println("[ERROR] 启动语音识别异常: ${e.message}")
            e.printStackTrace()
            _recognitionState.value = SpeechRecognizer.RecognitionState.ERROR
            return false
        }
    }

    /**
     * 停止识别
     */
    // 添加标志位防止递归调用
    private var isStoppingRecognition = false

    override fun stopRecognition() {
        // 如果已经在停止过程中，直接返回，防止递归调用
        if (isStoppingRecognition) {
            println("[DEBUG] 已经在停止语音识别过程中，避免递归调用")
            return
        }

        try {
            isStoppingRecognition = true
            recognitionJob?.cancel()
            recognitionJob = null

            // 只有在非停止状态时才调用voskService的停止方法
            if (_recognitionState.value != SpeechRecognizer.RecognitionState.IDLE) {
                _recognitionState.value = SpeechRecognizer.RecognitionState.IDLE
                println("[INFO] 语音识别已停止")
            }
        } catch (e: Exception) {
            println("[ERROR] 停止语音识别异常: ${e.message}")
            e.printStackTrace()
            _recognitionState.value = SpeechRecognizer.RecognitionState.ERROR
        } finally {
            isStoppingRecognition = false
        }
    }

    /**
     * 释放资源
     */
    private var isReleasing = false

    override fun release() {
        // 防止递归调用
        if (isReleasing) {
            println("[DEBUG] 已经在释放资源过程中，避免递归调用")
            return
        }

        try {
            isReleasing = true
            // 直接取消任务，不调用stopRecognition避免潜在的递归
            recognitionJob?.cancel()
            recognitionJob = null

            // 释放SpeexDSP资源
            speexDsp.release()

            // 释放其他资源
            audioPlayer.releasePlayer()
            _recognitionState.value = SpeechRecognizer.RecognitionState.IDLE
            println("[INFO] Vosk资源已释放")
        } catch (e: Exception) {
            println("[WARN] 释放Vosk资源时出错: ${e.message}")
            _recognitionState.value = SpeechRecognizer.RecognitionState.ERROR
        } finally {
            isReleasing = false
        }
    }

    /**
     * 播放音频文件
     * 播放时会自动暂停语音识别，播放完成后恢复原来的状态
     * @param filePath 音频文件路径
     * @return 是否成功开始播放
     */
    fun playAudio(filePath: String): Boolean {
        return audioPlayer.playAudio(filePath)
    }

    /**
     * 停止音频播放
     */
    fun stopPlayback() {
        audioPlayer.stopPlayback()
    }

    /**
     * 获取Vosk语音服务实例
     * 提供给其他组件用于诊断和修复
     */
    fun getVoskService(): VoskSpeechService? {
        return voskService
    }
}