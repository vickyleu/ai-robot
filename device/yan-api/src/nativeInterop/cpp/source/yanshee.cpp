#include "yanshee.h"


// SOXR包装实现 ----------------------------------------------------------------

SoxWrapper* soxr_wrapper_create() {
    SoxWrapper* wrapper = (SoxWrapper*)malloc(sizeof(SoxWrapper));
    if (!wrapper) {
        fprintf(stderr, "Failed to allocate SoxWrapper\n");
        return NULL;
    }

    // 分配各种规格结构体内存
    wrapper->io_spec = (soxr_io_spec_t*)malloc(sizeof(soxr_io_spec_t));
    wrapper->runtime_spec = (soxr_runtime_spec_t*)malloc(sizeof(soxr_runtime_spec_t));
    wrapper->quality_spec = (soxr_quality_spec_t*)malloc(sizeof(soxr_quality_spec_t));
    wrapper->soxr = NULL;

    if (!wrapper->io_spec || !wrapper->runtime_spec || !wrapper->quality_spec) {
        soxr_wrapper_destroy(wrapper);
        return NULL;
    }

    return wrapper;
}

void soxr_io_spec_create(soxr_datatype_t itype, soxr_datatype_t otype, SoxWrapper* wrapper) {
    if (!wrapper || !wrapper->io_spec) {
        fprintf(stderr, "Invalid SoxWrapper or io_spec is NULL\n");
        return;
    }

    // 使用原始soxr函数创建io规格
    *(wrapper->io_spec) = soxr_io_spec(itype, otype);
}

void soxr_runtime_spec_create(unsigned num_threads, SoxWrapper* wrapper) {
    if (!wrapper || !wrapper->runtime_spec) {
        fprintf(stderr, "Invalid SoxWrapper or runtime_spec is NULL\n");
        return;
    }

    // 使用原始soxr函数创建运行时规格
    *(wrapper->runtime_spec) = soxr_runtime_spec(num_threads);
}

void soxr_quality_spec_create(unsigned quality, SoxWrapper* wrapper) {
    if (!wrapper || !wrapper->quality_spec) {
        fprintf(stderr, "Invalid SoxWrapper or quality_spec is NULL\n");
        return;
    }

    // 使用原始soxr函数创建质量规格
    *(wrapper->quality_spec) = soxr_quality_spec(quality, 0);
}

int soxr_wrapper_create_resampler(
        SoxWrapper* wrapper,
        double input_rate,
        double output_rate) {

    if (!wrapper) {
        fprintf(stderr, "Invalid SoxWrapper\n");
        return -1;
    }

    // 释放可能存在的旧实例
    if (wrapper->soxr) {
        soxr_delete(wrapper->soxr);
        wrapper->soxr = NULL;
    }

    // 创建错误信息缓存
    soxr_error_t error;

    // 使用规格创建重采样器
    wrapper->soxr = soxr_create(
            input_rate, output_rate, 1,  // 采样率和通道数
            &error,
            wrapper->io_spec,
            wrapper->quality_spec,
            wrapper->runtime_spec
    );

    if (error) {
        fprintf(stderr, "SOXR create error: %s\n", soxr_strerror(error));
        return -1;
    }

    return 0;
}

size_t soxr_wrapper_process(
        SoxWrapper* wrapper,
        const short* in_data,
        size_t in_size,
        float* out_data,
        size_t out_size) {

    if (!wrapper || !wrapper->soxr) {
        fprintf(stderr, "Invalid SoxWrapper or resampler not initialized\n");
        return 0;
    }

    size_t done = 0;
    soxr_error_t error;

    error = soxr_process(
            wrapper->soxr,
            in_data, in_size / sizeof(short), NULL,
            out_data, out_size / sizeof(short), &done
    );

    if (error) {
        fprintf(stderr, "SOXR process error: %s\n", soxr_strerror(error));
        return 0;
    }

    return done;
}

void soxr_wrapper_destroy(SoxWrapper* wrapper) {
    if (!wrapper) return;

    if (wrapper->soxr) soxr_delete(wrapper->soxr);

    if (wrapper->io_spec) free(wrapper->io_spec);
    if (wrapper->runtime_spec) free(wrapper->runtime_spec);
    if (wrapper->quality_spec) free(wrapper->quality_spec);

    free(wrapper);
}

// RNNoise包装实现 --------------------------------------------------------------

// RNNoise默认帧大小
#define RNNOISE_FRAME_SIZE 480

RNNoiseWrapper* rnnoise_wrapper_create() {
    RNNoiseWrapper* wrapper = (RNNoiseWrapper*)malloc(sizeof(RNNoiseWrapper));
    if (!wrapper) {
        fprintf(stderr, "Failed to allocate RNNoiseWrapper\n");
        return NULL;
    }

    // 初始化RNNoise状态
    wrapper->state = rnnoise_create(NULL);
    if (!wrapper->state) {
        fprintf(stderr, "Failed to create RNNoise state\n");
        free(wrapper);
        return NULL;
    }

    // 初始化其他属性
    wrapper->vad_threshold = 0.6f;  // 默认VAD阈值
    wrapper->gain = 1.0f;           // 默认增益

    printf("[INFO] RNNoise wrapper created successfully\n");
    return wrapper;
}

void rnnoise_wrapper_destroy(RNNoiseWrapper* wrapper) {
    if (!wrapper) return;

    if (wrapper->state) {
        rnnoise_destroy(wrapper->state);
        wrapper->state = NULL;
    }

    free(wrapper);
    printf("[INFO] RNNoise wrapper destroyed\n");
}

void rnnoise_wrapper_reset(RNNoiseWrapper* wrapper) {
    if (!wrapper || !wrapper->state) {
        fprintf(stderr, "Invalid RNNoise wrapper or state\n");
        return;
    }

    // 释放旧状态并创建新状态
    rnnoise_destroy(wrapper->state);
    wrapper->state = rnnoise_create(NULL);
    
    if (!wrapper->state) {
        fprintf(stderr, "Failed to reset RNNoise state\n");
        return;
    }
    
    printf("[INFO] RNNoise state reset successfully\n");
}

void rnnoise_wrapper_set_vad_threshold(RNNoiseWrapper* wrapper, float threshold) {
    if (!wrapper) {
        fprintf(stderr, "Invalid RNNoise wrapper\n");
        return;
    }

    // 限制阈值在有效范围内
    if (threshold < 0.0f) threshold = 0.0f;
    if (threshold > 1.0f) threshold = 1.0f;

    wrapper->vad_threshold = threshold;
//    printf("[INFO] RNNoise VAD threshold set to %.2f\n", threshold);
}

void rnnoise_wrapper_set_gain(RNNoiseWrapper* wrapper, float gain) {
    if (!wrapper) {
        fprintf(stderr, "Invalid RNNoise wrapper\n");
        return;
    }

    // 限制增益在合理范围内
    if (gain < 0.1f) gain = 0.1f;
    if (gain > 5.0f) gain = 5.0f;

    wrapper->gain = gain;
//    printf("[INFO] RNNoise gain set to %.2f\n", gain);
}

int rnnoise_wrapper_process_frame(
        RNNoiseWrapper* wrapper,
        const short* in_frame,
        short* out_frame,
        float* vad_probability) {
    
    if (!wrapper || !wrapper->state) {
        fprintf(stderr, "Invalid RNNoise wrapper or state\n");
        return -1;
    }

    if (!in_frame || !out_frame) {
        fprintf(stderr, "Invalid input or output buffer\n");
        return -1;
    }

    // 转换输入数据到float (范围 -1 到 1)
    float input_frame[RNNOISE_FRAME_SIZE];
    float output_frame[RNNOISE_FRAME_SIZE];
    
    for (int i = 0; i < RNNOISE_FRAME_SIZE; i++) {
        input_frame[i] = in_frame[i] / 32768.0f;
    }

    // 处理音频帧并获取VAD概率
    float prob = rnnoise_process_frame(wrapper->state, output_frame, input_frame);
    
    // 将输出转换回短整型，并应用增益
    for (int i = 0; i < RNNOISE_FRAME_SIZE; i++) {
        float sample = output_frame[i] * wrapper->gain;
        // 限制在 [-1, 1] 范围内
        if (sample > 1.0f) sample = 1.0f;
        if (sample < -1.0f) sample = -1.0f;
        // 转换回 short 范围
        out_frame[i] = (short)(sample * 32767.0f);
    }

    // 输出VAD概率
    if (vad_probability) {
        *vad_probability = prob;
    }

    // 返回是否检测到语音活动
    return (prob >= wrapper->vad_threshold) ? 1 : 0;
}

int rnnoise_wrapper_process(
        RNNoiseWrapper* wrapper,
        const short* in_data,
        short* out_data,
        int in_size,
        float* vad_probabilities,
        int max_vad_values) {
    
    if (!wrapper || !wrapper->state) {
        fprintf(stderr, "Invalid RNNoise wrapper or state\n");
        return -1;
    }

    if (!in_data || !out_data) {
        fprintf(stderr, "Invalid input or output buffer\n");
        return -1;
    }

    // 计算有多少完整帧
    int frames = in_size / RNNOISE_FRAME_SIZE;
    int vad_count = 0;
    int voice_frames = 0;

    // 逐帧处理
    for (int i = 0; i < frames && i < max_vad_values; i++) {
        const short* in_frame = in_data + (i * RNNOISE_FRAME_SIZE);
        short* out_frame = out_data + (i * RNNOISE_FRAME_SIZE);
        float vad_prob = 0.0f;

        // 处理一帧
        int has_voice = rnnoise_wrapper_process_frame(
            wrapper, 
            in_frame, 
            out_frame, 
            &vad_prob
        );

        // 更新VAD计数和概率数组
        if (has_voice) {
            voice_frames++;
        }

        if (vad_probabilities && vad_count < max_vad_values) {
            vad_probabilities[vad_count++] = vad_prob;
        }
    }

    // 处理可能的剩余样本（不足一帧的部分）
    int remaining = in_size % RNNOISE_FRAME_SIZE;
    if (remaining > 0) {
        // 对于不足一帧的部分，直接复制
        memcpy(
            out_data + (frames * RNNOISE_FRAME_SIZE),
            in_data + (frames * RNNOISE_FRAME_SIZE),
            remaining * sizeof(short)
        );
    }

    // 返回处理的完整帧数
    return frames;
}

// 批量处理函数 - 适用于需要处理大块音频数据的情况
int rnnoise_wrapper_process_batch(
        RNNoiseWrapper* wrapper,
        const short* in_data,
        short* out_data,
        int sample_count,
        int* voice_frames_detected) {
    
    if (!wrapper || !wrapper->state || !in_data || !out_data) {
        fprintf(stderr, "Invalid parameters for batch processing\n");
        return -1;
    }

    // 计算总帧数
    int total_frames = sample_count / RNNOISE_FRAME_SIZE;
    int detected_voice_frames = 0;
    float vad_prob = 0.0f;

    // 逐帧处理
    for (int i = 0; i < total_frames; i++) {
        const short* in_frame = in_data + (i * RNNOISE_FRAME_SIZE);
        short* out_frame = out_data + (i * RNNOISE_FRAME_SIZE);

        // 处理一帧
        int has_voice = rnnoise_wrapper_process_frame(
            wrapper, 
            in_frame, 
            out_frame, 
            &vad_prob
        );

        if (has_voice) {
            detected_voice_frames++;
        }
    }

    // 处理剩余样本
    int remaining = sample_count % RNNOISE_FRAME_SIZE;
    if (remaining > 0) {
        memcpy(
            out_data + (total_frames * RNNOISE_FRAME_SIZE),
            in_data + (total_frames * RNNOISE_FRAME_SIZE),
            remaining * sizeof(short)
        );
    }

    // 设置检测到的语音帧数
    if (voice_frames_detected) {
        *voice_frames_detected = detected_voice_frames;
    }

    return total_frames;
}
