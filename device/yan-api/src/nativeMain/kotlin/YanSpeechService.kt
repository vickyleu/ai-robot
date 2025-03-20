package com.airobot.device.yanapi

import com.airobot.pythoninterop.PyList_GetItem
import com.airobot.pythoninterop.PyList_Size
import com.airobot.pythoninterop.PyLong_AsLong
import com.airobot.pythoninterop.PyLong_FromLong
import com.airobot.pythoninterop.PyObject_IsTrue
import com.airobot.pythoninterop.PyUnicodeObject
import com.airobot.pythoninterop.PyUnicode_AsUTF8
import com.airobot.pythoninterop.Py_BuildValue
import com.airobot.pythoninterop.create_voice_asr_offline_syntax
import com.airobot.pythoninterop.delete_voice_asr_offline_syntax
import com.airobot.pythoninterop.get_robot_language
import com.airobot.pythoninterop.get_robot_volume_value
import com.airobot.pythoninterop.get_voice_asr_offline_syntax
import com.airobot.pythoninterop.get_voice_asr_offline_syntax_grammars
import com.airobot.pythoninterop.get_voice_asr_state
import com.airobot.pythoninterop.get_voice_iat
import com.airobot.pythoninterop.get_voice_tts_state_impl
import com.airobot.pythoninterop.my_Py_False
import com.airobot.pythoninterop.my_Py_True
import com.airobot.pythoninterop.set_robot_language
import com.airobot.pythoninterop.set_robot_volume_value
import com.airobot.pythoninterop.start_voice_tts_impl
import com.airobot.pythoninterop.stop_voice_asr
import com.airobot.pythoninterop.stop_voice_iat
import com.airobot.pythoninterop.stop_voice_tts
import com.airobot.pythoninterop.sync_do_tts_impl
import com.airobot.pythoninterop.sync_do_voice_asr
import com.airobot.pythoninterop.sync_do_voice_asr_value
import com.airobot.pythoninterop.sync_do_voice_iat
import com.airobot.pythoninterop.sync_do_voice_iat_value
import com.airobot.pythoninterop.update_voice_asr_offline_syntax
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.datetime.Clock

/**
 * YAN设备语音服务
 *
 * 提供机器人的语音控制功能，如语音合成、语音识别等
 */
@OptIn(ExperimentalForeignApi::class)
class YanSpeechService {
    /**
     * 文本转语音
     *
     * @param text 要转换的文本
     * @return 操作是否成功
     */
    fun textToSpeech(text: String): Boolean {
        try {
            memScoped {
                val pyText = PyUnicodeObject(text.cstr.ptr.rawValue)
                val pyInterrupt = if (true) my_Py_True() else my_Py_False()

                val pyTimestamp = PyLong_FromLong(Clock.System.now().toEpochMilliseconds())

                val result = start_voice_tts_impl(pyText.reinterpret<PyObject>().ptr, pyInterrupt,pyTimestamp, 0)
                return result != null && PyObject_IsTrue(result) == 1
            }
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 获取当前语音语言
     *
     * @return 当前语言代码，如"zh-CN"，失败返回null
     */
    fun getLanguage(): String? {
        try {
            val result = get_robot_language(0)
            if (result != null) {
                // 将PyObject转换为String
                val pyStr = PyUnicode_AsUTF8(result)
                return pyStr?.toKString()
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 设置语音语言
     *
     * @param languageCode 语言代码，如"zh-CN"、"en-US"等
     * @return 操作是否成功
     */
    fun setLanguage(languageCode: String): Boolean {
        try {
            memScoped {
                val pyLang = PyUnicodeObject(languageCode.cstr.ptr.rawValue)
                val result = set_robot_language(pyLang.reinterpret<PyObject>().ptr,0)
                return result != null && PyObject_IsTrue(result) == 1
            }
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 获取当前音量
     *
     * @return 当前音量值(0-100)，失败返回-1
     */
    fun getVolume(): Int {
        try {
            val result = get_robot_volume_value(0)
            if (result != null) {
                // 将PyObject转换为Int
                return PyLong_AsLong(result).toInt()
            }
            return -1
        } catch (e: Exception) {
            return -1
        }
    }

    /**
     * 设置音量
     *
     * @param volume 音量值，范围0-100
     * @return 操作是否成功
     */
    fun setVolume(volume: Int): Boolean {
        try {
            memScoped {
                val pyVolume = PyLong_FromLong(volume.toLong())
                val result = set_robot_volume_value(pyVolume,0)
                return result != null && PyObject_IsTrue(result) == 1
            }
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 执行语音听写
     *
     * @return 语音听写结果，包含识别状态和内容
     */
    fun syncDoVoiceIat(): Map<String, Any>? {
        try {
            val result = sync_do_voice_iat(0)
            if (result != null) {
                // 将PyObject转换为Map
                return PyObjectToMap(result)
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 执行语音听写并直接返回识别文本
     *
     * @return 识别到的文本内容，失败返回null
     */
    fun syncDoVoiceIatValue(): String? {
        try {
            val result = sync_do_voice_iat_value(0)
            if (result != null) {
                // 将PyObject转换为String
                val pyStr = PyUnicode_AsUTF8(result)
                return pyStr?.toKString()
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }


    /**
     * 同步执行语音识别
     *
     * @return 语音识别结果，包含识别状态和内容
     */
    fun syncDoVoiceAsr(): Map<String, Any>? {
        try {
            val result = sync_do_voice_asr(0)
            if (result != null) {
                return PyObjectToMap(result)
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 开始语音合成
     *
     * @param text 要合成的文本
     * @return 操作是否成功
     */
    fun startVoiceTts(text: String): Boolean {
        try {
            memScoped {
                val pyText = PyUnicodeObject(text.cstr.ptr.rawValue)
                val pyInterrupt = if (true) my_Py_True() else my_Py_False()
                val result = sync_do_tts_impl(pyText.reinterpret<PyObject>().ptr,pyInterrupt,0)
                return result != null && PyObject_IsTrue(result) == 1
            }
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 获取语音合成状态
     *
     * @return 语音合成状态信息
     */
    fun getVoiceTtsState(): Map<String, Any>? {
        try {
            val pyTimestamp = PyLong_FromLong(Clock.System.now().toEpochMilliseconds())
            val result = get_voice_tts_state_impl(pyTimestamp,0)
            if (result != null) {
                return PyObjectToMap(result)
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 停止语音合成
     *
     * @return 操作是否成功
     */
    fun stopVoiceTts(): Boolean {
        try {
            val result = stop_voice_tts(0)
            return result != null && PyObject_IsTrue(result) == 1
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 同步执行语音合成
     *
     * @param text 要合成的文本
     * @return 操作是否成功
     */
    fun syncDoVoiceTts(text: String): Boolean {
        try {
            memScoped {
                val pyText = PyUnicodeObject(text.cstr.ptr.rawValue)
                val pyInterrupt = if (true) my_Py_True() else my_Py_False()
                val pyTimestamp = PyLong_FromLong(Clock.System.now().toEpochMilliseconds())
                val result = start_voice_tts_impl(pyText.reinterpret<PyObject>().ptr, pyInterrupt,pyTimestamp, 0)
                return result != null && PyObject_IsTrue(result) == 1
            }
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 停止语音识别
     *
     * @return 操作是否成功
     */
    fun stopVoiceAsr(): Boolean {
        try {
            val result = stop_voice_asr(0)
            return result != null && PyObject_IsTrue(result) == 1
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 获取语音识别状态
     *
     * @return 语音识别状态信息
     */
    fun getVoiceAsrState(): Map<String, Any>? {
        try {
            val result = get_voice_asr_state(0)
            if (result != null) {
                return PyObjectToMap(result)
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 同步执行语音识别并返回文本值
     *
     * @return 识别到的文本内容
     */
    fun syncDoVoiceAsrValue(): String? {
        try {
            val result = sync_do_voice_asr_value(0)
            if (result != null) {
                val pyStr = PyUnicode_AsUTF8(result)
                return pyStr?.toKString()
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 删除离线语音识别语法
     *
     * @param grammarId 语法ID
     * @return 操作是否成功
     */
    fun deleteVoiceAsrOfflineSyntax(grammarId: String): Boolean {
        try {
            memScoped {
                val pyGrammarId = PyUnicodeObject(grammarId.cstr.ptr.rawValue)
                val result = delete_voice_asr_offline_syntax(pyGrammarId.reinterpret<PyObject>().ptr, 0)
                return result != null && PyObject_IsTrue(result) == 1
            }
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 获取离线语音识别语法
     *
     * @param grammarId 语法ID
     * @return 语法内容
     */
    fun getVoiceAsrOfflineSyntax(grammarId: String): Map<String, Any>? {
        try {
            memScoped {
                val pyGrammarId = PyUnicodeObject(grammarId.cstr.ptr.rawValue)
                val result = get_voice_asr_offline_syntax(pyGrammarId.reinterpret<PyObject>().ptr, 0)
                if (result != null) {
                    return PyObjectToMap(result)
                }
                return null
            }
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 创建离线语音识别语法
     *
     * @param grammar 语法内容
     * @return 操作是否成功
     */
    fun createVoiceAsrOfflineSyntax(grammar: String): Boolean {
        try {
            memScoped {
                val pyGrammar = PyUnicodeObject(grammar.cstr.ptr.rawValue)
                val result = create_voice_asr_offline_syntax(pyGrammar.reinterpret<PyObject>().ptr, 0)
                return result != null && PyObject_IsTrue(result) == 1
            }
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 更新离线语音识别语法
     *
     * @param grammar 语法内容
     * @return 操作是否成功
     */
    fun updateVoiceAsrOfflineSyntax(grammar: String): Boolean {
        try {
            memScoped {
                val pyGrammar = PyUnicodeObject(grammar.cstr.ptr.rawValue)
                val result = update_voice_asr_offline_syntax(pyGrammar.reinterpret<PyObject>().ptr, 0)
                return result != null && PyObject_IsTrue(result) == 1
            }
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 获取离线语音识别语法列表
     *
     * @return 语法列表
     */
    fun getVoiceAsrOfflineSyntaxGrammars(): List<String> {
        try {
            val result = get_voice_asr_offline_syntax_grammars(0)
            if (result != null) {
                val grammars = mutableListOf<String>()
                val size = PyList_Size(result)
                
                for (i in 0 until size) {
                    val item = PyList_GetItem(result, i)
                    if (item != null) {
                        val pyStr = PyUnicode_AsUTF8(item)
                        pyStr?.toKString()?.let { grammars.add(it) }
                    }
                }
                
                return grammars
            }
            return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }
    }

    /**
     * 停止语音听写
     *
     * @return 操作是否成功
     */
    fun stopVoiceIat(): Boolean {
        try {
            val result = stop_voice_iat(0)
            return result != null && PyObject_IsTrue(result) == 1
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 获取语音听写状态
     *
     * @return 语音听写状态信息
     */
    fun getVoiceIat(): Map<String, Any>? {
        try {
            val result = get_voice_iat(0)
            if (result != null) {
                return PyObjectToMap(result)
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }


}