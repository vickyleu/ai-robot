@file:OptIn(ExperimentalForeignApi::class)

package voice.util

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.get
import kotlin.experimental.and

/**
 * 音频处理工具类
 * 提供音频数据格式转换方法
 */
object AudioUtils {
    
    /**
     * 将C指针类型的Short数组转换为Kotlin的ByteArray
     * 采用小端序(Little Endian)格式
     * 
     * @param source Short数组的C指针
     * @param length 数组长度
     * @return 转换后的ByteArray
     */
    fun shortArrayToByteArray(source: CPointer<ShortVar>, length: Int): ByteArray {
        val result = ByteArray(length * 2)
        for (i in 0 until length) {
            val value = source[i]
            result[i * 2] = (value and 0xFF).toByte()
            result[i * 2 + 1] = (value.toInt() shr 8).toByte()
        }
        return result
    }
    
    /**
     * 将Kotlin的ShortArray转换为ByteArray
     * 采用小端序(Little Endian)格式
     * 
     * @param source 源ShortArray
     * @return 转换后的ByteArray
     */
    fun shortArrayToByteArray(source: ShortArray): ByteArray {
        val result = ByteArray(source.size * 2)
        for (i in source.indices) {
            val value = source[i]
            result[i * 2] = (value and 0xFF).toByte()
            result[i * 2 + 1] = (value.toInt() shr 8).toByte()
        }
        return result
    }
    
    /**
     * 将Kotlin的ByteArray转换为ShortArray
     * 采用小端序(Little Endian)格式
     * 
     * @param source 源ByteArray
     * @return 转换后的ShortArray
     */
    fun byteArrayToShortArray(source: ByteArray): ShortArray {
        val shortArraySize = source.size / 2
        val result = ShortArray(shortArraySize)
        for (i in 0 until shortArraySize) {
            val low = source[i * 2].toInt() and 0xFF
            val high = source[i * 2 + 1].toInt() and 0xFF
            result[i] = ((high shl 8) or low).toShort()
        }
        return result
    }
    
    /**
     * 将单声道音频转换为立体声
     * 通过复制每个样本到左右两个通道实现
     * 
     * @param mono 单声道音频数据
     * @return 立体声音频数据
     */
    fun monoToStereo(mono: ShortArray): ShortArray {
        val stereo = ShortArray(mono.size * 2)
        for (i in mono.indices) {
            val sample = mono[i]
            stereo[i * 2] = sample     // 左声道
            stereo[i * 2 + 1] = sample // 右声道
        }
        return stereo
    }
    
    /**
     * 将立体声音频转换为单声道
     * 通过计算左右声道的平均值实现
     * 
     * @param stereo 立体声音频数据
     * @return 单声道音频数据
     */
    fun stereoToMono(stereo: ShortArray): ShortArray {
        val monoSize = stereo.size / 2
        val mono = ShortArray(monoSize)
        for (i in 0 until monoSize) {
            val left = stereo[i * 2].toInt()
            val right = stereo[i * 2 + 1].toInt()
            mono[i] = ((left + right) / 2).toShort()
        }
        return mono
    }
    
    /**
     * 将 48 kHz 立体声 PCM (16-bit) 降采样到 16 kHz，保持立体声。
     * 由于二者为 3:1 的整数倍关系，可用简单均值抽取，性能开销极低。
     *
     * @param src 48 kHz 立体声 short 数组（L R L R …）
     * @return 16 kHz 立体声 short 数组
     */
    fun downsample48kTo16kStereo(src: ShortArray): ShortArray {
        // 3 个 48k 立体声帧 (6 shorts) -> 1 个 16k 立体声帧 (2 shorts)
        val frames = src.size / 6                   // 可整除的帧数
        val out = ShortArray(frames * 2)
        var j = 0
        var i = 0
        repeat(frames) {
            val l = ((src[i].toInt() + src[i + 2] + src[i + 4]) / 3).toShort()
            val r = ((src[i + 1] + src[i + 3] + src[i + 5]) / 3).toShort()
            out[j++] = l
            out[j++] = r
            i += 6
        }
        return out
    }

    /** FloatArray [-1,1] → ShortArray (16-bit PCM) */
    fun floatArrayToShortArray(src: FloatArray): ShortArray {
        val out = ShortArray(src.size)
        for (i in src.indices) {
            val v = (src[i] * 32767f).toInt().coerceIn(-32768, 32767)
            out[i] = v.toShort()
        }
        return out
    }

    /** ShortArray (16-bit PCM) → FloatArray [-1,1] */
    fun shortArrayToFloatArray(src: ShortArray): FloatArray {
        val out = FloatArray(src.size)
        for (i in src.indices) {
            out[i] = src[i] / 32768f
        }
        return out
    }
} 