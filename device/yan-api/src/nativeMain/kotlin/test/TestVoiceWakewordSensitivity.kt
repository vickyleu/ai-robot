@file:OptIn(ExperimentalTime::class)

package com.airobot.device.yanapi.test

import com.airobot.device.yanapi.voice.analysis.BasicAudioAnalyzer
import com.airobot.device.yanapi.voice.audio.BasicAudioProcessor
import com.airobot.device.yanapi.voice.interfaces.WakewordDetector
import com.airobot.device.yanapi.voice.wakeword.SnowboyWakewordDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.FILE
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fclose
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * 测试Snowboy唤醒词检测灵敏度
 * 
 * 这个测试程序用于验证改进后的唤醒词检测灵敏度
 * 1. 从WAV文件读取测试音频数据
 * 2. 模拟音频数据帧发送到唤醒词检测器
 * 3. 记录检测成功率和响应时间
 */
@OptIn(ExperimentalForeignApi::class)
fun main() {
    println("------------------------------------------")
    println("唤醒词检测灵敏度测试")
    println("------------------------------------------")
    
    // 初始化音频分析器和处理器
    val audioAnalyzer = BasicAudioAnalyzer()
    val audioProcessor = BasicAudioProcessor()
    audioProcessor.setGain(1.5f) // 设置增益
    audioProcessor.setNoiseGate(100) // 设置噪声门限
    
    // 初始化唤醒词检测器
    val wakewordDetector = SnowboyWakewordDetector(audioAnalyzer)
    val initResult = wakewordDetector.initialize(
        resourcePath = "models/snowboy/common.res",
        modelPath = "models/snowboy/xiaodu.pmdl",
        sensitivity = 0.99f // 使用较高灵敏度
    )
    
    if (!initResult) {
        println("[ERROR] 唤醒词检测器初始化失败")
        return
    }
    
    println("[INFO] 唤醒词检测器初始化成功")
    
    // 设置检测回调
    var detectionCount = 0
    var detectionStartTime = 0L
    wakewordDetector.setDetectionCallback {
        if (it == WakewordDetector.DetectionResult.WAKEWORD_DETECTED) {
            val responseTime = Clock.System.now().toEpochMilliseconds() - detectionStartTime
            detectionCount++
            println("[SUCCESS] 唤醒词检测成功! 响应时间: ${responseTime}ms, 检测计数: $detectionCount")
        }
    }
    
    // 创建测试音频数据帧
    val frameSize = 1600 // 100ms @ 16kHz
    val testFiles = listOf(
        "test_data/xiaodu_normal.wav", 
        "test_data/xiaodu_soft.wav", 
        "test_data/xiaodu_distant.wav",
        "test_data/xiaodu_noisy.wav"
    )
    
    // 运行测试
    runBlocking {
        for (testFile in testFiles) {
            println("\n[TEST] 测试文件: $testFile")
            
            // 读取WAV文件
            val wavData = readWavFile(testFile)
            if (wavData.isEmpty()) {
                println("[ERROR] 无法读取测试文件: $testFile")
                continue
            }
            
            println("[INFO] 成功读取测试文件，大小: ${wavData.size} bytes")
            
            // 将WAV数据转换为短整型数组（跳过44字节的WAV头）
            val headerSize = 44
            val samples = (wavData.size - headerSize) / 2
            val audioData = ShortArray(samples)
            
            for (i in 0 until samples) {
                val pos = headerSize + i * 2
                val b1 = wavData[pos].toInt() and 0xFF
                val b2 = wavData[pos + 1].toInt() and 0xFF
                audioData[i] = ((b2 shl 8) or b1).toShort()
            }
            
            // 重置检测器状态
            detectionCount = 0
            detectionStartTime = Clock.System.now().toEpochMilliseconds()
            
            // 分帧处理音频数据
            val totalFrames = audioData.size / frameSize
            println("[INFO] 开始处理音频，共 $totalFrames 帧")
            
            for (frame in 0 until totalFrames) {
                val startIndex = frame * frameSize
                val endIndex = minOf(startIndex + frameSize, audioData.size)
                val frameData = audioData.copyOfRange(startIndex, endIndex)
                
                // 处理音频帧
                val processedData = audioProcessor.processAudio(frameData)
                
                // 检测唤醒词
                val result = wakewordDetector.detect(processedData, processedData.size)
                
                // 延迟一下，模拟实时流
                delay(15) // 15ms延迟，比实时略快
                
                // 打印进度
                if (frame % 10 == 0) {
                    print("\r[PROGRESS] 处理进度: ${frame * 100 / totalFrames}%")
                }
            }
            
            println("\r[FINISHED] 处理完成: $testFile, 检测次数: $detectionCount, 总帧数: $totalFrames")
        }
        
        // 释放资源
        wakewordDetector.release()
        println("\n[INFO] 测试完成")
    }
}

/**
 * 从文件读取WAV数据
 */
@OptIn(ExperimentalForeignApi::class)
fun readWavFile(filename: String): ByteArray {
    val file = fopen(filename, "rb") ?: return ByteArray(0)
    
    try {
        // 获取文件大小
        fseek(file, 0, SEEK_END)
        val fileSize = ftell(file)
        fseek(file, 0, SEEK_SET)
        
        // 分配内存并读取文件
        val buffer = nativeHeap.allocArray<ByteVar>(fileSize)
        val bytesRead = fread(buffer, 1u, fileSize.toUInt(), file)
        
        if (bytesRead <= 0u) {
            return ByteArray(0)
        }
        
        // 转换为Kotlin字节数组
        return buffer.readBytes(bytesRead.toInt())
    } finally {
        fclose(file)
    }
}

// POSIX文件操作函数
@OptIn(ExperimentalForeignApi::class)
fun fseek(file: CPointer<FILE>, offset: Int, whence: Int): Int {
    return platform.posix.fseek(file, offset, whence)
}

@OptIn(ExperimentalForeignApi::class)
fun ftell(file: CPointer<FILE>): Int {
    return platform.posix.ftell(file)
}

// 常量定义
const val SEEK_SET = 0
const val SEEK_END = 2 