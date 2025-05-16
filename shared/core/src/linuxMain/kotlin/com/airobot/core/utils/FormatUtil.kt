@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package com.airobot.core.utils

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.toKString
import platform.posix.sprintf

/**
 * 格式化工具类
 * 提供各种基本类型的格式化功能
 */
object FormatUtil {
    /**
     * 格式化Double值为指定小数位数的字符串
     * @param value 要格式化的双精度浮点数
     * @param precision 小数位数
     * @param groupDigits 是否使用千位分隔符
     * @return 格式化后的字符串
     */
    fun formatDouble(value: Double, precision: Int = 2, groupDigits: Boolean = false): String {
        val buffer = nativeHeap.allocArray<ByteVar>(64)

        val format = if (groupDigits) {
            "%'.${precision}f"
        } else {
            "%.${precision}f"
        }

        sprintf(buffer, format, value)
        val result = buffer.toKString()
        nativeHeap.free(buffer.rawValue)

        return result
    }

    /**
     * 格式化Float值为指定小数位数的字符串
     * @param value 要格式化的单精度浮点数
     * @param precision 小数位数
     * @param groupDigits 是否使用千位分隔符
     * @return 格式化后的字符串
     */
    fun formatFloat(value: Float, precision: Int = 2, groupDigits: Boolean = false): String {
        return formatDouble(value.toDouble(), precision, groupDigits)
    }

    /**
     * 格式化整数值，可选带千位分隔符
     * @param value 要格式化的整数
     * @param groupDigits 是否使用千位分隔符
     * @return 格式化后的字符串
     */
    fun formatInt(value: Int, groupDigits: Boolean = false): String {
        val buffer = nativeHeap.allocArray<ByteVar>(32)

        val format = if (groupDigits) {
            "%'d"
        } else {
            "%d"
        }

        sprintf(buffer, format, value)
        val result = buffer.toKString()
        nativeHeap.free(buffer.rawValue)

        return result
    }

    /**
     * 格式化Long值，可选带千位分隔符
     * @param value 要格式化的长整数
     * @param groupDigits 是否使用千位分隔符
     * @return 格式化后的字符串
     */
    fun formatLong(value: Long, groupDigits: Boolean = false): String {
        val buffer = nativeHeap.allocArray<ByteVar>(32)

        val format = if (groupDigits) {
            "%'lld"
        } else {
            "%lld"
        }

        sprintf(buffer, format, value)
        val result = buffer.toKString()
        nativeHeap.free(buffer.rawValue)

        return result
    }

    /**
     * 格式化为百分比
     * @param value 要格式化的值(0.0-1.0)
     * @param precision 小数位数
     * @return 格式化后的百分比字符串
     */
    fun formatPercent(value: Double, precision: Int = 1): String {
        val percent = value * 100
        return "${formatDouble(percent, precision)}%"
    }
}

/**
 * 字符串格式化函数
 * 提供简单的Printf风格格式化功能
 * 注意：由于C互操作限制，仅支持简单的替换模式
 */
object StringFormat {
    /**
     * 格式化带一个整数参数的字符串
     */
    fun format(format: String, arg: Int): String {
        val buffer = nativeHeap.allocArray<ByteVar>(1024)
        sprintf(buffer, format, arg)
        val result = buffer.toKString()
        nativeHeap.free(buffer.rawValue)
        return result
    }

    /**
     * 格式化带一个长整数参数的字符串
     */
    fun format(format: String, arg: Long): String {
        val buffer = nativeHeap.allocArray<ByteVar>(1024)
        sprintf(buffer, format, arg)
        val result = buffer.toKString()
        nativeHeap.free(buffer.rawValue)
        return result
    }

    /**
     * 格式化带一个Float参数的字符串
     */
    fun format(format: String, arg: Float): String {
        val buffer = nativeHeap.allocArray<ByteVar>(1024)
        sprintf(buffer, format, arg.toDouble())  // C中float会提升为double
        val result = buffer.toKString()
        nativeHeap.free(buffer.rawValue)
        return result
    }

    /**
     * 格式化带一个Double参数的字符串
     */
    fun format(format: String, arg: Double): String {
        val buffer = nativeHeap.allocArray<ByteVar>(1024)
        sprintf(buffer, format, arg)
        val result = buffer.toKString()
        nativeHeap.free(buffer.rawValue)
        return result
    }

    /**
     * 格式化带一个字符串参数的字符串
     */
    fun format(format: String, arg: String): String {
        val buffer = nativeHeap.allocArray<ByteVar>(1024)
        sprintf(buffer, format, arg)
        val result = buffer.toKString()
        nativeHeap.free(buffer.rawValue)
        return result
    }

    /**
     * 格式化带两个参数的字符串
     */
    fun format(format: String, arg1: Any, arg2: Any): String {
        val buffer = nativeHeap.allocArray<ByteVar>(1024)

        // 根据参数类型调用适当的sprintf重载
        when {
            arg1 is Int && arg2 is Int ->
                sprintf(buffer, format, arg1, arg2)

            arg1 is String && arg2 is Int ->
                sprintf(buffer, format, arg1, arg2)

            arg1 is Int && arg2 is String ->
                sprintf(buffer, format, arg1, arg2)

            arg1 is String && arg2 is String ->
                sprintf(buffer, format, arg1, arg2)

            arg1 is Double && arg2 is Double ->
                sprintf(buffer, format, arg1, arg2)

            arg1 is String && arg2 is Double ->
                sprintf(buffer, format, arg1, arg2)

            arg1 is Double && arg2 is String ->
                sprintf(buffer, format, arg1, arg2)

            else -> {
                // 转换为字符串处理
                sprintf(buffer, format, arg1.toString(), arg2.toString())
            }
        }

        val result = buffer.toKString()
        nativeHeap.free(buffer.rawValue)
        return result
    }

    /**
     * 格式化带三个参数的字符串
     */
    fun format(format: String, arg1: Any, arg2: Any, arg3: Any): String {
        val buffer = nativeHeap.allocArray<ByteVar>(1024)

        // 常见组合
        when {
            arg1 is String && arg2 is Int && arg3 is Int ->
                sprintf(buffer, format, arg1, arg2, arg3)

            arg1 is Int && arg2 is Int && arg3 is Int ->
                sprintf(buffer, format, arg1, arg2, arg3)

            arg1 is String && arg2 is String && arg3 is String ->
                sprintf(buffer, format, arg1, arg2, arg3)

            else -> {
                // 转换为字符串处理
                sprintf(buffer, format, arg1.toString(), arg2.toString(), arg3.toString())
            }
        }

        val result = buffer.toKString()
        nativeHeap.free(buffer.rawValue)
        return result
    }
}

/**
 * 字符串扩展函数
 * 根据参数数量和类型选择适当的格式化方法
 */
fun String.format(arg: Int): String = StringFormat.format(this, arg)
fun String.format(arg: Long): String = StringFormat.format(this, arg)
fun String.format(arg: Float): String = StringFormat.format(this, arg)
fun String.format(arg: Double): String = StringFormat.format(this, arg)
fun String.format(arg: String): String = StringFormat.format(this, arg)
fun String.format(arg1: Any, arg2: Any): String = StringFormat.format(this, arg1, arg2)
fun String.format(arg1: Any, arg2: Any, arg3: Any): String =
    StringFormat.format(this, arg1, arg2, arg3)
