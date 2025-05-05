@file:OptIn(ExperimentalForeignApi::class)

package com.airobot.device.yanapi.voice.utils

import com.airobot.portaudiointerop.PaDeviceInfo
import com.airobot.portaudiointerop.PaStreamCallbackTimeInfo
import com.airobot.portaudiointerop.PaStreamParameters
import com.airobot.portaudiointerop.Pa_AbortStream
import com.airobot.portaudiointerop.Pa_CloseStream
import com.airobot.portaudiointerop.Pa_GetDefaultInputDevice
import com.airobot.portaudiointerop.Pa_GetDefaultOutputDevice
import com.airobot.portaudiointerop.Pa_GetDeviceCount
import com.airobot.portaudiointerop.Pa_GetDeviceInfo
import com.airobot.portaudiointerop.Pa_GetErrorText
import com.airobot.portaudiointerop.Pa_GetStreamReadAvailable
import com.airobot.portaudiointerop.Pa_GetStreamWriteAvailable
import com.airobot.portaudiointerop.Pa_GetVersionText
import com.airobot.portaudiointerop.Pa_Initialize
import com.airobot.portaudiointerop.Pa_IsStreamActive
import com.airobot.portaudiointerop.Pa_OpenStream
import com.airobot.portaudiointerop.Pa_ReadStream
import com.airobot.portaudiointerop.Pa_Sleep
import com.airobot.portaudiointerop.Pa_StartStream
import com.airobot.portaudiointerop.Pa_StopStream
import com.airobot.portaudiointerop.Pa_Terminate
import com.airobot.portaudiointerop.Pa_WriteStream
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.nativeHeap.alloc
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.F_OK
import platform.posix.access
import platform.posix.getenv
import platform.posix.gettimeofday
import platform.posix.localtime_r
import platform.posix.strftime
import platform.posix.timeval
import platform.posix.tm

// 流指针类型
typealias PaStream = COpaquePointerVar

/**
 * PortAudio接口绑定
 * 提供对PortAudio C API的Kotlin封装
 */
@OptIn(ExperimentalForeignApi::class)
object PortAudio {


    /** 设备信息结构体 */

    /** 初始化PortAudio库 */
    fun initialize(): Int = Pa_Initialize()

    /** 终止PortAudio库 */
    fun terminate(): Int = Pa_Terminate()

    /** 获取版本信息 */
    fun getVersionText(): String? = Pa_GetVersionText()?.toKString()

    /** 获取设备数量 */
    fun getDeviceCount(): Int = Pa_GetDeviceCount()

    /** 获取默认输入设备 */
    fun getDefaultInputDevice(): Int = Pa_GetDefaultInputDevice()

    /** 获取默认输出设备 */
    fun getDefaultOutputDevice(): Int = Pa_GetDefaultOutputDevice()

    /** 获取设备信息 */
    fun getDeviceInfo(device: Int): PaDeviceInfo? {
        val ptr = Pa_GetDeviceInfo(device) ?: return null
        return ptr.pointed
    }

    /** 打开流 */
    fun openStream(
        memScope: MemScope,
        inputParameters: CPointer<PaStreamParameters>?,
        outputParameters: CPointer<PaStreamParameters>?,
        sampleRate: Double,
        framesPerBuffer: UInt,
        streamFlags: UInt,
        streamCallback: CPointer<CFunction<(CPointer<out CPointed>?, CPointer<out CPointed>?, UInt, CPointer<PaStreamCallbackTimeInfo>?, UInt, CPointer<out CPointed>?) -> Int>>?,
        userData: COpaquePointer?
    ): Pair<Int, CPointer<PaStream>?> {
        val streamPtr = memScope.alloc<PaStream>()
        val result = Pa_OpenStream(
            stream = streamPtr.ptr,
            inputParameters = inputParameters,
            outputParameters = outputParameters,
            sampleRate = sampleRate,
            framesPerBuffer = framesPerBuffer,
            streamFlags = streamFlags,
            streamCallback = streamCallback,
            userData = userData
        )
        return Pair(result, streamPtr.ptr)
    }

    /** 关闭流 */
    fun closeStream(stream: CPointer<*>?): Int = Pa_CloseStream(stream)

    /** 启动流 */
    fun startStream(stream: CPointer<*>?): Int = Pa_StartStream(stream)

    /** 停止流 */
    fun stopStream(stream: CPointer<*>?): Int = Pa_StopStream(stream)

    /** 终止流 */
    fun abortStream(stream: CPointer<*>?): Int = Pa_AbortStream(stream)

    /** 流是否活跃 */
    fun isStreamActive(stream: CPointer<*>?): Int = Pa_IsStreamActive(stream)

    /** 获取可读数据数量 */
    fun getStreamReadAvailable(stream: CPointer<*>?): Int = Pa_GetStreamReadAvailable(stream)

    /** 获取可写数据数量 */
    fun getStreamWriteAvailable(stream: CPointer<*>?): Int = Pa_GetStreamWriteAvailable(stream)

    /** 读取流数据 */
    fun readStream(stream: CPointer<*>?, buffer: COpaquePointer?, frames: UInt): Int =
        Pa_ReadStream(stream, buffer, frames)

    /** 写入流数据 */
    fun writeStream(stream: CPointer<*>?, buffer: COpaquePointer?, frames: UInt): Int =
        Pa_WriteStream(stream, buffer, frames)


    /** 获取错误描述 */
    fun getErrorText(errorCode: Int): String? = Pa_GetErrorText(errorCode)?.toKString()

    /**
     * 更新 ~/.asoundrc 为全双工 duplex 配置，防止设备独占
     */
    fun configureAlsaSettings() {
        val homeDir = FileUtils.getHomeDir() ?: return
        val asoundrcPath = "$homeDir/.asoundrc"
        val backupPath = "$homeDir/.asoundrc.bak"

        // 备份原配置
        if (FileUtils.fileExists(asoundrcPath)) {
            println("[INFO] 备份现有 ALSA 配置 -> $backupPath")
            FileUtils.writeToFile(backupPath, FileUtils.readFile(asoundrcPath))
        }

        // 全双工 duplex 配置
        val configContent = buildString {
            append("# 语音助手ALSA配置 - 使用 duplex 全双工，防止设备独占\n")
            append("# 创建于 ${getTimeString()}\n\n")
            append("pcm.duplex {\n")
            append("    type asym\n")
            append("    playback.pcm \"dmix\"\n")
            append("    capture.pcm  \"dsnoop\"\n")
            append("}\n\n")
            append("pcm.!default {\n")
            append("    type plug\n")
            append("    slave.pcm \"duplex\"\n")
            append("}\n\n")
            append("# 禁用蓝牙音频相关配置\n")
            append("defaults.bluealsa.interface \"hci0\"\n")
            append("defaults.bluealsa.device \"00:00:00:00:00:00\"\n")
            append("defaults.bluealsa.profile \"a2dp\"\n")
        }

        // 写入新配置
        FileUtils.writeToFile(asoundrcPath, configContent)
        println("[INFO] 已更新 ~/.asoundrc 为 duplex 配置 (UTF-8)")
    }

    // 获取当前时间字符串，保持原有实现
    private fun getTimeString(): String {
        val timeVal = nativeHeap.alloc<timeval>()
        gettimeofday(timeVal.ptr, null)
        val timer = nativeHeap.alloc<IntVar>()
        timer.value = timeVal.tv_sec
        val timeInfo = nativeHeap.alloc<tm>()
        localtime_r(timer.ptr, timeInfo.ptr)

        val buffer = ByteArray(64)
        strftime(buffer.refTo(0), buffer.size.toUInt(), "%Y-%m-%d %H:%M:%S", timeInfo.ptr)
        return buffer.toKString()
    }

} 