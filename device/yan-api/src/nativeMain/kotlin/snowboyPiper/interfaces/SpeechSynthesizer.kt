@file:OptIn(ExperimentalForeignApi::class)

package snowboyPiper.interfaces

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ShortVar
import kotlinx.coroutines.flow.StateFlow

/**
 * 语音合成接口
 * 负责将文本转换为语音
 */
interface SpeechSynthesizer {
    /**
     * 合成状态
     */
    enum class SynthesisState {
        IDLE,           // 空闲状态
        INITIALIZING,   // 初始化中
        SYNTHESIZING,   // 合成中
        READY,          // 合成完成，准备播放
        ERROR           // 错误状态
    }
    
    /**
     * 当前合成状态
     */
    val synthesisState: StateFlow<SynthesisState>
    
    /**
     * 初始化语音合成器
     * @param modelPath 模型文件路径
     * @param configPath 配置文件路径
     * @param espeakDataPath espeak数据路径
     * @param speakerId 说话人ID
     * @return 初始化是否成功
     */
    fun initialize(modelPath: String, configPath: String, espeakDataPath: String, speakerId: Int = 0): Boolean
    
    /**
     * 合成语音
     * @param text 要合成的文本
     * @return 合成的音频数据，null表示合成失败
     */
    fun synthesize(text: String): Pair<CPointer<ShortVar>?, Int>?
    
    /**
     * 释放资源
     */
    fun release()
}