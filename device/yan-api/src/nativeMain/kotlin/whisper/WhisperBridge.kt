@file:OptIn(ExperimentalForeignApi::class)
//underscores
@file:Suppress(
    "SpellCheckingInspection",
    "FunctionName",
    "FunctionNaming",
    "ParameterName",
    "UNUSED",
    "ParameterNaming",
    "UNUSED_PARAMETER"
)

package com.airobot.device.yanapi.whisper

import com.airobot.whisperinterop.my_whisper_full
import com.airobot.whisperinterop.my_whisper_full_default_params
import com.airobot.whisperinterop.my_whisper_full_default_params_by_ref
import com.airobot.whisperinterop.my_whisper_full_parallel
import com.airobot.whisperinterop.my_whisper_full_with_state
import com.airobot.whisperinterop.my_whisper_init_from_buffer_with_params
import com.airobot.whisperinterop.my_whisper_init_from_buffer_with_params_no_state
import com.airobot.whisperinterop.my_whisper_init_from_file_with_params
import com.airobot.whisperinterop.my_whisper_init_from_file_with_params_no_state
import com.airobot.whisperinterop.my_whisper_init_with_params
import com.airobot.whisperinterop.my_whisper_init_with_params_no_state
import com.airobot.whisperinterop.my_whisper_token_to_str
import com.airobot.whisperinterop.whisper_context
import com.airobot.whisperinterop.whisper_context_params
import com.airobot.whisperinterop.whisper_full_params
import com.airobot.whisperinterop.whisper_model_loader
import com.airobot.whisperinterop.whisper_sampling_strategy
import com.airobot.whisperinterop.whisper_state
import kotlinx.cinterop.ByteVarOf
import kotlinx.cinterop.CEnumVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.CValue
import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVarOf
import kotlinx.cinterop.IntVarOf


/**
 * 绕过kotlin限制, 并保留原有的函数签名
 * 方法来自于whisper_c_api.h的伪函数
 * @see my_whisper_init_from_file_with_params
 * @see my_whisper_init_from_buffer_with_params
 * @see my_whisper_init_with_params
 * @see my_whisper_init_from_file_with_params_no_state
 * @see my_whisper_init_from_buffer_with_params_no_state
 * @see my_whisper_init_with_params_no_state
 * @see my_whisper_token_to_str
 * @see my_whisper_full
 * @see my_whisper_full_with_state
 * @see my_whisper_full_parallel
 */


/**
 * @see my_whisper_init_from_file_with_params
 */
fun whisper_init_from_file_with_params(
    pathModel: String?,
    params: CValuesRef<whisper_context_params>?
): CPointer<whisper_context>? {
    return my_whisper_init_from_file_with_params(pathModel, params)
}

/**
 * @see my_whisper_init_from_buffer_with_params
 */
fun whisper_init_from_buffer_with_params(
    buffer: CValuesRef<*>?,
    buffer_size: UInt,
    params: CValuesRef<whisper_context_params>?
): CPointer<whisper_context>? {
    return my_whisper_init_from_buffer_with_params(buffer, buffer_size, params)
}

/**
 * @see my_whisper_init_with_params
 */
fun whisper_init_with_params(
    loader: CValuesRef<whisper_model_loader>?,
    params: CValuesRef<whisper_context_params>?
): CPointer<whisper_context>? {
    return my_whisper_init_with_params(loader, params)
}

/**
 * @see my_whisper_init_from_file_with_params_no_state
 */
fun whisper_init_from_file_with_params_no_state(
    pathModel: String?,
    params: CValuesRef<whisper_context_params>?
): CPointer<whisper_context>? {
    return my_whisper_init_from_file_with_params_no_state(pathModel, params)
}

/**
 * @see my_whisper_init_from_buffer_with_params_no_state
 */
fun whisper_init_from_buffer_with_params_no_state(
    buffer: CValuesRef<*>?,
    buffer_size: UInt,
    params: CValuesRef<whisper_context_params>?
): CPointer<whisper_context>? {
    return my_whisper_init_from_buffer_with_params_no_state(buffer, buffer_size, params)
}

/**
 * @see my_whisper_init_with_params_no_state
 */
fun whisper_init_with_params_no_state(
    loader: CValuesRef<whisper_model_loader>?,
    params: CValuesRef<whisper_context_params>?
): CPointer<whisper_context>? {
    return my_whisper_init_with_params_no_state(loader, params)
}

/**
 *  @see my_whisper_token_to_str
 */
fun whisper_token_to_str(
    ctx: CValuesRef<whisper_context>?,
    token: CValuesRef<IntVarOf<Int>>?
): CPointer<ByteVarOf<Byte>>? {
    return my_whisper_token_to_str(ctx, token)
}

/**
 *  @see my_whisper_full
 */
fun whisper_full(
    ctx: CValuesRef<whisper_context>?,
    params: CValuesRef<whisper_full_params>?,
    samples: CValuesRef<FloatVarOf<Float>>?,
    n_samples: Int
): Int {
    return my_whisper_full(ctx, params, samples, n_samples)
}

fun whisper_full_default_params_by_ref(
    strategy:CValuesRef<whisper_sampling_strategy.Var>?
):  CPointer<whisper_full_params>?{
    return my_whisper_full_default_params_by_ref(strategy)
}
fun whisper_full_default_params(
    strategy:CValuesRef<whisper_sampling_strategy.Var>?
):  CValue<whisper_full_params>?{
    return my_whisper_full_default_params(strategy)
}



/**
 * @see my_whisper_full_with_state
 */
fun whisper_full_with_state(
    ctx: CValuesRef<whisper_context>?,
    state: CValuesRef<whisper_state>,
    params: CValuesRef<whisper_full_params>?,
    samples: CValuesRef<FloatVarOf<Float>>?,
    n_samples: Int
): Int {
    return my_whisper_full_with_state(ctx, state, params, samples, n_samples)
}

/**
 * @see my_whisper_full_parallel
 */
fun whisper_full_parallel(
    ctx: CValuesRef<whisper_context>?,
    params: CValuesRef<whisper_full_params>?,
    samples: CValuesRef<FloatVarOf<Float>>?,
    n_samples: Int,
    n_processors: Int
): Int {
    return my_whisper_full_parallel(ctx, params, samples, n_samples, n_processors)
}
