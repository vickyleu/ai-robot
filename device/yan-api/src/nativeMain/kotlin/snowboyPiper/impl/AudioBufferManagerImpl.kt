@file:OptIn(InternalCoroutinesApi::class)

package snowboyPiper.impl

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.CArrayPointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.coroutines.InternalCoroutinesApi
import snowboyPiper.interfaces.AudioBufferManager

@OptIn(ExperimentalForeignApi::class)
class AudioBufferManagerImpl : AudioBufferManager {

    private val audioAccumulator = mutableListOf<Short>()

    // 共享短整型缓冲区池 - 常用大小
    private val shortBufferPool = mutableMapOf<Int, CArrayPointer<ShortVar>?>()
    private val shortBufferPoolLock = SynchronizedObject()

    // 共享浮点型缓冲区池
    private val floatBufferPool = mutableMapOf<Int, CArrayPointer<FloatVar>?>()
    private val floatBufferPoolLock = SynchronizedObject()

    // 共享整型缓冲区池
    private val intBufferPool = mutableMapOf<Int, CPointer<IntVar>?>()
    private val intBufferPoolLock = SynchronizedObject()

    // 临时数组，避免频繁分配
    private val tempShortArray = ShortArray(4096)

    // 最常用的缓冲区大小
    private val commonSizes = intArrayOf(320, 480, 512, 1024, 2048)

    init {
        // 预分配常用大小的缓冲区
        for (size in commonSizes) {
            shortBufferPool[size] = nativeHeap.allocArray<ShortVar>(size)
            floatBufferPool[size] = nativeHeap.allocArray<FloatVar>(size)
            if (size <= 10) { // 如果是整型缓冲区，预给小的大小
                intBufferPool[size] = nativeHeap.alloc<IntVar>().ptr
            }
        }
    }

    override val size: Int
        get() = audioAccumulator.size

    override fun add(sample: Short) {
        audioAccumulator.add(sample)
    }

    override fun get(): ShortArray {
        val result = ShortArray(audioAccumulator.size)
        for (i in audioAccumulator.indices) {
            result[i] = audioAccumulator[i]
        }
        return result
    }

    override fun clear() {
        audioAccumulator.clear()
    }

    override fun retainOverlap(overlapSize: Int) {
        if (overlapSize <= 0 || overlapSize >= audioAccumulator.size) {
            return
        }
        val overlap = audioAccumulator.takeLast(overlapSize)
        audioAccumulator.clear()
        audioAccumulator.addAll(overlap)
    }

    override fun getShortBuffer(size: Int): CArrayPointer<ShortVar> {
        synchronized(shortBufferPoolLock) {
            val existing = shortBufferPool[size]
            if (existing != null) return existing

            val newBuffer = nativeHeap.allocArray<ShortVar>(size)
            // 如果是常用大小，加入池中
            if (size % 160 == 0 && size <= 4096) shortBufferPool[size] = newBuffer
            return newBuffer
        }
    }

    override fun getFloatBuffer(size: Int): CArrayPointer<FloatVar> {
        synchronized(floatBufferPoolLock) {
            val existing = floatBufferPool[size]
            if (existing != null) return existing

            val newBuffer = nativeHeap.allocArray<FloatVar>(size)
            if (size % 160 == 0 && size <= 4096) floatBufferPool[size] = newBuffer
            return newBuffer
        }
    }

    override fun getIntBuffer(size: Int): CPointer<IntVar> {
        synchronized(intBufferPoolLock) {
            val existing = intBufferPool[size]
            if (existing != null) return existing

            // 整型缓冲区分配
            val newBuffer = nativeHeap.alloc<IntVar>()
            if (size <= 10) intBufferPool[size] = newBuffer.ptr
            return newBuffer.ptr
        }
    }

    override fun getShortArray(size: Int): ShortArray {
        if (size <= tempShortArray.size) return tempShortArray
        return ShortArray(size)
    }

    override fun release() {
        synchronized(shortBufferPoolLock) {
            for (buffer in shortBufferPool.values) {
                buffer?.let { nativeHeap.free(it.rawValue) }
            }
            shortBufferPool.clear()
        }

        synchronized(floatBufferPoolLock) {
            for (buffer in floatBufferPool.values) {
                buffer?.let { nativeHeap.free(it.rawValue) }
            }
            floatBufferPool.clear()
        }

        synchronized(intBufferPoolLock) {
            for (buffer in intBufferPool.values) {
                buffer?.let { nativeHeap.free(it.rawValue) }
            }
            intBufferPool.clear()
        }
    }
}