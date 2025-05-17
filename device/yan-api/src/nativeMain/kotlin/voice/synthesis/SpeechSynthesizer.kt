@file:OptIn(ExperimentalForeignApi::class)

package voice.synthesis

import com.airobot.piperinterop.PiperContext
import com.airobot.piperinterop.piper_wrapper_init
import com.airobot.piperinterop.piper_wrapper_terminate
import com.airobot.piperinterop.piper_wrapper_text_to_audio
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.pclose
import platform.posix.popen
import voice.api.synthesis.ISpeechSynthesizer
import voice.hal.PortAudioDevice

/**
 * Piper语音合成器实现
 * 负责将文本转换为语音
 */
class PiperSpeechSynthesizer : ISpeechSynthesizer {
    // Piper上下文
    private var piperContext: CValuesRef<PiperContext>? = null

    // 合成状态
    private val _synthesisState = MutableStateFlow(SynthesisState.IDLE)
    val synthesisState: StateFlow<SynthesisState> = _synthesisState.asStateFlow()

    // 音频播放器
    private val audioPlayer = PortAudioDevice()
    
    // 是否正在播放
    private var isSpeakingFlag = false

    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Default)

    // 枚举内部状态
    enum class SynthesisState {
        IDLE,
        INITIALIZING,
        SYNTHESIZING,
        SPEAKING,
        ERROR
    }

    init {
        // 检查Piper库是否正确加载
        checkPiperLibLoaded()
        // 初始化音频播放器
        audioPlayer.initialize("default", 16000)
    }

    /**
     * 检查Piper库是否正确加载
     */
    private fun checkPiperLibLoaded() {
        try {
            // 获取库的symbol，如果能获取到说明库已加载
            val piperVersion = "Unknown" // 这里可以添加获取piper版本的方法，如果有的话
            println("[INFO] Piper库已加载，版本: $piperVersion")
        } catch (e: Exception) {
            println("[ERROR] Piper库加载失败: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 初始化语音合成器
     * @param modelPath 模型文件路径
     * @param configPath 配置文件路径
     * @param espeakDataPath espeak数据路径
     * @param speakerId 说话人ID
     * @return 初始化是否成功
     */
    override fun initialize(
        modelPath: String,
        configPath: String,
        espeakDataPath: String,
        speakerId: Int
    ): Boolean {
        _synthesisState.value = SynthesisState.INITIALIZING

        // 检查文件路径是否为空
        if (modelPath.isBlank()) {
            println("[ERROR] Piper模型路径为空")
            _synthesisState.value = SynthesisState.ERROR
            return false
        }

        if (configPath.isBlank()) {
            println("[ERROR] Piper配置路径为空")
            _synthesisState.value = SynthesisState.ERROR
            return false
        }

        if (espeakDataPath.isBlank()) {
            println("[ERROR] espeak数据路径为空")
            _synthesisState.value = SynthesisState.ERROR
            return false
        }

        // 检查文件是否存在
        scope.launch {
            val piperModelExists = checkFileExists(modelPath)
            if (!piperModelExists) {
                println("[ERROR] Piper模型文件不存在: $modelPath")
            } else {
                println("[INFO] Piper模型文件存在: $modelPath")
            }

            val piperConfigExists = checkFileExists(configPath)
            if (!piperConfigExists) {
                println("[ERROR] Piper配置文件不存在: $configPath")
            } else {
                println("[INFO] Piper配置文件存在: $configPath")
            }

            val espeakDataExists = checkDirectoryExists(espeakDataPath)
            if (!espeakDataExists) {
                println("[ERROR] espeak数据目录不存在: $espeakDataPath")
            } else {
                println("[INFO] espeak数据目录存在: $espeakDataPath")
            }
        }

        // 初始化Piper语音合成
        println("[INFO] 初始化Piper语音合成，模型路径: $modelPath, 配置路径: $configPath, espeak数据路径: $espeakDataPath, 说话人ID: $speakerId")
        try {
            println("[INFO] 创建Piper语音合成上下文.......")
            piperContext = piper_wrapper_init(
                espeak_data_path = espeakDataPath,
                model_path = modelPath,
                config_path = configPath,
                speaker_id = speakerId,
                language = "cmn"
            )
            if (piperContext == null) {
                println("[ERROR] Piper初始化失败，返回的上下文为空")
                _synthesisState.value = SynthesisState.ERROR
                return false
            }
            println("[INFO] Piper语音合成初始化成功")

            _synthesisState.value = SynthesisState.IDLE
            return true
        } catch (e: Exception) {
            println("[ERROR] Piper初始化异常: ${e.message}")
            e.printStackTrace()
            _synthesisState.value = SynthesisState.ERROR
            return false
        }
    }

    /**
     * 检查文件是否存在
     */
    private fun checkFileExists(path: String): Boolean {
        val file = fopen(path, "r")
        val exists = file != null
        if (file != null) {
            fclose(file)
        }
        return exists
    }
    
    /**
     * 检查目录是否存在
     */
    private fun checkDirectoryExists(path: String): Boolean {
        val process = popen("test -d \"$path\" && echo 1 || echo 0", "r")
        val buffer = ByteArray(2)
        fread(buffer.refTo(0), 1u, 1u, process)
        val result = buffer[0].toInt().toChar() == '1'
        pclose(process)
        return result
    }

    /**
     * 合成语音
     * @param text 要合成的文本
     * @param outputWav 是否输出wav格式(否则输出raw PCM)
     * @return 合成的音频数据，失败返回空数组
     */
    override fun synthesize(text: String, outputWav: Boolean): ByteArray {
        if (piperContext == null) {
            println("[ERROR] Piper未初始化")
            return ByteArray(0)
        }

        // 检查输入文本是否为空
        if (text.isBlank()) {
            println("[ERROR] 输入文本为空")
            _synthesisState.value = SynthesisState.ERROR
            return ByteArray(0)
        }

        _synthesisState.value = SynthesisState.SYNTHESIZING
        println("[INFO] 开始合成文本: \"$text\"")
        // 合成语音
        val audioBufferVar = nativeHeap.allocArray<CPointerVar<ShortVar>>(text.length)
        val audioLengthVar = nativeHeap.alloc<IntVar>()
        try {
            // 调用piper合成
            println("[INFO] 调用Piper合成，文本长度: ${text.length}")
            val ret = piper_wrapper_text_to_audio(
                context = piperContext,
                text = text,
                audio_buffer = audioBufferVar,
                audio_length = audioLengthVar.ptr,
                sampleRate = 48000,
                channels = 1
            )

            // 检查合成结果
            if (ret < 0) {
                println("[ERROR] 语音合成失败，返回值: $ret")
                _synthesisState.value = SynthesisState.ERROR
                return ByteArray(0)
            }

            val frameCount = audioLengthVar.value
            println("[INFO] 合成完成，音频长度: $frameCount 帧")
            
            // 将CPointer<ShortVar>转换为ByteArray
            val audioBuffer = audioBufferVar[0]
            val byteArray = ByteArray(frameCount * 2) // 16位PCM，每帧2字节
            
            for (i in 0 until frameCount) {
                val sampleValue = audioBuffer!![i]
                byteArray[i * 2] = (sampleValue.toInt() and 0xFF).toByte() // 低字节
                byteArray[i * 2 + 1] = ((sampleValue.toInt() shr 8) and 0xFF).toByte() // 高字节
            }
            
            _synthesisState.value = SynthesisState.IDLE
            return byteArray
        } catch (e: Exception) {
            println("[ERROR] 语音合成异常: ${e.message}")
            e.printStackTrace()
            _synthesisState.value = SynthesisState.ERROR
            return ByteArray(0)
        }
    }
    
    /**
     * 播放文本
     * @param text 要播放的文本
     * @return 播放是否成功
     */
    override fun speak(text: String): Boolean {
        if (isSpeakingFlag) {
            stopSpeaking()
        }
        
        val audioData = synthesize(text)
        if (audioData.isEmpty()) {
            return false
        }
        
        isSpeakingFlag = true
        _synthesisState.value = SynthesisState.SPEAKING
        
        // 将字节数组转换为短整数数组以便播放
        val shortArray = ShortArray(audioData.size / 2)
        for (i in shortArray.indices) {
            val lowByte = audioData[i * 2].toInt() and 0xFF
            val highByte = audioData[i * 2 + 1].toInt() and 0xFF
            shortArray[i] = ((highByte shl 8) or lowByte).toShort()
        }
        
        // 播放音频
        val result = audioPlayer.playAudio(shortArray)
        
        isSpeakingFlag = false
        _synthesisState.value = SynthesisState.IDLE
        
        return result
    }
    
    /**
     * 停止播放
     */
    override fun stopSpeaking() {
        if (isSpeakingFlag) {
            audioPlayer.stopPlayback()
            isSpeakingFlag = false
            _synthesisState.value = SynthesisState.IDLE
        }
    }
    
    /**
     * 是否正在播放
     */
    override fun isSpeaking(): Boolean {
        return isSpeakingFlag
    }

    /**
     * 释放资源
     */
    override fun release() {
        println("[INFO] 释放Piper语音合成器资源")
        try {
            if (isSpeakingFlag) {
                stopSpeaking()
            }
            
            piper_wrapper_terminate(piperContext)
            piperContext = null
            audioPlayer.release()
            _synthesisState.value = SynthesisState.IDLE
        } catch (e: Exception) {
            println("[ERROR] 释放Piper资源异常: ${e.message}")
            _synthesisState.value = SynthesisState.ERROR
        }
    }
}