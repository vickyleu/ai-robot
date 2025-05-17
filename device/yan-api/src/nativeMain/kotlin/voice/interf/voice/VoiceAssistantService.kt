/**
 * 语音助手服务兼容层
 */
package voice.interf.voice

import voice.api.assistant.IVoiceAssistant

/**
 * @deprecated 使用 voice.api.assistant.IVoiceAssistant 替代
 */
@Deprecated("使用 voice.api.assistant.IVoiceAssistant 替代", ReplaceWith("voice.api.assistant.IVoiceAssistant"))
typealias VoiceAssistantService = IVoiceAssistant 