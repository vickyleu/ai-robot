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
    override val synthesisState: StateFlow<SpeechSynthesizer.SynthesisState> = _synthesisState.asStateFlow()
    
    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Default)
    
    /**
     * 初始化语音合成器
     * @param modelPath 模型文件路径
     * @param configPath 配置文件路径
     * @param espeakDataPath espeak数据路径
     * @param speakerId 说话人ID
     * @return 初始化是否成功
     */
    override fun initialize(modelPath: String, configPath: String, espeakDataPath: String, speakerId: Int): Boolean {
        _synthesisState.value = SpeechSynthesizer.SynthesisState.INITIALIZING
        
        // 检查文件是否存在
        scope.launch {
            val checkPiperModelCmd = "test -f $modelPath && echo 'exists' || echo 'not exists'"
            val piperModelExists = VoskSpeechService.executeCommand(checkPiperModelCmd).trim() == "exists"
            if (!piperModelExists) {
                println("[WARN] Piper模型文件不存在: $modelPath")
            }
            
            val checkPiperConfigCmd = "test -f $configPath && echo 'exists' || echo 'not exists'"
            val piperConfigExists = VoskSpeechService.executeCommand(checkPiperConfigCmd).trim() == "exists"
            if (!piperConfigExists) {
                println("[WARN] Piper配置文件不存在: $configPath")
            }
        }
        
        // 初始化Piper语音合成
        println("[INFO] 初始化Piper语音合成，模型路径: $modelPath, 配置路径: $configPath")
        try {
            println("[INFO] 创建Piper语音合成上下文.......")
            piperContext = piper_wrapper_init(
                espeak_data_path = espeakDataPath,
                model_path = modelPath,
                config_path = configPath,
                speaker_id = speakerId
            )
            if (piperContext == null) {
                println("[ERROR] Piper初始化失败")
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
        
        _synthesisState.value = SpeechSynthesizer.SynthesisState.SYNTHESIZING
        
        try {
            // 合成语音
            val audioBufferVar = nativeHeap.allocArray<CPointerVar<ShortVar>>(text.length)
            val audioLengthVar = nativeHeap.alloc<IntVar>()
            val audioData = piper_wrapper_text_to_audio(piperContext, text,audioBufferVar, audioLengthVar.ptr)
            if (audioData < 0) {
                println("[ERROR] 语音合成失败")
                _synthesisState.value = SpeechSynthesizer.SynthesisState.ERROR
                return null
            }
            val audioLength = audioLengthVar.value
            val buffer: CPointer<ShortVar>? = audioBufferVar.pointed.value
            println("[INFO] 语音合成成功，长度: $audioLength 帧")
            _synthesisState.value = SpeechSynthesizer.SynthesisState.READY
            return Pair(buffer, audioLength)
        } catch (e: Exception) {
            println("[ERROR] 语音合成异常: ${e.message}")
            e.printStackTrace()
            _synthesisState.value = SpeechSynthesizer.SynthesisState.ERROR
            return null
        }
    }
    
    /**
     * 释放资源
     */
    override fun release() {
        try {
            piperContext?.let {
                piper_wrapper_terminate(it)
                println("[INFO] Piper资源已释放")
            }
            piperContext = null
            _synthesisState.value = SpeechSynthesizer.SynthesisState.IDLE
        } catch (e: Exception) {
            println("[WARN] 释放Piper资源时出错: ${e.message}")
            _synthesisState.value = SpeechSynthesizer.SynthesisState.ERROR
        }
    }
}