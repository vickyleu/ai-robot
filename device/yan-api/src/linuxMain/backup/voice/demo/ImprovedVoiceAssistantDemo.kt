@file:OptIn(ExperimentalTime::class)

package com.airobot.device.yanapi.voice.demo

import com.airobot.device.yanapi.voice.speech.BasicVoiceAssistant
import com.airobot.device.yanapi.voice.utils.PortAudio
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.time.ExperimentalTime

/**
 * 改进版语音助手演示程序
 * 展示集成AdaptiveNoiseProfiler和AudioFeatureExtractor后的增强型语音处理架构
 */
fun voiceAssistantDemo() = runBlocking {
    println("[INFO] 启动改进版语音助手演示程序...")
    println("[INFO] 此版本集成了AdaptiveNoiseProfiler、AudioFeatureExtractor和BasicAudioProcessor")
    println("[INFO] 提供更好的噪声适应性和更低的误唤醒率")

    // 添加ALSA配置，解决设备独占问题
    PortAudio.configureAlsaSettings()

    // 测试麦克风提示
    println("[INFO] 测试麦克风中...")

    // 创建语音助手实例
    val voiceAssistant = BasicVoiceAssistant()

    // 设置命令处理器
    voiceAssistant.setCommandHandler { command ->
        println("[INFO] 收到命令: $command")

        // 简单的命令处理逻辑
        when {
            command.contains("时间") -> "现在是北京时间18点30分"
            command.contains("天气") -> "今天天气晴朗，气温25度"
            command.contains("你好") || command.contains("你是谁") -> "你好，我是改进版AI语音助手，我能更好地识别唤醒词并减少误触发"
            command.contains("功能") || command.contains("特点") -> "我是改进版语音助手，集成了自适应噪声分析、音频处理和精确特征提取技术，可以更好地适应环境噪声变化并减少误唤醒"
            else -> "对不起，我没有理解你的命令"
        }
    }

    // 设置参数 - 提高灵敏度
    voiceAssistant.setParameters(
        wakewordSensitivity = 0.85f,    // 显著提高唤醒词灵敏度(原为0.65f)
        listeningTimeoutMs = 10000L,    // 延长超时时间(原为8000L)
        soundFeedback = true,           // 保持声音反馈启用
        audioGain = 1.8f,               // 大幅提高音频增益(原为1.15f)
        noiseGateThreshold = 80,        // 降低噪声门限(原为100)
        lowPassCoefficient = 0.75f      // 调整低通滤波系数(原为0.8f)
    )
    // 模型路径
    val wakewordResource = "/usr/local/share/yanshee-model/snowboy/common.res"
//    val wakewordModel = "/usr/local/share/yanshee-model/snowboy/models/snowboy.umdl"
    val wakewordModel = "/usr/local/share/yanshee-model/snowboy/models/xiaodu.pmdl"
    val recognizerModel = "/usr/local/share/yanshee-model/vosk/vosk-model-small-cn-0.22"
    val synthesizerModel = "/usr/local/share/yanshee-model/piper/zh_CN-huayan-medium.onnx"
    val synthesizerConfig = "/usr/local/share/yanshee-model/piper/zh_CN-huayan-medium.onnx.json"
//    val synthesizerModel = "/usr/local/share/yanshee-model/piper/zh_CN-huayan-x_low.onnx"
//    val synthesizerConfig = "/usr/local/share/yanshee-model/piper/zh_CN-huayan-x_low.onnx.json"
    val synthesizerESpeakData = "/usr/local/share/yanshee-model/piper/espeak-ng-data"

    // 初始化语音助手
    val initialized = voiceAssistant.initialize(
        wakewordResource = wakewordResource,
        wakewordModel = wakewordModel,
        recognizerModel = recognizerModel,
        synthesizerModel = synthesizerModel,
        synthesizerConfig = synthesizerConfig,
        synthesizerESpeakDataPath = synthesizerESpeakData
    )

    if (!initialized) {
        println("[ERROR] 语音助手初始化失败，请检查模型文件路径是否正确")
        return@runBlocking
    }

    // 启动语音助手
    val started = voiceAssistant.start()
    if (!started) {
        println("[ERROR] 语音助手启动失败")
        return@runBlocking
    }

    println("[INFO] 改进版语音助手已启动！")
    println("[INFO] 请说\"小度小度\"来唤醒")
    println("[INFO] 该版本具有以下改进：")
    println("[INFO] 1. 集成AdaptiveNoiseProfiler提供动态噪声阈值")
    println("[INFO] 2. 使用AudioFeatureExtractor进行精确特征提取")
    println("[INFO] 3. 集成BasicAudioProcessor进行音频预处理和增强")
    println("[INFO] 4. 在唤醒词检测中使用更严格的多层验证")
    println("[INFO] 5. 按Ctrl+C退出程序")

    // 保持程序运行
    while (true) {
        delay(1000) // 持续运行直到被中断，每秒检查一次
    }
} 