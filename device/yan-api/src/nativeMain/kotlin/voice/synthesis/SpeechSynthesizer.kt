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
import voice.acquisition.portaudio.PortAudioDevice
import voice.util.AudioUtils
import kotlinx.cinterop.memScoped

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

    // 使用全局单例的音频设备，避免多个实例导致状态不同步
    private val audioPlayer = PortAudioDevice.getInstance()
    
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
        // 假设主流程已经初始化并启动了PortAudioDevice，若未启动则尝试启动
        if (audioPlayer.deviceState.value == voice.hal.AudioDevice.AudioDeviceState.IDLE) {
            audioPlayer.initialize("default", 16000)
        }
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

        if (text.isBlank()) {
            println("[ERROR] 输入文本为空")
            _synthesisState.value = SynthesisState.ERROR
            return ByteArray(0)
        }

        _synthesisState.value = SynthesisState.SYNTHESIZING
        println("[INFO] 开始合成文本: \"$text\"")

        return memScoped {
            val audioBufferVar = alloc<CPointerVar<ShortVar>>()
            val audioLengthVar = alloc<IntVar>()

            // 调用piper合成
            val ret = piper_wrapper_text_to_audio(
                context = piperContext,
                text = text,
                audio_buffer = audioBufferVar.ptr,
                audio_length = audioLengthVar.ptr,
                sampleRate = 16000,
                channels = 1  // 先生成单声道
            )

            if (ret < 0) {
                println("[ERROR] 语音合成失败，返回值: $ret")
                _synthesisState.value = SynthesisState.ERROR
                ByteArray(0)
            } else {
                val frameCount = audioLengthVar.value
                val monoBufferPtr = audioBufferVar.value
                if (monoBufferPtr == null) {
                    println("[ERROR] Piper返回的音频缓冲区为空")
                    _synthesisState.value = SynthesisState.ERROR
                    return@memScoped ByteArray(0)
                }

                val monoShortArray = ShortArray(frameCount)
                for (i in 0 until frameCount) {
                    monoShortArray[i] = monoBufferPtr[i]
                }

                // 释放C侧音频缓冲区，防止内存泄漏
                com.airobot.piperinterop.piper_wrapper_free_audio(monoBufferPtr)

                val stereoShortArray = AudioUtils.monoToStereo(monoShortArray)
                val byteArray = AudioUtils.shortArrayToByteArray(stereoShortArray)

                _synthesisState.value = SynthesisState.IDLE
                byteArray
            }
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
        
        try {
            // 将字节数组转换为短整数数组以便播放
            val shortArray = AudioUtils.byteArrayToShortArray(audioData)
            
            // 确保音频设备已处于活动状态
            if (audioPlayer.deviceState.value != voice.hal.AudioDevice.AudioDeviceState.ACTIVE) {
                audioPlayer.start()
            }

            // 播放音频
            val result = audioPlayer.playAudio(shortArray)
            return result
        } finally {
            // 确保无论如何都重置状态
            isSpeakingFlag = false
            _synthesisState.value = SynthesisState.IDLE
        }
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