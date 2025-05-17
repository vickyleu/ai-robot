/**
 * 工具类兼容层
 * 确保现有代码在迁移过程中可以继续工作
 */
package voice.interf.util

import voice.api.DynamicCallback

/**
 * @deprecated 使用 voice.api.DynamicCallback 替代
 */
@Deprecated("使用 voice.api.DynamicCallback 替代", ReplaceWith("voice.api.DynamicCallback"))
typealias DynamicCallback<T> = voice.api.DynamicCallback<T> 