@file:OptIn(ExperimentalForeignApi::class)

package com.airobot.device.yanapi.voice.utils

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.F_OK
import platform.posix.FILE
import platform.posix.access
import platform.posix.fclose
import platform.posix.feof
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.gettimeofday
import platform.posix.localtime_r
import platform.posix.strftime
import platform.posix.timeval
import platform.posix.tm

/**
 * 文件工具类
 * 提供文件读写功能
 */
object FileUtils {
    /**
     * 读取文件内容
     * @param filePath 文件路径
     * @return 文件内容
     */
    fun readFile(filePath: String): String {
        val result = StringBuilder()
        
        val file = fopen(filePath, "r") ?: return result.toString()
        
        try {
            memScoped {
                val buffer = allocArray<ByteVar>(4096)
                
                while (fgets(buffer, 4096, file)?.pointed != null) {
                    val line = buffer.toKString()
                    result.append(line)
                }
            }
        } finally {
            fclose(file)
        }
        
        return result.toString()
    }
    
    /**
     * 写入文件内容
     * @param filePath 文件路径
     * @param content 文件内容
     * @return 是否成功
     */
    fun writeToFile(filePath: String, content: String): Boolean {
        val file = fopen(filePath, "w") ?: return false
        
        try {
            fputs(content, file)
            return true
        } finally {
            fclose(file)
        }
    }
    
    /**
     * 检查文件是否存在
     * @param filePath 文件路径
     * @return 是否存在
     */
    fun fileExists(filePath: String): Boolean {
        return access(filePath, F_OK) == 0
    }
    
    /**
     * 获取环境变量
     * @param name 环境变量名
     * @return 环境变量值
     */
    fun getEnv(name: String): String? {
        return getenv(name)?.toKString()
    }
    
    /**
     * 获取HOME目录
     * @return HOME目录路径
     */
    fun getHomeDir(): String? {
        return getEnv("HOME")
    }
} 