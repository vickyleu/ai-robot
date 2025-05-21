//
// Created by user on 3/14/25.
//

#ifndef WEBRTC_APM_WRAPPER_H
#define WEBRTC_APM_WRAPPER_H

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct APMConfigEchoCanceller {
  bool enabled = false;
  bool mobile_mode = false;
  bool export_linear_aec_output = false;
  // Enforce the highpass filter to be on (has no effect for the mobile
  // mode).
  bool enforce_high_pass_filtering = true;
}APMConfigEchoCanceller;

// 新增：噪声抑制（NS）配置
typedef enum APMNsLevel {
  kNsLow = 0,
  kNsModerate,
  kNsHigh,
  kNsVeryHigh
} APMNsLevel;

typedef struct APMConfigNoiseSuppression {
  bool enabled = false;
  APMNsLevel level = kNsModerate;
} APMConfigNoiseSuppression;

// 新增：高通滤波（HPF）配置
typedef struct APMConfigHighPassFilter {
  bool enabled = false;
} APMConfigHighPassFilter;

// 新增：自动增益控制（AGC1）配置
typedef enum APMAgcMode {
  kAgcAdaptiveAnalog = 0,
  kAgcAdaptiveDigital,
  kAgcFixedDigital
} APMAgcMode;

typedef struct APMConfigGainController {
  bool enabled = false;
  APMAgcMode mode = kAgcAdaptiveAnalog;
  int target_level_dbfs = 3;        // [0,31]
  int compression_gain_db = 9;      // [0,90]
  bool enable_limiter = true;
} APMConfigGainController;

// 新增：语音检测（VAD）配置
typedef struct APMConfigVoiceDetection {
  bool enabled = false;
} APMConfigVoiceDetection;

// 新增：短暂噪声抑制（Transient Suppression）配置
typedef struct APMConfigTransientSuppression {
  bool enabled = false;
} APMConfigTransientSuppression;

// 新增：残余回声检测（RED）配置
typedef struct APMConfigResidualEchoDetector {
  bool enabled = true;
} APMConfigResidualEchoDetector;

// 新增：电平估计（Level Estimation）配置
typedef struct APMConfigLevelEstimation {
  bool enabled = false;
} APMConfigLevelEstimation;

// 新增：前置放大器（PreAmplifier）配置
typedef struct APMConfigPreAmplifier {
  bool enabled = false;
  float fixed_gain_factor = 1.0f;  // 倍率，1.0 为 0 dB
} APMConfigPreAmplifier;

typedef struct APMConfig {
  APMConfigEchoCanceller echo_canceller;
  APMConfigNoiseSuppression noise_suppression;
  APMConfigHighPassFilter high_pass_filter;
  APMConfigGainController gain_controller;
  APMConfigVoiceDetection voice_detection;
  APMConfigTransientSuppression transient_suppression;
  APMConfigResidualEchoDetector residual_echo_detector;
  APMConfigLevelEstimation level_estimation;
  APMConfigPreAmplifier pre_amplifier;
} APMConfig;

void *webrtc_apm_create();
void webrtc_apm_destroy(void *apm);

void webrtc_apm_apply_config(void *apm, const APMConfig *config);
void webrtc_apm_prepare(void *apm, int sample_rate, int channels);
void webrtc_apm_process_reverse_stream(void *apm, const float *const *src,
                                       float *const *dest);
void webrtc_apm_process_stream(void *apm, const float *const *src,
                               float *const *dest);

// 新增运行期接口
void webrtc_apm_set_stream_analog_level(void *apm, int level);
int  webrtc_apm_get_stream_analog_level(void *apm);

void webrtc_apm_set_key_pressed(void *apm, bool key_pressed);

// VAD 结果输出
bool webrtc_apm_voice_detected(void *apm);

// 延迟补偿：调用前先估计近端采集帧与远端播放帧之间的延迟（毫秒）
void webrtc_apm_set_stream_delay_ms(void *apm, int delay_ms);
int  webrtc_apm_get_stream_delay_ms(void *apm);

// 快捷开关 AEC (Echo Canceller)
void webrtc_apm_enable_aec(void *apm, bool enable);

#ifdef __cplusplus
}
#endif

#endif // WEBRTC_APM_WRAPPER_H
