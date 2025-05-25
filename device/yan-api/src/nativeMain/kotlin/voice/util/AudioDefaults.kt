package voice.util

/**
 * 音频配置常量 - 明确定义每个组件的参数
 */
object AudioDefaults {
    // === 硬件设备配置 ===
    const val INPUT_DEVICE_SAMPLE_RATE = 16000    // 输入设备采样率
    const val INPUT_DEVICE_CHANNELS = 2           // 输入设备声道数（双声道）
    
    const val OUTPUT_DEVICE_SAMPLE_RATE = 48000   // 输出设备采样率  
    const val OUTPUT_DEVICE_CHANNELS = 2          // 输出设备声道数（双声道）
    
    // === WebRTC APM 内部处理配置 ===
    const val WEBRTC_APM_SAMPLE_RATE = 16000      // WebRTC APM内部处理采样率
    const val WEBRTC_APM_CHANNELS = 1             // WebRTC APM内部处理声道数（单声道）
    
    // === Piper TTS 配置 ===
    const val PIPER_TTS_SAMPLE_RATE = 22050       // Piper TTS输出采样率
    const val PIPER_TTS_CHANNELS = 1              // Piper TTS输出声道数（单声道）
    
    // === 缓冲区配置 ===
    const val AUDIO_BUFFER_SIZE = 1024            // 音频缓冲区大小
    const val CALLBACK_BUFFER_SIZE = 512          // 回调缓冲区大小
} 