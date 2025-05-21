package voice.audio.processing

import voice.audio.vad.VoiceActivityDetection
import voice.audio.vad.VoiceActivityDetector
import voice.util.AudioDefaults
import voice.util.LogManager

/**
 * 音频处理工厂类
 * 提供不同类型的音频处理器实例
 */
object AudioProcessingFactory {
    private val logger = LogManager.getLogger("AudioProcessingFactory")
    
    /**
     * 创建基于 WebRTC APM 的音频处理管理器
     * @return AudioProcessingManager 实例
     */
    fun createWebRtcApmProcessor(): AudioProcessingManager {
        logger.info("创建基于 WebRTC APM 的音频处理管理器")
        return AudioProcessingManager()
    }
}