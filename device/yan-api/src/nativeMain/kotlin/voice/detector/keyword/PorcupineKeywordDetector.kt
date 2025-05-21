@file:OptIn(ExperimentalForeignApi::class)

package voice.detector.keyword

import kotlinx.cinterop.*
import voice.util.LogManager
import com.airobot.porcupineinterop.*

/**
 * Porcupine 唤醒词检测器的 Kotlin 封装
 * 此代码封存, 效果最好, 但是不支持中文,保留后续使用
 */
class PorcupineKeywordDetector {
    private val logger = LogManager.getLogger("PorcupineKeywordDetector")

    private var handle: CPointer<pv_porcupine>? = null
    private var isInitialized = false

    private var frameLength = 0
    private var keywordCallback: ((String) -> Unit)? = null

    /**
     * 初始化 Porcupine 关键词检测器
     * @param modelPath 模型路径
     * @param sensitivity 敏感度 [0,1]
     * @return 初始化是否成功
     */
    fun initialize(modelPath: String, sensitivity: Float = 0.5f): Boolean {
        if (isInitialized) {
            logger.warn("Porcupine 已经初始化")
            return true
        }

        try {
            // 检查模型路径
            if (modelPath.isEmpty()) {
                logger.error("模型路径为空")
                return false
            }

            memScoped {
                // 找到关键词模型文件
                val keywordPath = "$modelPath/xiaoduxiaodu_zh_linux_v2_1_0.ppn"

                // 关键词路径数组
                val keywordPaths = allocArray<CPointerVar<ByteVar>>(1)
                keywordPaths[0] = keywordPath.cstr.ptr

                // 敏感度数组
                val sensitivities = allocArray<FloatVar>(1)
                sensitivities[0] = sensitivity

                // 准备输出句柄
                val handlePtr = allocPointerTo<pv_porcupine>()

                // 初始化 Porcupine，使用my_pv_porcupine_init包装函数
                val result = pv_porcupine_init(
                    access_key = "YYQ7+rq6kdT8iiOC3rm++Ckh6cgs/umQn9QWR0s2jEeYkUqKwz2VPg==", // 使用Picovoice提供的访问密钥
                    model_path = "$modelPath/porcupine_params.pv",
                    num_keywords = 1,
                    keyword_paths = keywordPaths,
                    sensitivities = sensitivities,
                    `object` = handlePtr.ptr
                )

                if (result != PV_STATUS_SUCCESS) {
                    logger.error("Porcupine 初始化失败")
                    return false
                }

                // 获取句柄
                handle = handlePtr.value

                // 获取帧长
                frameLength = pv_porcupine_frame_length()

                isInitialized = true
                logger.info("Porcupine 初始化成功: 模型=$modelPath, 敏感度=$sensitivity, 帧长=$frameLength")
                return true
            }
        } catch (e: Exception) {
            logger.error("Porcupine 初始化异常: ${e.message}")
            return false
        }
    }

    /**
     * 设置关键词检测回调
     * @param callback 检测到关键词时的回调函数
     */
    fun setKeywordCallback(callback: (String) -> Unit) {
        this.keywordCallback = callback
    }

    /**
     * 检测音频中是否包含关键词
     * @param audioData 音频数据
     * @return 是否检测到关键词
     */
    fun detect(audioData: ShortArray): Boolean {
        if (!isInitialized || handle == null) {
            logger.error("Porcupine 未初始化")
            return false
        }

        if (audioData.size < frameLength) {
            logger.warn("音频数据过短: ${audioData.size} < $frameLength")
            return false
        }

        try {
            memScoped {
                val keywordIndex = alloc<IntVar>()

                val result = pv_porcupine_process(
                    handle,
                    audioData.refTo(0),
                    keywordIndex.ptr
                )

                if (result != PV_STATUS_SUCCESS) {
                    logger.error("Porcupine 处理失败")
                    return false
                }

                // 检测到关键词
                val detected = keywordIndex.value >= 0
                if (detected) {
                    logger.info("检测到关键词: index=${keywordIndex.value}")
                    keywordCallback?.invoke("小度小度")
                }

                return detected
            }
        } catch (e: Exception) {
            logger.error("Porcupine 检测异常: ${e.message}")
            return false
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        if (handle != null) {
            try {
                pv_porcupine_delete(handle)
                handle = null
                isInitialized = false
                logger.info("Porcupine 资源已释放")
            } catch (e: Exception) {
                logger.error("释放 Porcupine 资源失败: ${e.message}")
            }
        }
    }
} 