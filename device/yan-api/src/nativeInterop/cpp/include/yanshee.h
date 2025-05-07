#ifndef YANSHEE_H
#define YANSHEE_H
#include <string>
#include "soxr/soxr.h"
#ifdef __cplusplus
extern "C" {
#endif

// SOXR包装器 -------------------------------------------------------------------

struct SoxWrapper {
    soxr_io_spec_t* io_spec;
    soxr_runtime_spec_t* runtime_spec;
    soxr_quality_spec_t* quality_spec;
    soxr_t soxr;
};

// 创建和释放
SoxWrapper* soxr_wrapper_create();
void soxr_wrapper_destroy(SoxWrapper* wrapper);

// 配置函数
void soxr_io_spec_create(soxr_datatype_t itype, soxr_datatype_t otype, SoxWrapper* wrapper);
void soxr_runtime_spec_create(unsigned num_threads, SoxWrapper* wrapper);
void soxr_quality_spec_create(unsigned quality, SoxWrapper* wrapper);

// 创建重采样器
int soxr_wrapper_create_resampler(
        SoxWrapper* wrapper,
        double input_rate,
        double output_rate);

// 执行重采样
size_t soxr_wrapper_process(
        SoxWrapper* wrapper,
        const short* in_data,
        size_t in_size,
        float* out_data,
        size_t out_size);



#ifdef __cplusplus
}
#endif
#endif // YANSHEE_H