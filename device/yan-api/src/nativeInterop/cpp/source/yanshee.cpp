#include "yanshee.h"
#include "webrtc_apm_wrapper.h"
#include <stdio.h>
#include <stdlib.h>


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

size_t soxr_wrapper_process_float_to_short(
        SoxWrapper* wrapper,
        const float* in_data,
        size_t in_size,
        short* out_data,
        size_t out_size) {

    if (!wrapper || !wrapper->soxr) {
        fprintf(stderr, "Invalid SoxWrapper or resampler not initialized\n");
        return 0;
    }

    size_t done = 0;
    soxr_error_t error;

    error = soxr_process(
            wrapper->soxr,
            in_data, in_size / sizeof(float), NULL,
            out_data, out_size / sizeof(short), &done
    );

    if (error) {
        fprintf(stderr, "SOXR process float to short error: %s\n", soxr_strerror(error));
        return 0;
    }

    return done;
}

size_t soxr_wrapper_process_float_to_float(
        SoxWrapper* wrapper,
        const float* in_data,
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
            in_data, in_size, NULL,         // 输入：Float数据，样本数
            out_data, out_size, &done       // 输出：Float数据，样本数
    );

    if (error) {
        fprintf(stderr, "SOXR process float to float error: %s\n", soxr_strerror(error));
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

// WebRTC APM包装实现 ------------------------------------------------------------
void my_webrtc_apm_set_key_pressed(void *apm, int key_pressed){
    webrtc_apm_set_key_pressed(apm, key_pressed!=0);
}
// VAD 结果输出
int my_webrtc_apm_voice_detected(void *apm){
    return webrtc_apm_voice_detected(apm)?1:0;
}
// 快捷开关 AEC (Echo Canceller)
void my_webrtc_apm_enable_aec(void *apm, int enable){
    webrtc_apm_enable_aec(apm, enable!=0);
}

