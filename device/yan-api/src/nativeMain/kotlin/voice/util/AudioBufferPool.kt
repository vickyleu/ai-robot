package com.airobot.device.yanapi.voice.util

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import voice.util.LogManager

/**
 * 音频缓冲池，用于减少内存分配
 */
object AudioBufferPool {
    private val logger = LogManager.getLogger("AudioBufferPool")

    // 不同大小的缓冲池
    private val pools = mapOf(
        320 to BufferPool(320, 20),    // 20ms 缓冲
        640 to BufferPool(640, 20),    // 40ms 缓冲
        1024 to BufferPool(1024, 10),  // 64ms 缓冲
        1600 to BufferPool(1600, 10),  // 100ms 缓冲
        3200 to BufferPool(3200, 5)    // 200ms 缓冲
    )

    private class BufferPool(
        val bufferSize: Int,
        val maxPoolSize: Int
    ) {
        internal val pool = mutableListOf<ByteArray>()
        internal val lock = SynchronizedObject()
        private var totalCreated = 0
        private var totalReused = 0

        fun acquire(): ByteArray {
            return synchronized(lock) {
                if (pool.isNotEmpty()) {
                    totalReused++
                    pool.removeAt(pool.size - 1)
                } else {
                    totalCreated++
                    ByteArray(bufferSize)
                }
            }
        }

        fun release(buffer: ByteArray) {
            synchronized(lock) {
                if (pool.size < maxPoolSize && buffer.size == bufferSize) {
                    buffer.fill(0) // 清空数据
                    pool.add(buffer)
                }
            }
        }

        fun getStats(): String {
            return synchronized(lock) {
                "size=$bufferSize, pooled=${pool.size}, created=$totalCreated, reused=$totalReused"
            }
        }
    }

    /**
     * 获取指定大小的缓冲区
     */
    fun acquire(size: Int): ByteArray {
        // 找到最接近的缓冲池大小
        val poolSize = pools.keys.firstOrNull { it >= size } ?: 3200
        return pools[poolSize]?.acquire() ?: ByteArray(size)
    }

    /**
     * 释放缓冲区回池
     */
    fun release(buffer: ByteArray) {
        pools[buffer.size]?.release(buffer)
    }

    /**
     * 获取缓冲池统计信息
     */
    fun getStats(): String {
        val sb = StringBuilder()
        sb.appendLine("音频缓冲池统计:")
        pools.forEach { (size, pool) ->
            sb.appendLine("  ${pool.getStats()}")
        }
        return sb.toString()
    }

    /**
     * 清空所有缓冲池
     */
    fun clear() {
        pools.values.forEach { pool ->
            synchronized(pool.lock) {
                pool.pool.clear()
            }
        }
        logger.info("已清空所有音频缓冲池")
    }
}
