#ifndef YANSHEE_H
#define YANSHEE_H
#include <string>
#include "soxr/soxr.h"
#include "rnnoise/rnnoise.h"
#include <string.h>

#ifdef __cplusplus
extern "C" {
#endif

// SOXR包装器 -------------------------------------------------------------------

struct SoxWrapper {
    soxr_io_spec_t      io_spec;         // 直接存为值类型
    soxr_runtime_spec_t runtime_spec;    // 直接存为值类型
    soxr_quality_spec_t quality_spec;    // 直接存为值类型
    soxr_t              soxr_handle;     // soxr_create 返回的句柄
    int                destroyed = 0; // 标记是否已经销毁
};

// 创建和释放
SoxWrapper *soxr_wrapper_create();

void soxr_wrapper_destroy(SoxWrapper *wrapper);

// 配置函数
void soxr_io_spec_create(soxr_datatype_t itype, soxr_datatype_t otype, SoxWrapper *wrapper);

void soxr_runtime_spec_create(unsigned num_threads, SoxWrapper *wrapper);

void soxr_quality_spec_create(unsigned quality, SoxWrapper *wrapper);

// 创建重采样器
int soxr_wrapper_create_resampler(
        SoxWrapper *wrapper,
        double input_rate,
        double output_rate,unsigned channels);

// 执行重采样
size_t soxr_wrapper_process(
        SoxWrapper *wrapper,
        const short *in_data,
        size_t in_size,
        float *out_data,
        size_t out_size);

// 执行重采样：float输入到short输出 - 新增
size_t soxr_wrapper_process_float_to_short(
        SoxWrapper *wrapper,
        const float *in_data,
        size_t in_size,
        short *out_data,
        size_t out_size);

// 执行重采样：float输入到float输出 - 新增
size_t soxr_wrapper_process_float_to_float(
        SoxWrapper *wrapper,
        const float *in_data,
        size_t in_size,
        float *out_data,
        size_t out_size);

// 执行重采样：short输入到short输出 - 新增函数解决数据类型不匹配问题
size_t soxr_wrapper_process_short_to_short(
        SoxWrapper *wrapper,
        const short *in_data,
        size_t in_size,
        short *out_data,
        size_t out_size);


// RNNoise包装器 -----------------------------------------------------------------

// 前向声明RNNoise状态类型
typedef struct DenoiseState DenoiseState;

// RNNoise包装结构体
struct RNNoiseWrapper {
    DenoiseState* state;   // RNNoise内部状态
    float vad_threshold;   // VAD检测阈值 (0.0-1.0)
    float gain;            // 输出增益
};

// 创建和释放RNNoise包装器
RNNoiseWrapper* rnnoise_wrapper_create();
void rnnoise_wrapper_destroy(RNNoiseWrapper* wrapper);

// 重置状态
void rnnoise_wrapper_reset(RNNoiseWrapper* wrapper);

// 设置VAD检测阈值 (0.0-1.0)
void rnnoise_wrapper_set_vad_threshold(RNNoiseWrapper* wrapper, float threshold);

// 设置输出增益
void rnnoise_wrapper_set_gain(RNNoiseWrapper* wrapper, float gain);

// 处理单个音频帧 (默认帧大小为480样本)
// 返回: -1表示错误，0表示无语音，1表示有语音
int rnnoise_wrapper_process_frame(
        RNNoiseWrapper* wrapper,
        const short* in_frame,
        short* out_frame,
        float* vad_probability);  // 可选，传NULL表示不需要VAD概率

// 处理一段音频数据
// 返回: 处理的完整帧数，-1表示错误
int rnnoise_wrapper_process(
        RNNoiseWrapper* wrapper,
        const short* in_data,
        short* out_data,
        int in_size,              // 输入样本数
        float* vad_probabilities, // 可选，存储每帧的VAD概率
        int max_vad_values);      // vad_probabilities数组大小

// 批量处理更大块的音频
// 返回: 处理的完整帧数，-1表示错误
int rnnoise_wrapper_process_batch(
        RNNoiseWrapper* wrapper,
        const short* in_data,
        short* out_data,
        int sample_count,
        int* voice_frames_detected);  // 可选，检测到语音的帧数

#ifdef __cplusplus
}
#endif
#endif // YANSHEE_H