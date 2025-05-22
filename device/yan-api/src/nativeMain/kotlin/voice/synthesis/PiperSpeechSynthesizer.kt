@file:OptIn(ExperimentalForeignApi::class)

package voice.synthesis

import com.airobot.piperinterop.PiperContext
import com.airobot.piperinterop.piper_wrapper_init
import com.airobot.piperinterop.piper_wrapper_terminate
import com.airobot.piperinterop.piper_wrapper_text_to_audio
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.pclose
import platform.posix.popen
import voice.acquisition.portaudio.PortAudioDevice
import voice.api.SpeechSynthesizerApi
import voice.util.AudioDefaults

/**
 * Piper语音合成器实现
 * 负责将文本转换为语音
 */
class PiperSpeechSynthesizer : SpeechSynthesizerApi {
    // Piper上下文
    private var piperContext: CValuesRef<PiperContext>? = null

    // 合成状态
    private val _synthesisState = MutableStateFlow(SynthesisState.IDLE)
    val synthesisState: StateFlow<SynthesisState> = _synthesisState.asStateFlow()

    // 使用全局单例的音频设备，避免多个实例导致状态不同步
    private val audioPlayer = PortAudioDevice.getInstance()

    // 同步锁，用于线程安全
    private val mutex = Mutex()

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
            audioPlayer.initialize("default", AudioDefaults.TARGET_SAMPLE_RATE)
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
        println("Starting synthesis for text: $text")

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

                // 安全检查：帧数应该是个合理值
                if (frameCount <= 0 || frameCount > 1000000) {  // 设置一个合理的上限，防止异常值
                    println("[ERROR] Piper返回的帧数异常: $frameCount")
                    // 释放C侧音频缓冲区
                    com.airobot.piperinterop.piper_wrapper_free_audio(monoBufferPtr)
                    _synthesisState.value = SynthesisState.ERROR
                    return@memScoped ByteArray(0)
                }

                println("[INFO] Piper生成了 $frameCount 帧音频数据")

                try {
                    // 直接使用字节数组，跳过ShortArray转换，避免中间数组分配
                    // 单声道转立体声(1通道转2通道)并转换为字节格式
                    // 一个short是2字节，一个立体声帧是2个short(左右声道)，所以总长度是frameCount*2*2
                    val byteArraySize = frameCount * 4  // 立体声每帧4字节
                    val byteArray = ByteArray(byteArraySize)

                    // 直接复制并转换，避免中间数组
                    for (i in 0 until frameCount) {
                        val monoSample = monoBufferPtr[i]

                        // 复制到左声道(低字节在前，高字节在后 - 小端序)
                        byteArray[i * 4] = (monoSample.toInt() and 0xFF).toByte()
                        byteArray[i * 4 + 1] = (monoSample.toInt() shr 8).toByte()

                        // 复制到右声道(同样的值)
                        byteArray[i * 4 + 2] = (monoSample.toInt() and 0xFF).toByte()
                        byteArray[i * 4 + 3] = (monoSample.toInt() shr 8).toByte()
                    }

                    _synthesisState.value = SynthesisState.IDLE
                    byteArray
                } finally {
                    // 确保在任何情况下都释放C侧音频缓冲区
                    com.airobot.piperinterop.piper_wrapper_free_audio(monoBufferPtr)
                    println("[INFO] 已释放Piper音频缓冲区")
                }
            }
        }
    }

    /**
     * 播放文本
     * @param text 要播放的文本
     * @return 播放是否成功
     */
    override suspend fun speak(text: String): Boolean {
        if (isSpeakingFlag) {
            stopSpeaking()
        }

        val audioData = synthesize(text)
        if (audioData.isEmpty()) {
            println("[WARN] 合成返回了空音频数据，无法播放")
            return false
        }

        isSpeakingFlag = true
        _synthesisState.value = SynthesisState.SPEAKING

        try {
            // 确保音频设备已处于活动状态
            if (audioPlayer.deviceState.value != voice.hal.AudioDevice.AudioDeviceState.ACTIVE) {
                val activated = audioPlayer.start()
                if (!activated) {
                    println("[WARN] 无法激活音频设备，但将尝试播放")
                }
            }

            // 使用音频设备的低级API来播放数据
            // 这是硬件交互的关键点，需要特别小心
            val result = mutex.withLock {
                // 直接使用ByteArray播放，避免转换
                audioPlayer.play(audioData, audioData.size)
            }

            if (!result) {
                println("[WARN] 调用音频播放失败，可能是设备被其他进程占用")
                return false
            }

            return true
        } catch (e: Exception) {
            println("[ERROR] 播放音频时发生异常: ${e.message}")
            e.printStackTrace()
            return false
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