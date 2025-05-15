package snowboyPiper.impl

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo
import kotlinx.cinterop.toKString
import platform.posix.fgets
import platform.posix.pclose
import platform.posix.popen

/**
 * Linux音频设备选择器
 * 用于在Linux/树莓派系统上选择合适的音频设备
 */
@OptIn(ExperimentalForeignApi::class)
class LinuxAudioDeviceSelector {

    /**
     * 音频设备信息
     * @param id 设备ID
     * @param name 设备名称
     * @param isInput 是否为输入设备
     * @param isOutput 是否为输出设备
     * @param description 设备描述
     */
    data class AudioDeviceInfo(
        val id: Int,
        val name: String,
        val isInput: Boolean,
        val isOutput: Boolean,
        val description: String = ""
    )

    /**
     * 获取系统上可用的ALSA音频设备列表
     * @return 音频设备列表
     */
    fun getALSADevices(): List<AudioDeviceInfo> {
        val devices = mutableListOf<AudioDeviceInfo>()
        
        try {
            // 使用aplay -l命令获取音频播放设备
            val outputDevices = executeCommand("aplay -l")
            parseALSADevicesList(outputDevices, true).forEach { devices.add(it) }
            
            // 使用arecord -l命令获取音频录制设备
            val inputDevices = executeCommand("arecord -l")
            parseALSADevicesList(inputDevices, false).forEach { devices.add(it) }
        } catch (e: Exception) {
            println("[ERROR] 获取ALSA设备列表失败: ${e.message}")
        }
        
        return devices
    }
    
    /**
     * 获取推荐的录音设备ID
     * 优先选择USB音频设备，如果没有则使用默认设备
     * @return 推荐的录音设备ID，或-1表示未找到合适设备
     */
    fun getRecommendedRecordingDevice(): Int {
        val devices = getALSADevices()
        
        // 按优先级顺序检查设备
        // 1. 首先查找USB麦克风
        val usbMicDevice = devices.firstOrNull { 
            it.isInput && (it.name.contains("usb", ignoreCase = true) || 
                           it.description.contains("usb", ignoreCase = true))
        }
        if (usbMicDevice != null) {
            println("[INFO] 找到USB麦克风设备: ${usbMicDevice.name}")
            return usbMicDevice.id
        }
        
        // 2. 查找任何麦克风设备
        val micDevice = devices.firstOrNull { 
            it.isInput && (it.name.contains("mic", ignoreCase = true) || 
                          it.description.contains("mic", ignoreCase = true))
        }
        if (micDevice != null) {
            println("[INFO] 找到麦克风设备: ${micDevice.name}")
            return micDevice.id
        }
        
        // 3. 查找任何录音设备
        val inputDevice = devices.firstOrNull { it.isInput }
        if (inputDevice != null) {
            println("[INFO] 找到输入设备: ${inputDevice.name}")
            return inputDevice.id
        }
        
        // 如果没有找到任何设备，返回-1
        println("[WARN] 未找到任何录音设备，将使用默认设备")
        return -1
    }
    
    /**
     * 获取推荐的播放设备ID
     * 优先选择USB音频设备或HDMI，如果没有则使用默认设备
     * @return 推荐的播放设备ID，或-1表示未找到合适设备
     */
    fun getRecommendedPlaybackDevice(): Int {
        val devices = getALSADevices()
        
        // 按优先级顺序检查设备
        // 1. 首先查找USB音频输出
        val usbSpeakerDevice = devices.firstOrNull { 
            it.isOutput && (it.name.contains("usb", ignoreCase = true) || 
                           it.description.contains("usb", ignoreCase = true))
        }
        if (usbSpeakerDevice != null) {
            println("[INFO] 找到USB音频输出设备: ${usbSpeakerDevice.name}")
            return usbSpeakerDevice.id
        }
        
        // 2. 查找HDMI输出
        val hdmiDevice = devices.firstOrNull { 
            it.isOutput && (it.name.contains("hdmi", ignoreCase = true) || 
                           it.description.contains("hdmi", ignoreCase = true))
        }
        if (hdmiDevice != null) {
            println("[INFO] 找到HDMI音频输出设备: ${hdmiDevice.name}")
            return hdmiDevice.id
        }
        
        // 3. 查找任何扬声器设备
        val speakerDevice = devices.firstOrNull { 
            it.isOutput && (it.name.contains("speaker", ignoreCase = true) || 
                           it.description.contains("speaker", ignoreCase = true))
        }
        if (speakerDevice != null) {
            println("[INFO] 找到扬声器设备: ${speakerDevice.name}")
            return speakerDevice.id
        }
        
        // 4. 查找任何输出设备
        val outputDevice = devices.firstOrNull { it.isOutput }
        if (outputDevice != null) {
            println("[INFO] 找到输出设备: ${outputDevice.name}")
            return outputDevice.id
        }
        
        // 如果没有找到任何设备，返回-1
        println("[WARN] 未找到任何播放设备，将使用默认设备")
        return -1
    }
    
    /**
     * 获取ALSA设备的参数字符串
     * @param deviceId 设备ID
     * @param isInput 是否为输入设备
     * @return ALSA设备参数字符串
     */
    fun getALSADeviceString(deviceId: Int, isInput: Boolean): String {
        if (deviceId < 0) {
            return "default"
        }
        
        // 尝试找到设备的详细信息
        val devices = getALSADevices()
        val matchingDevice = devices.firstOrNull { it.id == deviceId && it.isInput == isInput }
        
        if (matchingDevice != null) {
            // 解析设备名称获取hw:X,Y格式
            val cardRegex = "card\\s+(\\d+)".toRegex()
            val deviceRegex = "device\\s+(\\d+)".toRegex()
            
            val cardMatch = cardRegex.find(matchingDevice.description)
            val deviceMatch = deviceRegex.find(matchingDevice.description)
            
            if (cardMatch != null && deviceMatch != null) {
                val cardNum = cardMatch.groupValues[1]
                val deviceNum = deviceMatch.groupValues[1]
                return "hw:$cardNum,$deviceNum"
            }
        }
        
        // 如果无法解析，则返回默认设备
        return "default"
    }
    
    /**
     * 检测系统是否为Raspberry Pi
     * @return 是否为Raspberry Pi
     */
    fun isRaspberryPi(): Boolean {
        val modelInfo = executeCommand("cat /proc/device-tree/model 2>/dev/null || echo 'unknown'")
        return modelInfo.contains("Raspberry Pi", ignoreCase = true)
    }
    
    /**
     * 修复常见的ALSA配置问题
     * @return 修复是否成功
     */
    fun fixAlsaConfig(): Boolean {
        try {
            if (!isRaspberryPi()) {
                println("[INFO] 非树莓派系统，跳过ALSA配置修复")
                return true
            }
            
            println("[INFO] 尝试修复树莓派ALSA配置问题...")
            
            // 创建自定义ALSA配置
            val homeDir = executeCommand("echo \$HOME").trim()
            val alsaConfDir = "$homeDir/.asoundrc"
            
            // 检查配置文件是否已存在
            val fileExists = executeCommand("test -f $alsaConfDir && echo 'exists' || echo 'not exists'")
            
            if (fileExists.contains("exists")) {
                println("[INFO] ALSA配置文件已存在，备份原文件")
                executeCommand("cp $alsaConfDir ${alsaConfDir}.backup_$(date +%Y%m%d%H%M%S)")
            }
            
            // 创建一个简单的ALSA配置文件
            val config = """
                pcm.!default {
                    type asym
                    playback.pcm {
                        type plug
                        slave.pcm "hw:0,0"
                    }
                    capture.pcm {
                        type plug
                        slave.pcm "hw:0,0"
                    }
                }
                
                ctl.!default {
                    type hw
                    card 0
                }
            """.trimIndent()
            
            executeCommand("echo '$config' > $alsaConfDir")
            println("[INFO] 已创建默认ALSA配置文件")
            
            return true
        } catch (e: Exception) {
            println("[ERROR] 修复ALSA配置失败: ${e.message}")
            return false
        }
    }

    /**
     * 解析ALSA设备列表输出
     * @param output 命令输出结果
     * @param isOutputDevice 是否为输出设备
     * @return 解析后的设备列表
     */
    private fun parseALSADevicesList(output: String, isOutputDevice: Boolean): List<AudioDeviceInfo> {
        val devices = mutableListOf<AudioDeviceInfo>()
        val lines = output.lines()
        
        // 查找包含"card"的行，这些行包含设备信息
        val deviceLines = lines.filter { it.contains("card") && it.contains("device") }
        
        for (line in deviceLines) {
            try {
                // 解析设备ID
                val cardRegex = "card\\s+(\\d+)".toRegex()
                val deviceRegex = "device\\s+(\\d+)".toRegex()
                
                val cardMatch = cardRegex.find(line)
                val deviceMatch = deviceRegex.find(line)
                
                if (cardMatch != null && deviceMatch != null) {
                    val cardNum = cardMatch.groupValues[1].toInt()
                    val deviceNum = deviceMatch.groupValues[1].toInt()
                    val deviceId = cardNum * 10 + deviceNum // 简单的设备ID计算
                    
                    // 提取设备名称
                    val nameRegex = "\\[([^\\]]+)\\]".toRegex()
                    val nameMatches = nameRegex.findAll(line).toList()
                    val deviceName = if (nameMatches.size >= 2) nameMatches[1].groupValues[1] else "未知设备"
                    
                    devices.add(
                        AudioDeviceInfo(
                            id = deviceId,
                            name = deviceName,
                            isInput = !isOutputDevice,
                            isOutput = isOutputDevice,
                            description = line.trim()
                        )
                    )
                }
            } catch (e: Exception) {
                println("[WARN] 解析设备信息时出错: $line")
            }
        }
        
        return devices
    }

    /**
     * 执行系统命令并返回输出
     * 这是一个公共方法，可以被外部调用用于获取系统信息
     * @param command 要执行的命令
     * @return 命令输出
     */
    fun executeSystemCommand(command: String): String {
        return executeCommand(command)
    }

    /**
     * 执行Shell命令并返回输出
     * @param command 要执行的命令
     * @return 命令输出
     */
    private fun executeCommand(command: String): String {
        val buffer = ByteArray(4096)
        val result = StringBuilder()
        
        val pipe = popen(command, "r") ?: return ""
        
        try {
            while (true) {
                val line = fgets(buffer.refTo(0), buffer.size, pipe) ?: break
                result.append(buffer.toKString())
            }
        } finally {
            pclose(pipe)
        }
        
        return result.toString()
    }
} 