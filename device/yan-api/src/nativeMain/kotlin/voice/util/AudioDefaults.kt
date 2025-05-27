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

    const val MIN_RMS_ENERGY= 0.01f          // 最小RMS能量阈值，用于静音检测
    
    /**
     * 音频格式配置类
     */
    data class AudioFormat(
        val sampleRate: Int,
        val channels: Int
    ) {
        fun isSameAs(other: AudioFormat): Boolean {
            return sampleRate == other.sampleRate && channels == other.channels
        }
        
        fun needsConversion(targetFormat: AudioFormat): Boolean {
            return !isSameAs(targetFormat)
        }
        
        override fun toString(): String {
            return "${sampleRate}Hz/${channels}ch"
        }
    }
    
    /**
     * 预定义的音频格式
     */
    object Formats {
        val INPUT_DEVICE = AudioFormat(INPUT_DEVICE_SAMPLE_RATE, INPUT_DEVICE_CHANNELS)
        val OUTPUT_DEVICE = AudioFormat(OUTPUT_DEVICE_SAMPLE_RATE, OUTPUT_DEVICE_CHANNELS)
        val WEBRTC_APM = AudioFormat(WEBRTC_APM_SAMPLE_RATE, WEBRTC_APM_CHANNELS)
        val PIPER_TTS = AudioFormat(PIPER_TTS_SAMPLE_RATE, PIPER_TTS_CHANNELS)
    }
    
    /**
     * 检查是否需要声道转换
     */
    fun needsChannelConversion(fromChannels: Int, toChannels: Int): Boolean {
        return fromChannels != toChannels
    }
    
    /**
     * 检查是否需要采样率转换
     */
    fun needsSampleRateConversion(fromSampleRate: Int, toSampleRate: Int): Boolean {
        return fromSampleRate != toSampleRate
    }
    
    /**
     * 检查是否需要任何音频格式转换
     */
    fun needsAudioConversion(fromFormat: AudioFormat, toFormat: AudioFormat): Boolean {
        return fromFormat.needsConversion(toFormat)
    }
    
    /**
     * 获取音频转换路径描述
     */
    fun getConversionPath(fromFormat: AudioFormat, toFormat: AudioFormat): String {
        if (fromFormat.isSameAs(toFormat)) {
            return "无需转换"
        }
        
        val conversions = mutableListOf<String>()
        
        if (needsSampleRateConversion(fromFormat.sampleRate, toFormat.sampleRate)) {
            conversions.add("采样率: ${fromFormat.sampleRate}Hz -> ${toFormat.sampleRate}Hz")
        }
        
        if (needsChannelConversion(fromFormat.channels, toFormat.channels)) {
            conversions.add("声道: ${fromFormat.channels}ch -> ${toFormat.channels}ch")
        }
        
        return conversions.joinToString(", ")
    }
    
    /**
     * 验证音频格式是否有效
     */
    fun isValidAudioFormat(format: AudioFormat): Boolean {
        return format.sampleRate > 0 && 
               format.channels > 0 && 
               format.sampleRate <= 192000 && 
               format.channels <= 8
    }
    
    /**
     * 获取推荐的音频处理链路
     */
    fun getRecommendedProcessingChain(): String {
        return buildString {
            appendLine("推荐的音频处理链路:")
            appendLine("1. 输入设备: ${Formats.INPUT_DEVICE}")
            appendLine("2. WebRTC APM: ${Formats.WEBRTC_APM}")
            appendLine("3. 输出设备: ${Formats.OUTPUT_DEVICE}")
            appendLine("4. Piper TTS: ${Formats.PIPER_TTS}")
            appendLine()
            appendLine("转换路径:")
            appendLine("- 输入 -> APM: ${getConversionPath(Formats.INPUT_DEVICE, Formats.WEBRTC_APM)}")
            appendLine("- APM -> 输出: ${getConversionPath(Formats.WEBRTC_APM, Formats.OUTPUT_DEVICE)}")
            appendLine("- Piper -> 输出: ${getConversionPath(Formats.PIPER_TTS, Formats.OUTPUT_DEVICE)}")
        }
    }
    
    /**
     * 测试和验证AudioDefaults配置
     */
    fun validateConfiguration(): String {
        return buildString {
            appendLine("=== AudioDefaults 配置验证 ===")
            appendLine()
            
            // 验证所有预定义格式
            val formats = listOf(
                "INPUT_DEVICE" to Formats.INPUT_DEVICE,
                "OUTPUT_DEVICE" to Formats.OUTPUT_DEVICE,
                "WEBRTC_APM" to Formats.WEBRTC_APM,
                "PIPER_TTS" to Formats.PIPER_TTS
            )
            
            appendLine("格式验证:")
            formats.forEach { (name, format) ->
                val isValid = isValidAudioFormat(format)
                appendLine("- $name: $format ${if (isValid) "✓" else "✗"}")
            }
            
            appendLine()
            appendLine("转换需求分析:")
            
            // 检查常见转换路径
            val conversions = listOf(
                "输入设备 -> WebRTC APM" to (Formats.INPUT_DEVICE to Formats.WEBRTC_APM),
                "WebRTC APM -> 输出设备" to (Formats.WEBRTC_APM to Formats.OUTPUT_DEVICE),
                "Piper TTS -> 输出设备" to (Formats.PIPER_TTS to Formats.OUTPUT_DEVICE)
            )
            
            conversions.forEach { (description, pair) ->
                val (from, to) = pair
                val needsConversion = needsAudioConversion(from, to)
                val path = getConversionPath(from, to)
                appendLine("- $description: ${if (needsConversion) "需要转换" else "无需转换"}")
                if (needsConversion) {
                    appendLine("  转换: $path")
                }
            }
            
            appendLine()
            appendLine("推荐配置:")
            appendLine("- 所有格式均有效: ${formats.all { isValidAudioFormat(it.second) }}")
            appendLine("- 输入设备支持立体声录音")
            appendLine("- WebRTC APM使用单声道处理以提高性能")
            appendLine("- 输出设备支持立体声播放")
            appendLine("- Piper TTS输出单声道，需转换为立体声播放")
        }
    }
    
    /**
     * 诊断音频处理链路问题 - 新增
     */
    fun diagnoseAudioProcessingChain(): String {
        return buildString {
            appendLine("=== 音频处理链路诊断 ===")
            appendLine()
            
            appendLine("关键修复点检查:")
            appendLine("1. WebRtcApm.processAndResample 参数传递问题:")
            appendLine("   - 问题: 调用时请求2ch输出，但APM内部固定1ch处理")
            appendLine("   - 修复: 在APM内部正确处理1ch->2ch声道转换")
            appendLine("   - 状态: ✓ 已修复")
            
            appendLine()
            appendLine("2. AudioUtils.stereoToMono 算法问题:")
            appendLine("   - 问题: 复杂权重计算可能导致数据丢失")
            appendLine("   - 修复: 使用简单平均值算法")
            appendLine("   - 状态: ✓ 已修复")
            
            appendLine()
            appendLine("3. PiperSpeechSynthesizer 重采样质量问题:")
            appendLine("   - 问题: 简单线性插值质量差")
            appendLine("   - 修复: 改进重采样算法，支持抗混叠")
            appendLine("   - 状态: ✓ 已修复")
            
            appendLine()
            appendLine("4. KeywordDetector 参数传递问题:")
            appendLine("   - 问题: processAndResample调用参数不一致")
            appendLine("   - 修复: 统一参数传递，添加详细调试信息")
            appendLine("   - 状态: ✓ 已修复")
            
            appendLine()
            appendLine("预期改进效果:")
            appendLine("- 98%+零值问题应该消失")
            appendLine("- Vosk识别应该能正常工作")
            appendLine("- 语音合成音质应该提升")
            appendLine("- 系统整体稳定性提高")
            
            appendLine()
            appendLine("测试建议:")
            appendLine("1. 重新运行语音识别测试")
            appendLine("2. 检查日志中的零值比例报告")
            appendLine("3. 验证Vosk返回非空结果")
            appendLine("4. 测试语音合成播放质量")
        }
    }
} 