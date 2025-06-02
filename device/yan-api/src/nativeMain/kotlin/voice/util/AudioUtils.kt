@file:OptIn(ExperimentalForeignApi::class)

package voice.util

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.get
import kotlin.experimental.and
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlin.math.abs
import platform.posix.*
import kotlinx.cinterop.refTo

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
        shortArrayToByteArray(source, source.size, result)
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
     * @param stereoData 立体声音频数据
     * @return 单声道音频数据
     */
    fun stereoToMono(stereoData: ShortArray): ShortArray {
        val monoLength = stereoData.size / 2
        val monoData = ShortArray(monoLength)
        
        for (i in 0 until monoLength) {
            // 使用简单平均值，避免复杂权重计算导致数据丢失
            val left = stereoData[i * 2].toInt()
            val right = stereoData[i * 2 + 1].toInt()
            
            // 计算平均值并限制在Short范围内
            val average = (left + right) / 2
            monoData[i] = average.coerceIn(-32768, 32767).toShort()
        }
        
        return monoData
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

    /** 
     * FloatArray [-1,1] → ShortArray (16-bit PCM)
     * @param src 浮点数组，范围[-1,1]
     * @return 短整型数组
     */
    fun floatArrayToShortArray(src: FloatArray): ShortArray {
        val out = ShortArray(src.size)
        for (i in src.indices) {
            val v = (src[i] * 32767f).toInt().coerceIn(-32768, 32767)
            out[i] = v.toShort()
        }
        return out
    }

    /** 
     * ShortArray (16-bit PCM) → FloatArray [-1,1] 
     * @param src 短整型数组
     * @return 浮点数组，范围[-1,1]
     */
    fun shortArrayToFloatArray(src: ShortArray): FloatArray {
        val out = FloatArray(src.size)
        for (i in src.indices) {
            out[i] = src[i] / 32768f
        }
        return out
    }

    /**
     * 将 ShortArray 转为 ByteArray，写入复用的 dst，避免每帧重新分配。
     * dst 必须至少有 samples*2 的空间。
     * @param src 源短整型数组
     * @param srcLen 源数组的有效长度 
     * @param dst 目标字节数组（复用）
     * @return 实际写入的字节数
     */
    fun shortArrayToByteArray(src: ShortArray, srcLen: Int, dst: ByteArray): Int {
        val bytes = srcLen * 2
        if (dst.size < bytes) throw IllegalArgumentException("dst too small")
        var j = 0
        for (i in 0 until srcLen) {
            val v = src[i]
            dst[j++] = (v and 0xFF).toByte()
            dst[j++] = (v.toInt() shr 8).toByte()
        }
        return bytes
    }

    /**
     * 字节数组转短整型数组
     * @param bytes 字节数组
     * @param length 字节数组长度
     * @return 短整型数组
     */
    fun byteArrayToShortArray(bytes: ByteArray, length: Int): ShortArray {
        val shortLength = length / 2
        val shorts = ShortArray(shortLength)
        
        for (i in 0 until shortLength) {
            val b1 = bytes[i * 2].toInt() and 0xFF
            val b2 = bytes[i * 2 + 1].toInt() and 0xFF
            shorts[i] = ((b2 shl 8) or b1).toShort()
        }
        
        return shorts
    }
    
    /**
     * 调整音频音量
     * @param audio 音频数据
     * @param gain 增益 (0.0-2.0, 1.0表示原始音量)
     * @return 调整后的音频
     */
    fun adjustVolume(audio: ShortArray, gain: Float): ShortArray {
        val adjusted = ShortArray(audio.size)
        val clampedGain = gain.coerceIn(0.0f, 2.0f)
        
        for (i in audio.indices) {
            val sample = (audio[i].toFloat() * clampedGain).toInt()
            adjusted[i] = when {
                sample > Short.MAX_VALUE -> Short.MAX_VALUE
                sample < Short.MIN_VALUE -> Short.MIN_VALUE
                else -> sample.toShort()
            }
        }
        
        return adjusted
    }
    
    /**
     * 重采样音频数据（简单实现）
     * @param input 输入音频数据
     * @param inputRate 输入采样率
     * @param outputRate 输出采样率
     * @param channels 通道数
     * @return 重采样后的音频数据
     */
    fun resampleAudio(input: ByteArray, inputRate: Int, outputRate: Int, channels: Int): ByteArray {
        if (inputRate == outputRate) return input
        
        val inputSamples = input.size / 2 // 16位PCM，每个样本2字节
        val outputSamples = (inputSamples.toLong() * outputRate / inputRate).toInt()
        val output = ByteArray(outputSamples * 2)
        
        // 转换为short数组
        val inputShorts = byteArrayToShortArray(input)
        val outputShorts = ShortArray(outputSamples)
        
        // 线性插值重采样
        for (i in 0 until outputSamples / channels) {
            val position = i.toDouble() * inputRate / outputRate
            val intPosition = position.toInt()
            val fraction = position - intPosition
            
            for (ch in 0 until channels) {
                val inputIndex1 = (intPosition * channels + ch).coerceIn(0, inputShorts.size - 1)
                val inputIndex2 = ((intPosition + 1) * channels + ch).coerceIn(0, inputShorts.size - 1)
                
                val sample1 = inputShorts[inputIndex1].toDouble()
                val sample2 = inputShorts[inputIndex2].toDouble()
                
                val interpolated = (sample1 * (1.0 - fraction) + sample2 * fraction).toInt().toShort()
                outputShorts[i * channels + ch] = interpolated
            }
        }
        
        // 转回ByteArray
        val bytesWritten = shortArrayToByteArray(outputShorts, outputShorts.size, output)
        return output
    }

    /**
     * 将ShortArray保存为WAV文件（16bit PCM，支持多声道）。
     * @param data 音频数据（PCM 16bit）
     * @param sampleRate 采样率
     * @param channels 声道数
     * @param filePath 保存路径
     */
    fun saveShortArrayAsWav(data: ShortArray, sampleRate: Int, channels: Int, filePath: String) {
        val byteRate = sampleRate * channels * 2
        val totalAudioLen = data.size * 2L
        val totalDataLen = totalAudioLen + 36
        val header = ByteArray(44)
        // RIFF/WAVE header
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] =
            'F'.code.toByte()
        val totalDataLenInt = totalDataLen.toInt()
        header[4] = (totalDataLenInt and 0xff).toByte()
        header[5] = ((totalDataLenInt shr 8) and 0xff).toByte()
        header[6] = ((totalDataLenInt shr 16) and 0xff).toByte()
        header[7] = ((totalDataLenInt shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] =
            'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] =
            't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0 // Subchunk1Size (16 for PCM)
        header[20] = 1; header[21] = 0 // AudioFormat (1 = PCM)
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * 2).toByte(); header[33] = 0 // BlockAlign
        header[34] = 16; header[35] = 0 // BitsPerSample
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] =
            'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()
        val file = fopen(filePath, "wb") ?: return
        fwrite(header.refTo(0), 1.convert(), 44.convert(), file)
        // 写PCM数据（小端序）
        for (sample in data) {
            val lo = (sample.toInt() and 0xff).toByte()
            val hi = ((sample.toInt() shr 8) and 0xff).toByte()
            fwrite(byteArrayOf(lo).refTo(0), 1.convert(), 1.convert(), file)
            fwrite(byteArrayOf(hi).refTo(0), 1.convert(), 1.convert(), file)
        }
        fclose(file)
    }
} 