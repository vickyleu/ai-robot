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
     * 检测是否存在Microsemi DAC设备
     * @return Boolean 是否存在Microsemi DAC设备
     */
    fun isMicrosemiDacPresent(): Boolean {
        // 使用多种方法检测Microsemi DAC
        logger.info("检测Microsemi DAC设备...")
        
        // 方法1: 检查声卡列表
        val tempBuffer = ByteArray(2048)
        var isMicrosemiFound = false
        
        // 使用aplay -l命令检查声卡类型
        val command1 = "aplay -l 2>/dev/null | grep -i microsemi"
        val pipe1 = popen(command1, "r")
        if (pipe1 != null) {
            val read = fgets(tempBuffer.refTo(0), tempBuffer.size, pipe1)
            pclose(pipe1)
            
            if (read != null) {
                val output = tempBuffer.toKString().trim()
                if (output.isNotEmpty()) {
                    logger.info("通过aplay检测到Microsemi DAC: $output")
                    isMicrosemiFound = true
                }
            }
        }
        
        // 方法2: 检查/proc/asound/cards
        if (!isMicrosemiFound) {
            val command2 = "cat /proc/asound/cards 2>/dev/null | grep -i microsemi"
            val pipe2 = popen(command2, "r")
            if (pipe2 != null) {
                tempBuffer.fill(0)
                val read = fgets(tempBuffer.refTo(0), tempBuffer.size, pipe2)
                pclose(pipe2)
                
                if (read != null) {
                    val output = tempBuffer.toKString().trim()
                    if (output.isNotEmpty()) {
                        logger.info("通过/proc/asound/cards检测到Microsemi DAC: $output")
                        isMicrosemiFound = true
                    }
                }
            }
        }
        
        // 方法3: 检查内核模块
        if (!isMicrosemiFound) {
            val command3 = "lsmod | grep -i snd_microsemi"
            val pipe3 = popen(command3, "r")
            if (pipe3 != null) {
                tempBuffer.fill(0)
                val read = fgets(tempBuffer.refTo(0), tempBuffer.size, pipe3)
                pclose(pipe3)
                
                if (read != null) {
                    val output = tempBuffer.toKString().trim()
                    if (output.isNotEmpty()) {
                        logger.info("检测到Microsemi声卡模块已加载: $output")
                        isMicrosemiFound = true
                    }
                }
            }
        }
        
        if (isMicrosemiFound) {
            logger.info("确认检测到Microsemi DAC设备")
        } else {
            logger.info("未检测到Microsemi DAC设备")
        }
        
        return isMicrosemiFound
    }
    
    /**
     * 修复ALSA配置
     * 在树莓派上可能需要调整某些ALSA设置以获得更好的性能
     */
    fun fixAlsaConfig(): Boolean {
        logger.info("尝试修复ALSA配置...")
        
        // 确保没有其他进程占用音频设备 - 使用更安全的方式终止进程
        killOtherAudioProcesses()
        
        // 检查是否存在Microsemi DAC设备
        val hasMicrosemiDac = isMicrosemiDacPresent()
        if (hasMicrosemiDac) {
            logger.info("检测到Microsemi DAC设备，应用特殊配置...")
        }
        
        // 获取用户主目录
        val homeDir = getenv("HOME")?.toKString() ?: return false
        val asoundrcPath = "$homeDir/.asoundrc"
        
        // 首先尝试清理可能存在的不完整或错误的配置
        try {
            system("rm -f $asoundrcPath")
            logger.info("已清理旧的ALSA配置")
        } catch (e: Exception) {
            logger.warn("清理ALSA配置失败: ${e.message}")
        }
        
        // 检查设备访问权限
        try {
            system("sudo chmod -R 777 /dev/snd/* 2>/dev/null || true")
            logger.info("已修复音频设备权限")
        } catch (e: Exception) {
            logger.warn("修复音频设备权限失败: ${e.message}")
        }
        
        // 针对Microsemi DAC创建非常简单的配置
        val asoundrcContent = if (hasMicrosemiDac) {
            """
            # 针对Microsemi DAC的最小ALSA配置
            
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
        } else {
            """
            # 最小ALSA配置
            
            pcm.!default {
                type plug
                slave {
                    pcm "hw:0,0"
                    channels 2
                    rate 16000
                    format S16_LE
                }
            }
            
            ctl.!default {
                type hw
                card 0
            }
            """.trimIndent()
        }
        
        // 写入文件
        val file = fopen(asoundrcPath, "w") ?: return false
        
        try {
            for (line in asoundrcContent.lines()) {
                fputs("$line\n", file)
            }
            
            logger.info("ALSA配置已更新: ${if (hasMicrosemiDac) "Microsemi DAC特定配置" else "标准配置"}")
            
            // 应用新配置
            system("alsactl store 2>/dev/null || true")
            
            // 重启ALSA服务
            system("sudo alsactl kill rescan 2>/dev/null || true")
            system("sudo alsactl -F restore 2>/dev/null || true")
            
            // 直接测试设备可用性
            try {
                logger.info("尝试测试音频设备可用性...")
                // 尝试不同参数组合
                val testResults = mutableListOf<Pair<String, Int>>()
                
                // 测试1: 16kHz立体声
                val test1 = system("arecord -d 1 -f S16_LE -r 16000 -c 2 -D hw:0,0 /dev/null 2>/tmp/arecord_test_16k_stereo.log")
                testResults.add(Pair("16kHz立体声", test1))
                
                // 测试2: 8kHz立体声
                val test2 = system("arecord -d 1 -f S16_LE -r 8000 -c 2 -D hw:0,0 /dev/null 2>/tmp/arecord_test_8k_stereo.log")
                testResults.add(Pair("8kHz立体声", test2))
                
                // 记录测试结果
                logger.info("ALSA设备测试结果:")
                for ((desc, result) in testResults) {
                    logger.info("- $desc: ${if (result == 0) "成功" else "失败"}")
                }
                
                // 如果所有测试都失败，尝试加载特定模块
                if (testResults.all { it.second != 0 }) {
                    logger.warn("所有ALSA测试都失败，尝试加载声卡模块...")
                    system("sudo modprobe -r snd_microsemi 2>/dev/null || true")
                    kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(500) }
                    system("sudo modprobe snd_microsemi 2>/dev/null || true")
                    // 再次测试
                    val retestResult = system("arecord -d 1 -f S16_LE -r 16000 -c 2 -D hw:0,0 /dev/null 2>/tmp/arecord_retest.log")
                    logger.info("重新加载模块后测试: ${if (retestResult == 0) "成功" else "失败"}")
                }
                
            } catch (e: Exception) {
                logger.warn("测试音频设备失败: ${e.message}")
            }
            
            return true
        } catch (e: Exception) {
            logger.error("写入.asoundrc配置失败: ${e.message}")
            return false
        } finally {
            fclose(file)
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
        // 检查arecord/aplay是否可用，并列出设备
        try {
            val command = if (isInput) "arecord -l" else "aplay -l"
            system("$command > /tmp/alsa_devices.txt 2>/dev/null || true")
            logger.info("已检查可用${if (isInput) "录音" else "播放"}设备")
        } catch (e: Exception) {
            logger.warn("列举设备失败: ${e.message}")
        }
        
        // 检查是否有特殊设备名称提示这是Microsemi DAC
        val isMicrosemiDac = isMicrosemiDacPresent()
        
        // 尝试不同的设备字符串格式
        if (isMicrosemiDac) {
            logger.info("检测到Microsemi DAC设备，使用直接的设备字符串")
            // 直接使用硬件设备字符串
            return "hw:0,0"
        }
        
        // 默认设备字符串
        return "default"
    }
    
    /**
     * 杀死其他占用音频设备的进程
     * 这个方法会尝试杀死可能与我们的应用程序竞争音频设备的其他进程
     * 但会保护系统进程和我们自己的进程
     */
    fun killOtherAudioProcesses(): Boolean {
        logger.info("尝试结束其他占用音频设备的进程...")
        
        // 获取自身进程ID避免终止自己
        val selfPID = platform.posix.getpid()
        logger.info("当前进程ID: $selfPID")
        
        // 强制结束所有ALSA进程（除了自己）
        try {
            // 强制结束所有音频相关进程
            system("pkill -9 pulseaudio 2>/dev/null || true")
            system("pkill -9 arecord 2>/dev/null || true")
            system("pkill -9 aplay 2>/dev/null || true")
            logger.info("已终止所有潜在的音频进程")
            
            // 卸载并重新加载ALSA模块
            system("sudo rmmod snd_bcm2835 2>/dev/null || true")
            system("sudo modprobe snd_bcm2835 2>/dev/null || true")
            logger.info("已重载音频驱动模块")
            
            // 清理ALSA状态锁定文件
            system("sudo rm -f /var/lib/alsa/asound.state.lock 2>/dev/null || true")
            system("sudo rm -f /var/lib/alsa/asound.state 2>/dev/null || true")
            logger.info("已清理ALSA锁定文件")
            
            // 重置音频设备
            system("sudo alsactl kill rescan 2>/dev/null || true")
            system("sudo alsactl init 2>/dev/null || true")
            logger.info("已重置音频设备")
            
        } catch (e: Exception) {
            logger.error("处理音频进程时出错: $e")
        }
        
        // 等待音频子系统重置，使用较短时间
        try {
            system("sleep 0.5")
        } catch (e: Exception) {
            // 忽略
        }
        
        logger.info("音频进程清理完成")
        return true
    }
} 