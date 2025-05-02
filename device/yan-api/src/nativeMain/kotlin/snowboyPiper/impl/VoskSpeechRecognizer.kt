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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import snowboyPiper.interfaces.AudioDevice
import snowboyPiper.interfaces.AudioPlayer
import snowboyPiper.interfaces.SpeechRecognizer

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
    override val recognitionState: StateFlow<SpeechRecognizer.RecognitionState> = _recognitionState.asStateFlow()
    
    // 识别结果
    private val _recognitionText = MutableStateFlow<String?>(null)
    override val recognitionText: StateFlow<String?> = _recognitionText.asStateFlow()
    // 识别结果
    private val _recognitionResult = MutableStateFlow<RecognitionResult?>(null)
    val recognitionResult: StateFlow<RecognitionResult?> = _recognitionResult

    // 协程作用域和任务
    private val scope = CoroutineScope(Dispatchers.Default)
    private var recognitionJob: Job? = null

    fun recordDevice(): AudioDevice{
        return audioRecordDevice
    }

    fun playerDevice(): AudioPlayer{
        return audioPlayer
    }


    /**
     * 初始化语音识别器
     * @param modelPath 模型文件路径
     * @param deviceName 设备名称
     * @param sampleRate 采样率
     * @param micVolume 麦克风音量
     * @return 初始化是否成功
     */
    override fun initialize(audioRecordDevice: AudioDevice,modelPath: String, deviceName: String, sampleRate: Int, micVolume: Int): Boolean {
        _recognitionState.value = SpeechRecognizer.RecognitionState.INITIALIZING
        
        println("[INFO] 初始化Vosk语音识别...")
        try {
            if (!voskService.initialize(deviceName, modelPath, sampleRate, micVolume)) {
                println("[ERROR] Vosk语音识别初始化失败")
                _recognitionState.value = SpeechRecognizer.RecognitionState.ERROR
                return false
            }
            println("[INFO] Vosk语音识别初始化成功")
            
            // 初始化音频播放器
            if (!audioPlayer.initialize(audioRecordDevice,deviceName, 48000)) {
                println("[ERROR] 音频播放器初始化失败")
                _recognitionState.value = SpeechRecognizer.RecognitionState.ERROR
                return false
            }
            println("[INFO] 音频播放器初始化成功")
            
            // 监听语音服务的状态变化
            scope.launch {
                voskService.recognitionState.collectLatest { state ->
                    // 将SpeechService的状态映射到SpeechRecognizer的状态
                    _recognitionState.value = when(state) {
                        snowboyPiper.interfaces.SpeechService.RecognitionState.IDLE -> SpeechRecognizer.RecognitionState.IDLE
                        snowboyPiper.interfaces.SpeechService.RecognitionState.INITIALIZING -> SpeechRecognizer.RecognitionState.INITIALIZING
                        snowboyPiper.interfaces.SpeechService.RecognitionState.LISTENING -> SpeechRecognizer.RecognitionState.LISTENING
                        snowboyPiper.interfaces.SpeechService.RecognitionState.PROCESSING -> SpeechRecognizer.RecognitionState.PROCESSING
                        snowboyPiper.interfaces.SpeechService.RecognitionState.ERROR -> SpeechRecognizer.RecognitionState.ERROR
                    }
                }
            }
            
            // 监听语音服务的识别结果
            scope.launch {
                voskService.recognitionText.collectLatest { text ->
                    _recognitionText.value = text
                }
            }
            
            _recognitionState.value = SpeechRecognizer.RecognitionState.IDLE
            return true
        } catch (e: Exception) {
            println("[ERROR] Vosk初始化异常: ${e.message}")
            e.printStackTrace()
            _recognitionState.value = SpeechRecognizer.RecognitionState.ERROR
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
                        kotlinx.coroutines.delay(100) // 短暂延迟，避免CPU占用过高
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
                    // 处理完成后回到空闲状态
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
    override fun stopRecognition() {
        try {
            recognitionJob?.cancel()
            recognitionJob = null
            
            voskService.stopRecognition()
            _recognitionState.value = SpeechRecognizer.RecognitionState.IDLE
            println("[INFO] 语音识别已停止")
        } catch (e: Exception) {
            println("[ERROR] 停止语音识别异常: ${e.message}")
            e.printStackTrace()
            _recognitionState.value = SpeechRecognizer.RecognitionState.ERROR
        }
    }
    
    /**
     * 处理音频数据
     * @param audioData 音频数据
     * @return 是否成功处理
     */
    override fun processAudio(audioData: ShortArray): Boolean {
        if (_recognitionState.value != SpeechRecognizer.RecognitionState.LISTENING) {
            return false
        }
        
        try {
            return voskService.processAudio(audioData)
        } catch (e: Exception) {
            println("[ERROR] 处理音频数据异常: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
    
    /**
     * 释放资源
     */
    override fun release() {
        try {
            stopRecognition()
            voskService.release()
            audioPlayer.release()
            _recognitionState.value = SpeechRecognizer.RecognitionState.IDLE
            println("[INFO] Vosk资源已释放")
        } catch (e: Exception) {
            println("[WARN] 释放Vosk资源时出错: ${e.message}")
            _recognitionState.value = SpeechRecognizer.RecognitionState.ERROR
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
}