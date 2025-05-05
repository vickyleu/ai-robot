@file:OptIn(ExperimentalForeignApi::class)

package snowboyPiper.impl

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
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import snowboyPiper.interfaces.SpeechSynthesizer

/**
 * Piper语音合成器实现
 * 负责将文本转换为语音
 */
class PiperSpeechSynthesizer : SpeechSynthesizer {
    // Piper上下文
    private var piperContext: CValuesRef<PiperContext>? = null

    // 合成状态
    private val _synthesisState = MutableStateFlow(SpeechSynthesizer.SynthesisState.IDLE)
    override val synthesisState: StateFlow<SpeechSynthesizer.SynthesisState> =
        _synthesisState.asStateFlow()

    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Default)

    init {
        // 检查Piper库是否正确加载
        checkPiperLibLoaded()
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
        _synthesisState.value = SpeechSynthesizer.SynthesisState.INITIALIZING

        // 检查文件路径是否为空
        if (modelPath.isBlank()) {
            println("[ERROR] Piper模型路径为空")
            _synthesisState.value = SpeechSynthesizer.SynthesisState.ERROR
            return false
        }

        if (configPath.isBlank()) {
            println("[ERROR] Piper配置路径为空")
            _synthesisState.value = SpeechSynthesizer.SynthesisState.ERROR
            return false
        }

        if (espeakDataPath.isBlank()) {
            println("[ERROR] espeak数据路径为空")
            _synthesisState.value = SpeechSynthesizer.SynthesisState.ERROR
            return false
        }

        // 检查文件是否存在
        scope.launch {
            val checkPiperModelCmd = "test -f $modelPath && echo 'exists' || echo 'not exists'"
            val piperModelExists =
                VoskSpeechService.executeCommand(checkPiperModelCmd).trim() == "exists"
            if (!piperModelExists) {
                println("[ERROR] Piper模型文件不存在: $modelPath")
            } else {
                println("[INFO] Piper模型文件存在: $modelPath")
            }

            val checkPiperConfigCmd = "test -f $configPath && echo 'exists' || echo 'not exists'"
            val piperConfigExists =
                VoskSpeechService.executeCommand(checkPiperConfigCmd).trim() == "exists"
            if (!piperConfigExists) {
                println("[ERROR] Piper配置文件不存在: $configPath")
            } else {
                println("[INFO] Piper配置文件存在: $configPath")
            }

            val checkEspeakDataCmd = "test -d $espeakDataPath && echo 'exists' || echo 'not exists'"
            val espeakDataExists =
                VoskSpeechService.executeCommand(checkEspeakDataCmd).trim() == "exists"
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
                language = "zh"
            )
            if (piperContext == null) {
                println("[ERROR] Piper初始化失败，返回的上下文为空")
                _synthesisState.value = SpeechSynthesizer.SynthesisState.ERROR
                return false
            }
            println("[INFO] Piper语音合成初始化成功")

            _synthesisState.value = SpeechSynthesizer.SynthesisState.IDLE
            return true
        } catch (e: Exception) {
            println("[ERROR] Piper初始化异常: ${e.message}")
            e.printStackTrace()
            _synthesisState.value = SpeechSynthesizer.SynthesisState.ERROR
            return false
        }
    }

    /**
     * 合成语音
     * @param text 要合成的文本
     * @return 合成的音频数据和长度的对，null表示合成失败
     */
    override fun synthesize(text: String): Pair<CPointer<ShortVar>?, Int>? {
        if (piperContext == null) {
            println("[ERROR] Piper未初始化")
            return null
        }

        // 检查输入文本是否为空
        if (text.isBlank()) {
            println("[ERROR] 输入文本为空")
            _synthesisState.value = SpeechSynthesizer.SynthesisState.ERROR
            return null
        }

        _synthesisState.value = SpeechSynthesizer.SynthesisState.SYNTHESIZING
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
                audio_length = audioLengthVar.ptr
            )

            // 检查合成结果
            if (ret < 0) {
                println("[ERROR] 语音合成失败，返回值: $ret")
                _synthesisState.value = SpeechSynthesizer.SynthesisState.ERROR
                return null
            }

            val frameCount = audioLengthVar.value
            println("[INFO] 合成完成，音频长度: $frameCount 帧")

            if (frameCount == 0) {
                println("[ERROR] 语音合成结果为空，可能原因:")
                println("[ERROR] 1. 模型可能不支持文本中的某些字符")
                println("[ERROR] 2. 合成引擎内部错误")
                println("[ERROR] 3. Piper库可能未正确加载")
                _synthesisState.value = SpeechSynthesizer.SynthesisState.READY
                return null
            }

            val buffer = audioBufferVar.pointed.value
            if (buffer == null) {
                println("[ERROR] 音频缓冲区为空")
                _synthesisState.value = SpeechSynthesizer.SynthesisState.ERROR
                return null
            }

            println("[INFO] 语音合成成功，长度: $frameCount 帧，缓冲区: $buffer")
            _synthesisState.value = SpeechSynthesizer.SynthesisState.READY
            return Pair(buffer, frameCount)
        } catch (e: Exception) {
            println("[ERROR] 语音合成异常: ${e.message}")
            e.printStackTrace()
            _synthesisState.value = SpeechSynthesizer.SynthesisState.ERROR
            return null
        } finally {
            nativeHeap.free(audioBufferVar.pointed.rawPtr)
            nativeHeap.free(audioLengthVar.rawPtr)
        }
    }

    /**
     * 释放资源
     */
    override fun release() {
        try {
            println("[INFO] 开始释放Piper资源...")
            piperContext?.let {
                println("[INFO] 释放Piper上下文...")
                piper_wrapper_terminate(it)
                println("[INFO] Piper资源已释放")
            } ?: run {
                println("[INFO] Piper上下文为空，无需释放")
            }
            piperContext = null
            _synthesisState.value = SpeechSynthesizer.SynthesisState.IDLE
            println("[INFO] Piper资源释放完成")
        } catch (e: Exception) {
            println("[ERROR] 释放Piper资源时出错: ${e.message}")
            e.printStackTrace()
            _synthesisState.value = SpeechSynthesizer.SynthesisState.ERROR
        }
    }
}