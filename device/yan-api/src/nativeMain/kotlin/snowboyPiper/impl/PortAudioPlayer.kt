@file:OptIn(ExperimentalForeignApi::class, ExperimentalStdlibApi::class, NativeRuntimeApi::class)
@file:Suppress("FunctionName", "unused", "UNUSED_PARAMETER")

package snowboyPiper.impl

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ShortVar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import snowboyPiper.interfaces.AudioDevice
import snowboyPiper.interfaces.AudioPlayer
import snowboyPiper.interfaces.SpeechRecognizer
import kotlin.native.runtime.NativeRuntimeApi

/**
 * PortAudio音频播放器实现
 * 负责播放音频文件，并在播放时暂停语音识别
 */
class PortAudioPlayer(private val speechRecognizer: VoskSpeechRecognizer) : AudioPlayer {

    // 播放状态
    private val _playbackState = MutableStateFlow(AudioPlayer.PlaybackState.IDLE)
    override val playbackState: StateFlow<AudioPlayer.PlaybackState> = _playbackState.asStateFlow()

    // 协程作用域和任务
    private val scope = CoroutineScope(Dispatchers.Default)
    private var playbackJob: Job? = null

    // 保存播放前的识别状态
    private var previousRecognitionState: SpeechRecognizer.RecognitionState =
        SpeechRecognizer.RecognitionState.IDLE

    /**
     * 初始化音频播放器
     * @param deviceName 设备名称
     * @param sampleRate 采样率
     * @return 初始化是否成功
     */
    override fun initialize(
        audioRecordDevice: AudioDevice,
        deviceName: String,
        sampleRate: Int
    ): Boolean {
        if (audioRecordDevice.isInitialized()) {
            _playbackState.value = AudioPlayer.PlaybackState.INITIALIZING
        } else {
            println("[ERROR] PortAudio设备未初始化")
            _playbackState.value = AudioPlayer.PlaybackState.ERROR
            return false
        }
        println("[INFO] 初始化PortAudio音频播放器...")
        try {
            println("[INFO] PortAudio音频播放器初始化成功")
            _playbackState.value = AudioPlayer.PlaybackState.IDLE
            return true
        } catch (e: Exception) {
            println("[ERROR] PortAudio初始化异常: ${e.message}")
            e.printStackTrace()
            _playbackState.value = AudioPlayer.PlaybackState.ERROR
            return false
        }
    }

    /**
     * 播放音频文件
     * @param filePath 音频文件路径
     * @return 是否成功开始播放
     */
    override fun playAudio(filePath: String): Boolean {
        if (_playbackState.value == AudioPlayer.PlaybackState.PLAYING) {
            println("[WARN] 音频播放器已经在播放中")
            return true
        }

        try {
            // 保存当前识别状态
            previousRecognitionState = speechRecognizer.recognitionState.value

            // 如果正在识别，先停止识别
            if (previousRecognitionState == SpeechRecognizer.RecognitionState.LISTENING) {
                println("[INFO] 暂停语音识别以播放音频")
                speechRecognizer.stopRecognition()
            }

            // 启动播放任务
            playbackJob?.cancel()
            playbackJob = scope.launch {
                _playbackState.value = AudioPlayer.PlaybackState.PLAYING
                println("[INFO] 开始播放音频: $filePath")

                // 使用PortAudio播放音频文件
                // 这里使用executeCommand作为临时实现
                val playCommand = "aplay -D plughw:0,0 -f S16_LE -r 48000 -c 1 $filePath"
                val result = VoskSpeechService.executeCommand(playCommand, 30000L)
                println("[INFO] 播放结果: $result")

                // 播放完成后恢复识别状态
                if (previousRecognitionState == SpeechRecognizer.RecognitionState.LISTENING) {
                    println("[INFO] 恢复语音识别")
                    speechRecognizer.startRecognition()
                }

                _playbackState.value = AudioPlayer.PlaybackState.IDLE
                println("[INFO] 音频播放完成")
            }

            return true
        } catch (e: Exception) {
            println("[ERROR] 启动音频播放异常: ${e.message}")
            e.printStackTrace()

            // 恢复识别状态
            if (previousRecognitionState == SpeechRecognizer.RecognitionState.LISTENING) {
                speechRecognizer.startRecognition()
            }

            _playbackState.value = AudioPlayer.PlaybackState.ERROR
            return false
        }
    }

    override fun playAudio(
        buffer: CPointer<ShortVar>,
        frameCount: Int
    ): Int {
        return (speechRecognizer.recordDevice() as? PortAudioDevice)?.playAudio(buffer, frameCount)
            ?: -1
    }

    /**
     * 停止播放
     */
    override fun stopPlayback() {
        try {
            playbackJob?.cancel()
            playbackJob = null

            // 使用系统命令停止播放
            scope.launch {
                VoskSpeechService.executeCommand("pkill -f aplay")
            }

            // 恢复识别状态
            if (previousRecognitionState == SpeechRecognizer.RecognitionState.LISTENING) {
                println("[INFO] 恢复语音识别")
                speechRecognizer.startRecognition()
            }

            _playbackState.value = AudioPlayer.PlaybackState.IDLE
            println("[INFO] 音频播放已停止")
        } catch (e: Exception) {
            println("[ERROR] 停止音频播放异常: ${e.message}")
            e.printStackTrace()
            _playbackState.value = AudioPlayer.PlaybackState.ERROR
        }
    }

    /**
     * 释放资源
     */
    override fun releasePlayer() {
        try {
            stopPlayback()
            // 这里可以添加PortAudio资源释放代码

            _playbackState.value = AudioPlayer.PlaybackState.IDLE
            println("[INFO] PortAudio资源已释放")
        } catch (e: Exception) {
            println("[WARN] 释放PortAudio资源时出错: ${e.message}")
            _playbackState.value = AudioPlayer.PlaybackState.ERROR
        }
    }
}