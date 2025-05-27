#ifndef YANSHEE_H
#define YANSHEE_H

#include <string>
#include "soxr/soxr.h"
#include <string.h>

#ifdef __cplusplus
extern "C" {
#endif

// SOXR包装器 -------------------------------------------------------------------

struct SoxWrapper {
    soxr_io_spec_t *io_spec;
    soxr_runtime_spec_t *runtime_spec;
    soxr_quality_spec_t *quality_spec;
    soxr_t soxr;
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

// WebRTC APM包装实现 ------------------------------------------------------------
void my_webrtc_apm_set_key_pressed(void *apm, int key_pressed);
// VAD 结果输出
int my_webrtc_apm_voice_detected(void *apm);
// 快捷开关 AEC (Echo Canceller)
void my_webrtc_apm_enable_aec(void *apm, int enable);

#ifdef __cplusplus
}
#endif
#endif // YANSHEE_H