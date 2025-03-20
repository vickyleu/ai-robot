package com.airobot.device.yanapi

import com.airobot.pythoninterop.PyObject_IsTrue
import com.airobot.pythoninterop.PyUnicode_AsUTF8
import com.airobot.pythoninterop.PyUnicode_FromString
import com.airobot.pythoninterop.get_button_led_color_value
import com.airobot.pythoninterop.get_button_led_mode_value
import com.airobot.pythoninterop.get_eye_led_color_value
import com.airobot.pythoninterop.get_eye_led_mode_value
import com.airobot.pythoninterop.get_robot_led
import com.airobot.pythoninterop.set_robot_led
import com.airobot.pythoninterop.sync_set_led
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString

/**
 * YAN设备灯光服务
 *
 * 提供机器人的灯光控制功能，如设置灯光颜色、开关灯等
 */
@OptIn(ExperimentalForeignApi::class)
class YanLightService {
    /**
     * 设置灯光
     * 注意：使用set_robot_led函数实现
     * @param type 灯光类型
     * @param color 灯光颜色
     * @param mode 灯光模式
     * @return 操作是否成功
     */
    fun setLight(type: LightType, color: LightColor, mode: LightMode = LightMode.on): Boolean {
        try {
            if (color.modeLimit != null) {
                if (color.modeLimit != type) {
                    return false
                }
            }
            memScoped {
                val pyType = PyUnicode_FromString(type.value)
                val pyColor = PyUnicode_FromString(color.color)
                val pyMode = PyUnicode_FromString(mode.mode)
                val result = set_robot_led(pyType, pyColor, pyMode, 0)
                return result != null && PyObject_IsTrue(result) == 1
            }
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 关闭灯光
     *
     * 注意：使用set_robot_led函数实现
     *
     * @return 操作是否成功
     */
    fun turnOff(): Boolean {
        try {
            memScoped {
                val pyType = PyUnicode_FromString(LightType.BUTTON.value)
                val pyColor = PyUnicode_FromString(LightColor.white.color)
                val pyMode = PyUnicode_FromString(LightMode.off.mode)
                val result = set_robot_led(pyType, pyColor, pyMode, 0)
                return result != null && PyObject_IsTrue(result) == 1
            }
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 获取眼睛LED颜色值
     *
     * @return 眼睛LED颜色值
     */
    fun getEyeLedColorValue(): LightColor? {
        try {
            val result = get_eye_led_color_value(0)
            if (result != null) {
                val cstr = PyUnicode_AsUTF8(result)
                return cstr?.toKString()?.let { it2 ->
                    return LightColor.entries.find { it.color == it2 }
                }
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 获取眼睛LED模式值
     *
     * @return 眼睛LED模式值
     */
    fun getEyeLedModeValue(): LightMode? {
        try {
            val result = get_eye_led_mode_value(0)
            if (result != null) {
                val cstr = PyUnicode_AsUTF8(result)
                return cstr?.toKString()?.let { it2 ->
                    return LightMode.entries.find { it.mode == it2 }
                }
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 获取灯光状态
     * @return 灯光状态信息, 例如： {type: "button", color: "white", mode: "on"}
     */
    fun getLightStatus(): Map<String, Any> {
        try {
            val result = get_robot_led(0)
            if (result != null) {
                return PyObjectToMap(result)
            }
            return emptyMap()
        } catch (e: Exception) {
            return emptyMap()
        }
    }

    /**
     * 获取按钮LED颜色值
     *
     * @return 按钮LED颜色值
     */
    fun getButtonLedColorValue(): LightColor? {
        try {
            val result = get_button_led_color_value(0)
            if (result != null) {
                val cstr = PyUnicode_AsUTF8(result)
                return cstr?.toKString()?.let { it2 ->
                    return LightColor.entries.find { it.color == it2 }
                }
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 获取按钮LED模式值
     *
     * @return 按钮LED模式值
     */
    fun getButtonLedModeValue(): LightMode? {
        try {
            val result = get_button_led_mode_value(0)
            if (result != null) {
                val cstr = PyUnicode_AsUTF8(result)
                return cstr?.toKString()?.let { it2 ->
                    return LightMode.entries.find { it.mode == it2 }
                }
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 同步设置灯光
     *
     * @param position 灯光位置，如"head", "chest", "arm"等
     * @param color RGB颜色值
     * @param mode 灯光模式
     * @return 操作是否成功
     */
    fun syncSetLight(type: LightType, color: LightColor, mode: LightMode = LightMode.on): Boolean {
        try {
            if (color.modeLimit != null) {
                if (color.modeLimit != type) {
                    return false
                }
            }
            memScoped {
                val pyType = PyUnicode_FromString(type.value)
                val pyColor = PyUnicode_FromString(color.color)
                val pyMode = PyUnicode_FromString(mode.mode)
                val result = sync_set_led(pyType, pyColor, pyMode, 0)
                return result != null && PyObject_IsTrue(result) == 1
            }
        } catch (e: Exception) {
            return false
        }
    }
}

enum class LightType(val value: String) {
    BUTTON("button"),
    CAMERA("camera")
}

enum class LightColor(val color: String, val modeLimit: LightType? = null) {
    /**
     * 取值范围如下：white/red/green/blue/yellow/purple/cyan，type camera 时，取值范围如下:red/green/blue Default: white
     */
    white("white", LightType.BUTTON),
    red("red"),
    green("green"),
    blue("blue"),
    yellow("yellow", LightType.BUTTON),
    purple("purple", LightType.BUTTON),
    cyan("cyan", LightType.BUTTON);
}

enum class LightMode(val mode: String) {
    /**
     * 取值范围如下：on/off/blink/breath ,type为 camera 时，取值范围如下:on/off/blink Default: on
     */
    on("on"),
    off("off"),
    blink("blink"),
    breath("breath");
}
