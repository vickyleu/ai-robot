#include "yanshee.h"
#include "webrtc_apm_wrapper.h"
#include <stdio.h>
#include <stdlib.h>
#include <math.h>

 SoxWrapper* soxr_wrapper_create() {
    SoxWrapper* w = (SoxWrapper*)std::malloc(sizeof(SoxWrapper));
    if (!w) return nullptr;
    w->soxr_handle = nullptr;
    w->destroyed   = false;
    return w;
}

 void soxr_io_spec_create(soxr_datatype_t in_type, soxr_datatype_t out_type, SoxWrapper* w) {
    if (!w || w->destroyed) return;
    w->io_spec = soxr_io_spec(in_type, out_type);
}

 void soxr_runtime_spec_create(unsigned threads, SoxWrapper* w) {
    if (!w || w->destroyed) return;
    w->runtime_spec = soxr_runtime_spec(threads);
}

 void soxr_quality_spec_create(unsigned quality, SoxWrapper* w) {
    if (!w || w->destroyed) return;
    w->quality_spec = soxr_quality_spec(quality, 0);
}

 int soxr_wrapper_create_resampler(
        SoxWrapper* w,
        double      in_rate,
        double      out_rate,
        unsigned    channels
) {
    if (!w || w->destroyed) return -1;
    if (w->soxr_handle) {
        soxr_delete(w->soxr_handle);
        w->soxr_handle = nullptr;
    }
    soxr_error_t err;
    w->soxr_handle = soxr_create(
            in_rate, out_rate, channels,
            &err,
            &w->io_spec,
            &w->quality_spec,
            &w->runtime_spec
    );
    if (err) {
        w->soxr_handle = nullptr;
        return -1;
    }
    return 0;
}

 size_t soxr_wrapper_process_short_to_short(
        SoxWrapper*  w,
        const short* in_data,
        size_t       in_size,
        short*       out_data,
        size_t       out_size
) {
    if (!w || w->destroyed || !w->soxr_handle) return 0;
    if (!in_data || in_size == 0 || !out_data || out_size == 0) return 0;
    size_t done = 0;
    soxr_error_t err = soxr_process(
            w->soxr_handle,
            in_data, in_size, nullptr,
            out_data, out_size, &done
    );
    if (err) return 0;
    return done;
}

 void soxr_wrapper_destroy(SoxWrapper* w) {
    if (!w || w->destroyed) return;
    if (w->soxr_handle) {
        soxr_delete(w->soxr_handle);
        w->soxr_handle = nullptr;
    }
    std::free(w);
}


// RNNoise包装实现 --------------------------------------------------------------

// RNNoise默认帧大小
#define RNNOISE_FRAME_SIZE 480

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wimplicit-const-int-float-conversion"
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

    // 🔧 新增：验证RNNoise状态
    printf("[INFO] RNNoise state created: %p\n", (void*)wrapper->state);
    fflush(stdout);
    
    // 🔧 增强测试：多种信号测试RNNoise VAD功能
    printf("[INFO] 开始RNNoise VAD功能测试...\n");
    fflush(stdout);
    
    // 测试1：静音信号
    float test_silence[480] = {0};
    float output_silence[480];
    float silence_prob = rnnoise_process_frame(wrapper->state, output_silence, test_silence);
    printf("[TEST1] 静音信号 VAD概率=%.6f (期望: 接近0.0)\n", silence_prob);
    fflush(stdout);
    
    // 重置状态
    rnnoise_destroy(wrapper->state);
    wrapper->state = rnnoise_create(NULL);
    
    // 测试2：440Hz正弦波 (人声频率范围)
    float test_sine[480];
    float output_sine[480];
    for (int i = 0; i < 480; i++) {
        test_sine[i] = 0.1f * sin(2.0f * 3.14159f * 440.0f * i / 16000.0f);
    }
    float sine_prob = rnnoise_process_frame(wrapper->state, output_sine, test_sine);
    printf("[TEST2] 440Hz正弦波 VAD概率=%.6f (期望: >0.0)\n", sine_prob);
    fflush(stdout);
    
    // 重置状态
    rnnoise_destroy(wrapper->state);
    wrapper->state = rnnoise_create(NULL);
    
    // 测试3：白噪声
    float test_noise[480];
    float output_noise[480];
    for (int i = 0; i < 480; i++) {
        test_noise[i] = ((float)rand() / RAND_MAX - 0.5f) * 0.2f;  // 随机噪声
    }
    float noise_prob = rnnoise_process_frame(wrapper->state, output_noise, test_noise);
    printf("[TEST3] 白噪声 VAD概率=%.6f (期望: 可能>0.0)\n", noise_prob);
    fflush(stdout);
    
    // 重置状态
    rnnoise_destroy(wrapper->state);
    wrapper->state = rnnoise_create(NULL);
    
    // 测试4：模拟语音信号 (多频率混合)
    float test_speech[480];
    float output_speech[480];
    for (int i = 0; i < 480; i++) {
        // 混合多个频率模拟语音
        float f1 = 0.05f * sin(2.0f * 3.14159f * 200.0f * i / 16000.0f);   // 基频
        float f2 = 0.03f * sin(2.0f * 3.14159f * 400.0f * i / 16000.0f);   // 第一谐波
        float f3 = 0.02f * sin(2.0f * 3.14159f * 800.0f * i / 16000.0f);   // 第二谐波
        test_speech[i] = f1 + f2 + f3;
    }
    float speech_prob = rnnoise_process_frame(wrapper->state, output_speech, test_speech);
    printf("[TEST4] 模拟语音信号 VAD概率=%.6f (期望: >0.0)\n", speech_prob);
    fflush(stdout);
    
    // 重置状态用于正常使用
    rnnoise_destroy(wrapper->state);
    wrapper->state = rnnoise_create(NULL);
    if (!wrapper->state) {
        fprintf(stderr, "Failed to recreate RNNoise state after test\n");
        free(wrapper);
        return NULL;
    }

    // 初始化其他属性
    wrapper->vad_threshold = 0.6f;  // 默认VAD阈值
    wrapper->gain = 1.0f;           // 默认增益

    // 🔧 VAD测试结果分析
    printf("[ANALYSIS] VAD测试结果分析:\n");
    if (silence_prob == 0.0f && sine_prob == 0.0f && noise_prob == 0.0f && speech_prob == 0.0f) {
        printf("[ERROR] ❌ 所有测试信号的VAD概率都为0.0 - RNNoise VAD功能可能有问题！\n");
        printf("[ERROR] 可能原因: 1)RNNoise模型文件损坏 2)版本不兼容 3)编译问题\n");
    } else if (silence_prob < 0.1f && (sine_prob > 0.0f || speech_prob > 0.0f)) {
        printf("[INFO] ✅ RNNoise VAD功能正常 - 能够区分静音和信号\n");
    } else {
        printf("[WARN] ⚠️ RNNoise VAD行为异常 - 需要进一步调查\n");
    }
    fflush(stdout);

    printf("[INFO] RNNoise wrapper created successfully\n");
    fflush(stdout);
    return wrapper;
}
#pragma clang diagnostic pop

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
    printf("[INFO] RNNoise VAD threshold set to %.3f\n", threshold);
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
    printf("[INFO] RNNoise gain set to %.3f\n", gain);
}

int rnnoise_wrapper_process_frame(
        RNNoiseWrapper* wrapper,
        const short* in_frame,
        short* out_frame,
        float* vad_probability) {

    if (!wrapper || !wrapper->state) {
        fprintf(stderr, "[ERROR] Invalid RNNoise wrapper or state\n");
        fflush(stderr);
        return -1;
    }

    if (!in_frame || !out_frame) {
        fprintf(stderr, "[ERROR] Invalid input or output buffer\n");
        fflush(stderr);
        return -1;
    }

    // 转换输入数据到float (范围 -1 到 1)
    float input_frame[RNNOISE_FRAME_SIZE];
    float output_frame[RNNOISE_FRAME_SIZE];

    for (int i = 0; i < RNNOISE_FRAME_SIZE; i++) {
        input_frame[i] = in_frame[i] / 32768.0f;
    }

    // 🔧 增强调试：每帧都输出统计信息（前几帧）
    static int frame_counter = 0;
    frame_counter++;
    
    if (frame_counter <= 10 || frame_counter % 100 == 0) {
        // 计算输入数据的统计信息
        float min_val = input_frame[0], max_val = input_frame[0];
        float sum = 0.0f, sum_sq = 0.0f;
        int non_zero_count = 0;
        
        for (int i = 0; i < RNNOISE_FRAME_SIZE; i++) {
            float val = input_frame[i];
            if (val < min_val) min_val = val;
            if (val > max_val) max_val = val;
            sum += val;
            sum_sq += val * val;
            if (val != 0.0f) non_zero_count++;
        }
        
        float mean = sum / RNNOISE_FRAME_SIZE;
        float rms = sqrt(sum_sq / RNNOISE_FRAME_SIZE);
        
        printf("[DEBUG] RNNoise帧#%d输入统计: min=%.4f, max=%.4f, mean=%.4f, rms=%.4f, 非零样本=%d/%d\n", 
               frame_counter, min_val, max_val, mean, rms, non_zero_count, RNNOISE_FRAME_SIZE);
        fflush(stdout);
    }

    // 🔧 关键：处理音频帧并获取VAD概率
    float prob = rnnoise_process_frame(wrapper->state, output_frame, input_frame);

    // 🔧 强制输出：每帧都记录VAD概率（前几帧和定期输出）
    if (frame_counter <= 20 || frame_counter % 50 == 0) {
        printf("[INFO] RNNoise帧#%d VAD: prob=%.6f, threshold=%.6f, result=%s\n", 
               frame_counter, prob, wrapper->vad_threshold, (prob >= wrapper->vad_threshold) ? "VOICE" : "SILENCE");
        fflush(stdout);
    }
    
    // 🔧 新增：检查概率值的有效性
    if (prob < 0.0f || prob > 1.0f) {
        printf("[ERROR] RNNoise帧#%d返回无效的VAD概率: %.6f (应该在0.0-1.0范围内)\n", frame_counter, prob);
        fflush(stderr);
    }
    
    // 🔧 新增：特别关注VAD概率为0.0的情况
    static float last_prob = -1.0f;
    static int zero_count = 0;
    static int non_zero_count = 0;
    
    if (prob == 0.0f) {
        zero_count++;
        if (zero_count <= 5 || zero_count % 100 == 0) {
            printf("[WARN] RNNoise帧#%d VAD概率为0.0 (连续第%d次)\n", frame_counter, zero_count);
            fflush(stdout);
        }
    } else {
        if (zero_count > 0) {
            printf("[INFO] RNNoise帧#%d VAD非零概率: %.6f (之前连续%d帧为0.0)\n", frame_counter, prob, zero_count);
            fflush(stdout);
            zero_count = 0;
        }
        non_zero_count++;
        if (non_zero_count <= 5) {
            printf("[INFO] RNNoise帧#%d 检测到第%d个非零VAD: %.6f\n", frame_counter, non_zero_count, prob);
            fflush(stdout);
        }
    }
    last_prob = prob;

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

    // 🔧 增强调试：输出处理结果
    if (frame_counter <= 10 || frame_counter % 100 == 0) {
        // 计算输出统计
        int out_non_zero = 0;
        short out_max = 0;
        for (int i = 0; i < RNNOISE_FRAME_SIZE; i++) {
            if (out_frame[i] != 0) out_non_zero++;
            if (abs(out_frame[i]) > abs(out_max)) out_max = out_frame[i];
        }
        printf("[DEBUG] RNNoise帧#%d输出统计: 最大振幅=%d, 非零样本=%d/%d, VAD=%.6f\n", 
               frame_counter, out_max, out_non_zero, RNNOISE_FRAME_SIZE, prob);
        fflush(stdout);
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
        // 对于不足一帧的部分，直接复制到正确的位置
        const short* remaining_input = in_data + (frames * RNNOISE_FRAME_SIZE);
        short* remaining_output = out_data + (frames * RNNOISE_FRAME_SIZE);
        
        memcpy(remaining_output, remaining_input, remaining * sizeof(short));
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
