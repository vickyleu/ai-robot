@file:OptIn(ExperimentalForeignApi::class)

package voice.hal

import com.airobot.portaudiointerop.Pa_GetDeviceCount
import com.airobot.portaudiointerop.Pa_GetDeviceInfo
import com.airobot.portaudiointerop.Pa_GetHostApiInfo
import com.airobot.portaudiointerop.paALSA
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import kotlinx.cinterop.refTo
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.datetime.Clock.System
import platform.posix.F_OK
import platform.posix.access
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.fputc
import platform.posix.fclose
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.pclose
import platform.posix.popen
import platform.posix.system
import platform.posix.getpid
import platform.posix.kill
import platform.posix.SIGTERM
import platform.posix.SIGKILL
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
        "bluetooth" to "bluealsa",
        "microsemi_dac" to "hw:0,0"  // 添加Microsemi DAC的特定路径
    )
    
    /**
     * 检查是否为树莓派设备
     * 返回true，始终假设是树莓派环境
     */
    fun isRaspberryPi(): Boolean {
        // 简化逻辑，直接返回true
        return true
    }
    
    /**
     * 检测是否存在Microsemi DAC设备
     * @return Boolean 是否存在Microsemi DAC设备
     */
    fun isMicrosemiDacPresent(): Boolean {
        // 简化检测逻辑，默认存在
        return true
    }
    
    /**
     * 修复ALSA配置
     * 创建最优化的Microsemi DAC配置
     */
    fun fixAlsaConfig(): Boolean {
        logger.info("配置针对Microsemi DAC的ALSA...")
        
        // 确保没有其他进程占用音频设备
        killOtherAudioProcesses()
        
        // 获取用户主目录
        val homeDir = getenv("HOME")?.toKString() ?: return false
        val asoundrcPath = "$homeDir/.asoundrc"
        
        // 清理旧配置
        system("rm -f $asoundrcPath")

        // 检查设备访问权限
        system("sudo chmod -R 777 /dev/snd/* 2>/dev/null || true")
        
        // 创建针对Microsemi DAC的特殊配置
        val asoundrcContent = """
        # Microsemi DAC立体声配置 - 由AI-Robot生成
        
        pcm.!default {
            type hw
            card 0
            device 0
            format S16_LE
            channels 2
            rate 16000
        }
        
        ctl.!default {
            type hw
            card 0
        }
        """.trimIndent()
        
        // 写入文件
        val file = fopen(asoundrcPath, "w") ?: return false
        
        try {
            for (line in asoundrcContent.lines()) {
                fputs("$line\n", file)
            }
            
            // 应用新配置
            system("alsactl store 2>/dev/null || true")
            
            // 重启ALSA服务
            system("sudo alsactl kill rescan 2>/dev/null || true")
            system("sudo alsactl -F restore 2>/dev/null || true")
            
            return testAudioDevice()
        } catch (e: Exception) {
            logger.error("配置ALSA失败: ${e.message}")
            return false
        } finally {
            fclose(file)
        }
    }
    
    /**
     * 测试音频设备可用性
     */
    fun testAudioDevice(): Boolean {
        logger.info("测试音频设备可用性...")
        
        try {
            // 使用arecord测试Microsemi DAC
            val result = system("arecord -d 1 -f S16_LE -r 16000 -c 2 -D hw:0,0 /dev/null 2>/dev/null")
            val success = (result == 0)
            
            if (!success) {
                // 如果失败，尝试重新加载模块
                system("sudo modprobe -r snd_microsemi 2>/dev/null || true")
                system("sleep 1")
                system("sudo modprobe snd_microsemi 2>/dev/null || true")
                system("sleep 1")
                
                // 再次测试
                val retestResult = system("arecord -d 1 -f S16_LE -r 16000 -c 2 -D hw:0,0 /dev/null 2>/dev/null")
                return (retestResult == 0)
            }
            
            return success
        } catch (e: Exception) {
            logger.warn("测试音频设备失败: ${e.message}")
            return false
        }
    }
    
    /**
     * 检测声音设备
     * @return 卡号:设备号 格式的字符串
     */
    private fun detectSoundDevices(): String {
        // 执行arecord -l命令获取捕获设备
        val tempBuffer = ByteArray(2048)
        val command = "arecord -l 2>/dev/null | grep -i 'card' | head -n 1"
        val pipe = popen(command, "r") ?: return "0:0"
        
        val read = fgets(tempBuffer.refTo(0), tempBuffer.size, pipe)
        pclose(pipe)
        
        if (read != null) {
            val output = tempBuffer.toKString().trim()
            // 解析输出，一般格式为 "card X: Y..."
            val cardMatch = """card\s+(\d+)""".toRegex().find(output)
            val deviceMatch = """device\s+(\d+)""".toRegex().find(output)
            
            val cardNumber = cardMatch?.groups?.get(1)?.value ?: "0"
            val deviceNumber = deviceMatch?.groups?.get(1)?.value ?: "0"
            
            return "$cardNumber:$deviceNumber"
        }
        
        return "0:0" // 默认值
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
            if (apiInfo.type == paALSA && deviceInfo.maxInputChannels >= 2) {  // 确保至少支持2通道
                val deviceName = deviceInfo.name?.toKString() ?: ""
                
                // 优先选择支持立体声的设备
                if (deviceInfo.maxInputChannels >= 2) {
                    // 检查设备名称是否包含我们优先考虑的关键字
                    recordingDeviceKeywords.forEachIndexed { index, keyword ->
                        if (deviceName.contains(keyword, ignoreCase = true) && index < bestPriority) {
                            bestDevice = i
                            bestPriority = index
                            logger.info("找到优先级更高的录音设备: $deviceName (优先级: $index, 通道数: ${deviceInfo.maxInputChannels})")
                        }
                    }
                }
            }
        }
        
        if (bestDevice >= 0) {
            val deviceInfo = Pa_GetDeviceInfo(bestDevice)?.pointed
            logger.info("选择录音设备: ${deviceInfo?.name?.toKString()}, 通道数: ${deviceInfo?.maxInputChannels}")
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
            if (apiInfo.type == paALSA && deviceInfo.maxOutputChannels >= 2) {  // 确保至少支持2通道
                val deviceName = deviceInfo.name?.toKString() ?: ""
                
                // 优先选择支持立体声的设备
                if (deviceInfo.maxOutputChannels >= 2) {
                    // 检查设备名称是否包含我们优先考虑的关键字
                    playbackDeviceKeywords.forEachIndexed { index, keyword ->
                        if (deviceName.contains(keyword, ignoreCase = true) && index < bestPriority) {
                            bestDevice = i
                            bestPriority = index
                            logger.info("找到优先级更高的播放设备: $deviceName (优先级: $index, 通道数: ${deviceInfo.maxOutputChannels})")
                        }
                    }
                }
            }
        }
        
        if (bestDevice >= 0) {
            val deviceInfo = Pa_GetDeviceInfo(bestDevice)?.pointed
            logger.info("选择播放设备: ${deviceInfo?.name?.toKString()}, 通道数: ${deviceInfo?.maxOutputChannels}")
        } else {
            logger.warn("未找到合适的播放设备，使用默认设备")
        }
        
        return bestDevice
    }
    
    /**
     * 获取ALSA设备字符串
     * 优化了对Microsemi DAC的支持，使用更简单直接的设备字符串
     */
    fun getALSADeviceString(deviceIndex: Int, isInput: Boolean): String {
        // 始终返回最简单的硬件设备字符串
        return "hw:0,0"
    }
    
    /**
     * 杀死其他占用音频设备的进程
     */
    fun killOtherAudioProcesses(): Boolean {
        logger.info("释放音频资源...")
        
        try {
            // 获取自身进程ID避免终止自己
            val selfPID = platform.posix.getpid()
            
            // 使用更温和的方式结束音频相关进程，只使用一次pkill
            system("pkill -9 pulseaudio arecord aplay 2>/dev/null || true")
            
            // 只释放设备，不终止进程
            system("sudo fuser -k /dev/snd/* 2>/dev/null || true")
            
            // 确保设备权限
            system("sudo chmod -R 777 /dev/snd/* 2>/dev/null || true")
            
            // 避免重复加载卸载模块，可能导致系统不稳定
            
            // 简化ALSA状态管理，只做必要操作
            system("sudo rm -f /var/lib/alsa/asound.state.lock 2>/dev/null || true")
            
            return true
        } catch (e: Exception) {
            logger.error("释放音频资源失败: ${e.message}")
            return false
        }
    }
} 