@file:OptIn(ExperimentalForeignApi::class)

package voice.audio.processing

import kotlinx.cinterop.ExperimentalForeignApi
import voice.util.LogManager
import voice.util.AudioDefaults
import kotlin.concurrent.AtomicReference

/**
 * WebRTC APM 单例
 * 提供 WebRTC 音频处理模块的单例访问
 */
object WebRtcApmSingleton {
    private val logger = LogManager.getLogger("WebRtcApmSingleton")
    private val instanceRef = AtomicReference<WebRtcApm?>(null)
    
    /**
     * 获取 WebRTC APM 处理器实例
     * @param sampleRate 采样率
     * @param channels 通道数
     * @param recreateIfNeeded 如果参数不匹配，是否重新创建
     * @return WebRTC 音频处理器
     */
    fun getInstance(
        sampleRate: Int = AudioDefaults.TARGET_SAMPLE_RATE,
        channels: Int = 1, // WebRTC APM 通常使用单声道
        recreateIfNeeded: Boolean = false
    ): WebRtcApm? {
        val currentInstance = instanceRef.value
        
        // 检查现有处理器或创建新处理器
        if (currentInstance == null || recreateIfNeeded) {
            // 如果有现有实例，先释放资源
            currentInstance?.release()
            
            // 创建新的处理器
            val newApm = WebRtcApm()
            if (newApm.initialize(sampleRate, channels)) {
                logger.info("创建新的 WebRTC APM 处理器：采样率=${sampleRate}, 声道数=${channels}")
                instanceRef.value = newApm
                return newApm
            } else {
                logger.error("WebRTC APM 初始化失败")
                return null
            }
        }
        
        return currentInstance
    }
    
    /**
     * 处理音频数据
     * @param audioData 输入音频数据（短整型数组）
     * @return 处理后的音频数据
     */
    fun processFrame(audioData: ShortArray): ShortArray {
        val apm = getInstance() ?: return audioData
        return apm.processFrame(audioData)
    }
    
    /**
     * 检查是否检测到语音
     * @return 是否检测到语音
     */
    fun isVoiceDetected(): Boolean {
        val apm = getInstance() ?: return false
        return apm.isVoiceDetected()
    }
    
    /**
     * 释放资源
     */
    fun release() {
        val currentInstance = instanceRef.value
        currentInstance?.release()
        instanceRef.value = null
        logger.info("WebRTC APM 资源已释放")
    }
}
