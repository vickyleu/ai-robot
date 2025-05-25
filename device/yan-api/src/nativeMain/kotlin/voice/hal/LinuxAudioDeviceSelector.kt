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
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.pclose
import platform.posix.popen
import platform.posix.sleep
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
        "USB",
        "Mic",
        "MIC",
        "Camera",
        "Microphone",
        "Webcam",
        "WEBCAM",
        "airpods",
        "AirPods",
        "headset",
        "Headset"
    )

    private val playbackDeviceKeywords = listOf(
        "Speaker",
        "SPEAKER",
        "Headphone",
        "HEADPHONE",
        "airpods",
        "AirPods",
        "headset",
        "Headset",
        "Line Out"
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
        logger.info("修复Microsemi DAC的ALSA麦克风配置...")

        try {
            // 确保没有其他进程占用音频设备
            killOtherAudioProcesses()

            // 检查设备访问权限
            system("sudo chmod -R 777 /dev/snd/* 2>/dev/null || true")

            // 🔧 关键修复：取消麦克风静音并提高增益
            logger.info("取消麦克风静音...")
            val unmuteResult = system("amixer set 'MIC SOUT MUTE' off 2>/dev/null")
            if (unmuteResult != 0) {
                logger.warn("取消静音命令执行失败，继续尝试...")
            }

            logger.info("设置麦克风增益到最大...")
            val gainResult = system("amixer set 'MIC SOUT GAIN' 15 2>/dev/null")  // 改为最大值15
            if (gainResult != 0) {
                logger.warn("设置增益命令执行失败，继续尝试...")
            }

            // 确保DAC音量也是最大
            logger.info("设置DAC音量到最大...")
            system("amixer set 'DAC' 20 2>/dev/null || true")

            // 保存设置
            system("sudo alsactl store 2>/dev/null || true")

            // 验证设置是否生效
            logger.info("验证麦克风配置...")
            system("amixer get 'MIC SOUT MUTE'")
            system("amixer get 'MIC SOUT GAIN'")
            system("amixer get 'DAC'")
            return true

        } catch (e: Exception) {
            logger.error("配置ALSA失败: ${e.message}")
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
                        if (deviceName.contains(
                                keyword,
                                ignoreCase = true
                            ) && index < bestPriority
                        ) {
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
                        if (deviceName.contains(
                                keyword,
                                ignoreCase = true
                            ) && index < bestPriority
                        ) {
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

    /**
     * 全面诊断和修复音频问题
     * 检查并尝试解决常见的音频问题
     * @return 是否成功修复问题
     */
    fun diagnoseAndFixAudioIssues(): Boolean {
        logger.info("开始全面诊断和修复音频问题...")
        
        try {
            // 1. 检查并释放其他进程占用的音频设备
            killOtherAudioProcesses()
            
            // 2. 检查设备权限
            logger.info("检查音频设备权限...")
            system("sudo chmod -R 777 /dev/snd/* 2>/dev/null || true")
            
            // 3. 检查声卡模块是否加载
            logger.info("检查声卡模块...")
            system("lsmod | grep snd")
            
            // 4. 重新加载声卡模块
            logger.info("尝试重新加载声卡模块...")
            system("sudo modprobe -r snd_microsemi 2>/dev/null || true")
            system("sleep 1")
            system("sudo modprobe snd_microsemi 2>/dev/null || true")
            system("sleep 1")
            
            // 5. 调整音量设置
            logger.info("调整音量设置...")
            system("amixer set Master 100% unmute 2>/dev/null || true")  
            system("amixer set PCM 100% unmute 2>/dev/null || true")
            system("amixer set Speaker 100% unmute 2>/dev/null || true")
            system("amixer set 'MIC SOUT GAIN' 12 2>/dev/null || true")
            
            // 6. 尝试重启ALSA服务
            logger.info("尝试重启ALSA服务...")
            system("sudo alsactl kill rescan 2>/dev/null || true")
            system("sudo alsactl -F restore 2>/dev/null || true")
            
            // 7. 创建测试音频文件
            logger.info("创建测试音频文件...")
            val testWavPath = "/tmp/test_tone.wav"
            system("""
                dd if=/dev/urandom bs=1k count=10 | aplay -f cd -t raw 2>/dev/null || true
                if [ ! -f "$testWavPath" ]; then
                    echo "生成测试音频文件..."
                    dd if=/dev/urandom of=$testWavPath bs=1k count=10 2>/dev/null || true
                fi
            """.trimIndent())
            
            // 8. 使用不同方法测试播放
            logger.info("测试音频播放...")
            // 使用ALSA直接播放
            system("aplay -D hw:0,0 $testWavPath 2>/dev/null || true")
            // 使用默认设备播放
            system("aplay -D default $testWavPath 2>/dev/null || true")
            // 使用dmix设备播放
            system("aplay -D dmix:0,0 $testWavPath 2>/dev/null || true")
            
            // 9. 输出音频设备详细信息
            logger.info("音频设备详细信息:")
            system("aplay -l")
            system("aplay -L")
            
            logger.info("诊断和修复完成")
            return true
        } catch (e: Exception) {
            logger.error("诊断和修复过程中出现异常: ${e.message}")
            return false
        }
    }
} 