@file:OptIn(ExperimentalForeignApi::class)

package voice.hal

import com.airobot.portaudiointerop.PaAlsaStreamInfo
import com.airobot.portaudiointerop.paALSA
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import voice.util.LogManager

/**
 * ALSA流信息类
 * 用于创建和管理ALSA特定的音频流信息
 */
class AlsaStreamInfo(private val deviceString: String) {
    private val logger = LogManager.getLogger("AlsaStreamInfo")
    
    /**
     * 创建ALSA流信息
     * @return 流信息指针，失败返回null
     */
    fun createStreamInfo(): CPointer<COpaquePointerVar>? {
        if (deviceString.isBlank()) {
            logger.warn("设备字符串为空，无法创建ALSA流信息")
            return null
        }
        
        try {
            memScoped {
                // 创建ALSA流信息结构
                val streamInfo = nativeHeap.alloc<PaAlsaStreamInfo>()
                
                // 设置结构大小和类型标识符
                streamInfo.size = sizeOf<PaAlsaStreamInfo>().toUInt()
                streamInfo.hostApiType = paALSA.toUInt()
                
                // 设置ALSA设备名称
                val deviceCString = deviceString.cstr.ptr
                streamInfo.deviceString = deviceCString
                
                logger.info("创建ALSA流信息成功: $deviceString")
                return streamInfo.ptr.reinterpret()
            }
        } catch (e: Exception) {
            logger.error("创建ALSA流信息失败: ${e.message}")
            return null
        }
    }
    
    /**
     * 释放ALSA流信息
     */
    fun releaseStreamInfo(streamInfoPtr: CPointer<COpaquePointerVar>) {
        try {
            nativeHeap.free(streamInfoPtr.rawValue)
            logger.info("释放ALSA流信息成功")
        } catch (e: Exception) {
            logger.error("释放ALSA流信息失败: ${e.message}")
        }
    }
} 