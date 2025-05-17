@file:OptIn(ExperimentalForeignApi::class)

package voice.hal

import com.airobot.portaudiointerop.Pa_GetDeviceCount
import com.airobot.portaudiointerop.Pa_GetDeviceInfo
import com.airobot.portaudiointerop.Pa_GetHostApiInfo
import com.airobot.portaudiointerop.paALSA
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import platform.posix.F_OK
import platform.posix.access
import platform.posix.getenv
import platform.posix.system
import voice.util.LogManager

/**
 * Linux音频设备选择器
 * 提供对Linux平台音频设备的选择和优化
 */
class LinuxAudioDeviceSelector {
    private val logger = LogManager.getLogger("LinuxAudioDeviceSelector")
    
    // 关键字列表，按优先级排序
    private val recordingDeviceKeywords = listOf(
        "USB", "Mic", "MIC", "Camera", "Microphone", "Webcam", "WEBCAM", "airpods", "AirPods", "headset", "Headset"
    )
    
    private val playbackDeviceKeywords = listOf(
        "Speaker", "SPEAKER", "Headphone", "HEADPHONE", "airpods", "AirPods", "headset", "Headset", "Line Out"
    )
    
    // 特定ALSA设备路径
    private val alasDevicePaths = mapOf(
        "default" to "default",
        "usb_mic" to "plughw:1,0",
        "internal_mic" to "plughw:0,0",
        "hdmi" to "plughw:0,1",
        "bluetooth" to "bluealsa"
    )
    
    /**
     * 检查是否为树莓派设备
     */
    fun isRaspberryPi(): Boolean {
        // 检查常见的树莓派标识文件
        val cpuInfoExists = access("/proc/device-tree/model", F_OK) == 0
        if (cpuInfoExists) {
            // 也可以读取文件内容确认具体型号
            return true
        }
        
        // 检查环境变量
        getenv("RASPBERRY_PI")?.toKString()?.let {
            return it.isNotEmpty()
        }
        
        return false
    }
    
    /**
     * 修复ALSA配置
     * 在树莓派上可能需要调整某些ALSA设置以获得更好的性能
     */
    fun fixAlsaConfig(): Boolean {
        logger.info("尝试修复ALSA配置...")
        
        // 尝试使用ALSA实用工具调整音量
        system("amixer -c 0 sset 'Mic' 80% > /dev/null 2>&1")
        system("amixer -c 0 sset 'Capture' 80% > /dev/null 2>&1")
        
        // 尝试加载必要的模块
        system("sudo modprobe snd-bcm2835 > /dev/null 2>&1")
        
        logger.info("ALSA配置修复完成")
        return true
    }
    
    /**
     * 获取推荐的录音设备
     */
    fun getRecommendedRecordingDevice(): Int {
        val deviceCount = Pa_GetDeviceCount()
        var bestDevice = -1
        var bestPriority = Int.MAX_VALUE
        
        // 查找所有ALSA设备
        for (i in 0 until deviceCount) {
            val deviceInfo = Pa_GetDeviceInfo(i)?.pointed ?: continue
            val apiInfo = Pa_GetHostApiInfo(deviceInfo.hostApi)?.pointed ?: continue
            
            // 只考虑ALSA设备和有输入通道的设备
            if (apiInfo.type == paALSA && deviceInfo.maxInputChannels > 0) {
                val deviceName = deviceInfo.name?.toKString() ?: ""
                
                // 检查设备名称是否包含我们优先考虑的关键字
                recordingDeviceKeywords.forEachIndexed { index, keyword ->
                    if (deviceName.contains(keyword, ignoreCase = true) && index < bestPriority) {
                        bestDevice = i
                        bestPriority = index
                        logger.info("找到优先级更高的录音设备: $deviceName (优先级: $index)")
                    }
                }
            }
        }
        
        if (bestDevice >= 0) {
            val deviceInfo = Pa_GetDeviceInfo(bestDevice)?.pointed
            logger.info("选择录音设备: ${deviceInfo?.name?.toKString()}")
        } else {
            logger.warn("未找到合适的录音设备，使用默认设备")
        }
        
        return bestDevice
    }
    
    /**
     * 获取推荐的播放设备
     */
    fun getRecommendedPlaybackDevice(): Int {
        val deviceCount = Pa_GetDeviceCount()
        var bestDevice = -1
        var bestPriority = Int.MAX_VALUE
        
        // 查找所有ALSA设备
        for (i in 0 until deviceCount) {
            val deviceInfo = Pa_GetDeviceInfo(i)?.pointed ?: continue
            val apiInfo = Pa_GetHostApiInfo(deviceInfo.hostApi)?.pointed ?: continue
            
            // 只考虑ALSA设备和有输出通道的设备
            if (apiInfo.type == paALSA && deviceInfo.maxOutputChannels > 0) {
                val deviceName = deviceInfo.name?.toKString() ?: ""
                
                // 检查设备名称是否包含我们优先考虑的关键字
                playbackDeviceKeywords.forEachIndexed { index, keyword ->
                    if (deviceName.contains(keyword, ignoreCase = true) && index < bestPriority) {
                        bestDevice = i
                        bestPriority = index
                        logger.info("找到优先级更高的播放设备: $deviceName (优先级: $index)")
                    }
                }
            }
        }
        
        if (bestDevice >= 0) {
            val deviceInfo = Pa_GetDeviceInfo(bestDevice)?.pointed
            logger.info("选择播放设备: ${deviceInfo?.name?.toKString()}")
        } else {
            logger.warn("未找到合适的播放设备，使用默认设备")
        }
        
        return bestDevice
    }
    
    /**
     * 获取ALSA设备字符串
     */
    fun getALSADeviceString(deviceIndex: Int, isInput: Boolean): String {
        if (deviceIndex < 0) {
            return "default"
        }
        
        val deviceInfo = Pa_GetDeviceInfo(deviceIndex)?.pointed
        val deviceName = deviceInfo?.name?.toKString() ?: ""
        
        // 根据设备名称返回特定的ALSA设备路径
        for ((key, path) in alasDevicePaths) {
            if (deviceName.contains(key, ignoreCase = true)) {
                return path
            }
        }
        
        // 返回默认ALSA设备路径
        return if (isInput) {
            "plughw:$deviceIndex,0"
        } else {
            "plughw:$deviceIndex,0"
        }
    }
} 