@file:OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)

package snowboyPiper.impl

import com.airobot.alsainterop.fclose
import com.airobot.alsainterop.fopen
import com.airobot.alsainterop.fwrite
import com.airobot.snowboyinterop.SnowboyDetectWrapper
import com.airobot.snowboyinterop.snowboy_create
import com.airobot.snowboyinterop.snowboy_free
import com.airobot.snowboyinterop.snowboy_run_detection_int16
import com.airobot.snowboyinterop.snowboy_set_sensitivity
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.refTo
import kotlinx.cinterop.set
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import snowboyPiper.impl.VoskSpeechService.Companion.executeCommand
import snowboyPiper.interfaces.AudioPlayer
import snowboyPiper.interfaces.KeywordDetector
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Snowboy关键词检测器实现
 * 负责初始化和运行Snowboy关键词检测
 */
class SnowboyKeywordDetector : KeywordDetector {
    // 检测器实例
    private var snowboyDetector: CPointer<SnowboyDetectWrapper>? = null
    
    // 检测状态
    private val _detectionState = MutableStateFlow(KeywordDetector.DetectionState.IDLE)
    override val detectionState: StateFlow<KeywordDetector.DetectionState> = _detectionState.asStateFlow()
    
    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Default)
    
    /**
     * 初始化检测器
     * @param resourcePath 资源文件路径
     * @param modelPath 模型文件路径
     * @param sensitivity 灵敏度，范围0-1
     * @return 初始化是否成功
     */
    override fun initialize(resourcePath: String, modelPath: String, sensitivity: Float): Boolean {
        _detectionState.value = KeywordDetector.DetectionState.INITIALIZING
        
        // 检查文件是否存在
        scope.launch {
            val checkResourceCmd = "test -f $resourcePath && echo 'exists' || echo 'not exists'"
            val resourceExists = executeCommand(checkResourceCmd).trim() == "exists"
            if (!resourceExists) {
                println("[WARN] Snowboy资源文件不存在: $resourcePath")
            }
            
            val checkModelCmd = "test -f $modelPath && echo 'exists' || echo 'not exists'"
            val modelExists = executeCommand(checkModelCmd).trim() == "exists"
            if (!modelExists) {
                println("[WARN] Snowboy模型文件不存在: $modelPath")
            }
        }
        
        // 初始化Snowboy检测器
        println("[INFO] 创建Snowboy检测器...")
        try {
            snowboyDetector = snowboy_create(resourcePath, modelPath)
            if (snowboyDetector == null) {
                println("[ERROR] Snowboy检测器创建失败")
                _detectionState.value = KeywordDetector.DetectionState.ERROR
                return false
            }
            
            // 设置灵敏度
            println("[INFO] 设置灵敏度${sensitivity}...")
            snowboy_set_sensitivity(snowboyDetector, sensitivity.toString())
            println("[INFO] Snowboy检测器初始化成功")
            
            _detectionState.value = KeywordDetector.DetectionState.LISTENING
            return true
        } catch (e: Exception) {
            println("[ERROR] Snowboy初始化异常: ${e.message}")
            e.printStackTrace()
            _detectionState.value = KeywordDetector.DetectionState.ERROR
            return false
        }
    }

    /**
     * 检测关键词
     * @param buffer 音频数据缓冲区
     * @param frameCount 帧数
     * @return 检测结果，大于0表示检测到关键词，0表示未检测到，负值表示错误
     */
    override fun detect(player: AudioPlayer, buffer: ShortArray, frameCount: Int): Int {
        if (snowboyDetector == null) {
            println("[ERROR] Snowboy检测器未初始化")
            return -1
        }

        if (_detectionState.value != KeywordDetector.DetectionState.LISTENING) {
            _detectionState.value = KeywordDetector.DetectionState.LISTENING
        }

        try {
            val bufferPtr = nativeHeap.allocArray<ShortVar>(frameCount)

            // 复制音频数据到本地内存
            for (i in 0 until frameCount) {
                bufferPtr[i] = buffer[i]
            }

            // 运行检测
            val result = snowboy_run_detection_int16(snowboyDetector, bufferPtr, frameCount, is_end = 1)

            // 释放本地内存
            nativeHeap.free(bufferPtr.rawValue)

            if (result > 0) {
                // 检测到关键词
                println("[INFO] 检测到关键词！")
                _detectionState.value = KeywordDetector.DetectionState.DETECTED
            }
            if(result==-2){

                // 使用临时文件名
                val tempFilePath = "/tmp/snowboy_audio_${Clock.System.now().toEpochMilliseconds()}.raw"

                // 使用平台特定的文件写入
                val file = fopen(tempFilePath, "wb")
                if (file != null) {
                    try {
                        // 写入音频数据
                        for (i in 0 until frameCount) {
                            val value = buffer[i].toInt()
                            val bytes = byteArrayOf(
                                (value and 0xFF).toByte(),
                                ((value shr 8) and 0xFF).toByte()
                            )
                            fwrite(bytes.refTo(0), 1u, 2u, file)
                        }
                    } finally {
                        fclose(file)
                    }
//                    val playCommand = "aplay -D plughw:0,0 -f S16_LE -r 48000 -c 1 $tempFilePath && rm $tempFilePath"
                    // 执行命令播放音频并在播放后删除临时文件
                    scope.launch {
                        player.playAudio(tempFilePath)
//                        executeCommand(playCommand)
                    }
                } else {
                    println("[ERROR] 无法创建临时文件")
                }
            }

            return result
        } catch (e: Exception) {
            println("[ERROR] 关键词检测异常: ${e.message}")
            e.printStackTrace()
            _detectionState.value = KeywordDetector.DetectionState.ERROR
            return -1
        }
    }

    /**
     * 释放资源
     */
    override fun release() {
        try {
            snowboyDetector?.let {
                snowboy_free(it)
                println("[INFO] Snowboy资源已释放")
            }
            snowboyDetector = null
            _detectionState.value = KeywordDetector.DetectionState.IDLE
        } catch (e: Exception) {
            println("[WARN] 释放Snowboy资源时出错: ${e.message}")
            _detectionState.value = KeywordDetector.DetectionState.ERROR
        }
    }
}