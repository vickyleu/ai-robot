package com.airobot.device.yanapi

import androidx.annotation.IntRange
import com.airobot.device.yanapi.Servo.ServoAngle.OTHER
import com.airobot.device.yanapi.Servo.ServoAngle.valueOf
import com.airobot.device.yanapi.Servo.ServoMode
import com.airobot.device.yanapi.Servo.ServoName
import com.airobot.pythoninterop.PyBool_FromLong
import com.airobot.pythoninterop.PyDict_New
import com.airobot.pythoninterop.PyDict_SetItem
import com.airobot.pythoninterop.PyList_New
import com.airobot.pythoninterop.PyList_SetItem
import com.airobot.pythoninterop.PyLong_AsLong
import com.airobot.pythoninterop.PyLong_FromLong
import com.airobot.pythoninterop.PyObject_IsTrue
import com.airobot.pythoninterop.PyUnicode_FromString
import com.airobot.pythoninterop.get_servo_angle_value
import com.airobot.pythoninterop.get_servos_mode
import com.airobot.pythoninterop.set_servos_angles_layers
import com.airobot.pythoninterop.set_servos_mode
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped

/**
 * YAN设备舵机服务
 *
 * 提供机器人的舵机控制功能，如关节控制、姿态调整等
 */
@OptIn(ExperimentalForeignApi::class)
class YanServoService {
    /**
     * 设置舵机角度
     *
     * @param servo 舵机数组,包含舵机名、角度、是否需要贝塞尔曲线、运行时间
     * @return 操作是否成功
     */
    fun setServoAngle(vararg servo: Servo): Boolean {
        if (servo.isEmpty()) return false
        try {
            memScoped {
                // 创建一个包含舵机ID和角度的字典
                val pyDict = PyDict_New()
                servo.onEach {
                    val pyChildDict = PyDict_New()
                    val servoName = PyUnicode_FromString(it.servoConversion.name)
                    val angle = PyLong_FromLong(it.safeAngelValue.toLong())
                    val isNeedBessel = PyBool_FromLong(if (it.isNeedBessel) 1 else 0)
                    val runtime = PyLong_FromLong(it.runtime.toLong())
                    PyDict_SetItem(pyChildDict, PyUnicode_FromString("angle"), angle)
                    PyDict_SetItem(pyChildDict, PyUnicode_FromString("isNeedBessel"), isNeedBessel)
                    PyDict_SetItem(pyChildDict, PyUnicode_FromString("runtime"), runtime)
                    PyDict_SetItem(pyDict, servoName, pyChildDict)
                }
                // 使用set_servos_angles_layers函数设置舵机角度
                val result = set_servos_angles_layers(pyDict, 0)
                return result != null && PyObject_IsTrue(result) == 1
            }
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 获取舵机角度
     *
     * @param servoId 舵机ID
     * @return 当前角度值，失败返回-1
     */
    fun getServoAngle(servoId: ServoName): Int {
        try {
            memScoped {
                val pyServoId = PyUnicode_FromString(servoId.upperName)
                val result = get_servo_angle_value(pyServoId, 0)
                if (result != null) {
                    return PyLong_AsLong(result).toInt()
                }
                return -1
            }
        } catch (e: Exception) {
            return -1
        }
    }

    /**
     * 获取舵机模式
     *
     * @param servoIds 舵机ID列表
     * @return 舵机模式信息
     */
    fun getServosMode(servoIds: List<ServoName>): Map<String, Any> {
        try {
            memScoped {
                // 创建一个包含舵机ID的列表
                val pyList = PyList_New(servoIds.size.toLong())
                for (i in servoIds.indices) {
                    val pyServoId = PyUnicode_FromString(servoIds[i].upperName)
                    PyList_SetItem(pyList, i.toLong(), pyServoId)
                }
                // 使用get_servos_mode函数获取舵机模式
                val result = get_servos_mode(pyList, 0)
                if (result != null) {
                    return PyObjectToMap(result)
                }
                return emptyMap()
            }
        } catch (e: Exception) {
            return emptyMap()
        }
    }

    /**
     * 设置舵机模式
     *
     * @param servoIds 舵机ID列表
     * @param mode 模式值
     * @return 操作是否成功
     */
    fun setServosMode(servoIds: List<ServoName>, mode: ServoMode): Boolean {
        try {
            memScoped {
                // 创建一个包含舵机ID的列表
                val pyList = PyList_New(servoIds.size.toLong())
                for (i in servoIds.indices) {
                    val pyServoId = PyUnicode_FromString(servoIds[i].upperName)
                    PyList_SetItem(pyList, i.toLong(), pyServoId)
                }
                val pyMode =PyUnicode_FromString(mode.modeName)
                // 使用set_servos_mode函数设置舵机模式
                val result = set_servos_mode(pyList, pyMode, 0)
                return result != null && PyObject_IsTrue(result) == 1
            }
        } catch (e: Exception) {
            return false
        }
    }
}


@Suppress("NonAsciiCharacters","EnumEntryName","UNUSED_PARAMETER","UNUSED")
data class Servo(
    val servoName: ServoName,//舵机名
    @IntRange(from = 0, to = 180)
    private val angel: Int,//角度：【0-180】
    @IntRange(from = 200, to = 4000)
    val runtime: Int = 200,//minimum:200 maximum:4000 运行时间，单位ms
    val isNeedBessel: Boolean = true //是否需要变速运动(贝塞尔曲线)
) {
    private val safeAngel: Pair<Int, Int>
        get() = (try {
            servoConversion
        } catch (e: Exception) {
            OTHER
        }).let {
            it.minAngle to it.maxAngle
        }

    /**
     * 舵机编号转换
     */
    val servoConversion: ServoAngle
        get() = ServoAngle.entries[servoName.ordinal]

    val safeAngelValue: Int
        get() = angel.coerceIn(safeAngel.first, safeAngel.second)

    /**
     * 舵机名
     * @property upperName 大写名
     * @property lowerName 小写名
     * @property chineseName 中文名
     */
    enum class ServoName(
        val upperName: String = "",
                         val lowerName: String = "",
        val chineseName: String = ""
    ) {
        右肩水平("RightShoulderRoll", "right_shoulder_roll","右肩水平"),
        右肩上下(
            "RightShoulderFlex",
            "right_shoulder_flex","右肩上下"
        ),
        右肘("RightElbowFlex", "right_elbow_flex","右肘"),
        左肩水平(
            "LeftShoulderRoll",
            "left_shoulder_roll","左肩水平"
        ),
        左肩上下("LeftShoulderFlex", "left_shoulder_flex","左肩上下"),
        左肘(
            "LeftElbowFlex",
            "left_elbow_flex","左肘"
        ),
        右髋水平("RightHipLR", "right_hip_lr","右髋水平"),
        右髋前后(
            "RightHipFB",
            "right_hip_fb","右髋前后"
        ),
        右膝("RightKneeFlex", "right_knee_flex","右膝"),
        右踝前后(
            "RightAnkleFB",
            "right_ankle_fb","右踝前后"
        ),
        右踝上下("RightAnkleUD", "right_ankle_ud","右踝上下"),
        左髋水平(
            "LeftHipLR",
            "left_hip_lr","左髋水平"
        ),
        左髋前后("LeftHipFB", "left_hip_fb","左髋前后"),
        左膝(
            "LeftKneeFlex",
            "left_knee_flex","左膝"
        ),
        左踝前后("LeftAnkleFB", "left_ankle_fb","左踝前后"),
        左踝上下(
            "LeftAnkleUD",
            "left_ankle_ud","左踝上下"
        ),
        颈部水平("NeckLR", "neck_lr","颈部水平"),
        OTHER();
    }

    enum class ServoAngle(val minAngle: Int = 0, val maxAngle: Int) {
        ID1(0, 180), ID2(0, 180), ID3(0, 180), ID4(0, 180), ID5(0, 180), ID6(0, 180), ID7(
            0,
            120
        ),
        ID8(10, 180), ID9(0, 180), ID10(0, 180),
        ID11(65, 180), ID12(60, 180), ID13(0, 170), ID14(0, 180), ID15(0, 180), ID16(0, 115), ID17(
            15,
            165
        ),
        OTHER(-1, -1);
    }

    /**
     * 舵机模式
     * @property mode 模式值
     * @property modeName 模式名称
     */
    enum class ServoMode(val mode: Int,val modeName: String) {
        WORK(0,"work"),
        PROGRAM(1,"program"),
        UNKNOWN(2,"unknown"),
        NONSUPPORT(3,"nonsupport");
    }
}
