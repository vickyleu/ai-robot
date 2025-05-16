package snowboyPiper.impl

import snowboyPiper.config.VoiceAssistantConfig
import snowboyPiper.interfaces.VoiceStateManager
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class VoiceStateManagerImpl(private val config: VoiceAssistantConfig) : VoiceStateManager {

    private var _isSpeaking = false
    override val isSpeaking: Boolean get() = _isSpeaking

    private var _speechStarted = false
    override val speechStarted: Boolean get() = _speechStarted

    override val silenceFramesThreshold: Int
        get() = config.silenceFramesThreshold

    private var _speechBufferStarted = false
    override val speechBufferStarted: Boolean get() = _speechBufferStarted

    private var _silenceFrames = 0
    override val silenceFrames: Int get() = _silenceFrames

    private var _lastSpeechDetectedTime = 0L
    override val lastSpeechDetectedTime: Long get() = _lastSpeechDetectedTime

    override fun processVoiceActivity(hasVoice: Boolean, isCommandState: Boolean): Boolean {
        val stateChanged = if (hasVoice) {
            val wasNotSpeaking = !_isSpeaking
            _isSpeaking = true
            _silenceFrames = 0
            _speechStarted = true

            // 更新最后检测到语音的时间
            if (isCommandState) {
                _lastSpeechDetectedTime = Clock.System.now().toEpochMilliseconds()
            }

            wasNotSpeaking
        } else if (_isSpeaking) {
            // 增加安静帧计数
            _silenceFrames++
            false
        } else {
            false
        }

        return stateChanged
    }

    override fun isSilenceThresholdReached(silenceThreshold: Int): Boolean {
        return _silenceFrames > silenceThreshold && _isSpeaking
    }

    override fun markSpeechStopped() {
        _isSpeaking = false
        _speechBufferStarted = false
    }

    override fun reset() {
        _isSpeaking = false
        _speechStarted = false
        _speechBufferStarted = false
        _silenceFrames = 0
        _lastSpeechDetectedTime = Clock.System.now().toEpochMilliseconds()
    }

    /**
     * 标记语音缓冲开始
     */
    fun startSpeechBuffer() {
        _speechBufferStarted = true
    }
}