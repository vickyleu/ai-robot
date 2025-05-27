//
// Created by user on 3/14/25.
//

#ifndef WEBRTC_APM_WRAPPER_H
#define WEBRTC_APM_WRAPPER_H

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// 基础配置结构体
typedef struct APMConfigEchoCanceller {
    int enabled;  // 0=false, 非0=true
    int mobile_mode;
    int export_linear_aec_output;
    int enforce_high_pass_filtering;
} APMConfigEchoCanceller;

// 噪声抑制（NS）配置
typedef enum APMNsLevel {
    kNsLow = 0,
    kNsModerate,
    kNsHigh,
    kNsVeryHigh
} APMNsLevel;

typedef struct APMConfigNoiseSuppression {
    int enabled;
    APMNsLevel level;
} APMConfigNoiseSuppression;

// 高通滤波（HPF）配置
typedef struct APMConfigHighPassFilter {
    int enabled;
} APMConfigHighPassFilter;

// 自动增益控制（AGC1）配置
typedef enum APMAgcMode {
    kAgcAdaptiveAnalog = 0,
    kAgcAdaptiveDigital,
    kAgcFixedDigital
} APMAgcMode;

typedef struct APMConfigGainController {
    int enabled;
    APMAgcMode mode;
    int target_level_dbfs;        // [0,31]
    int compression_gain_db;      // [0,90]
    int enable_limiter;
} APMConfigGainController;

// AGC2 自适应数字增益控制详细配置
typedef struct APMConfigAdaptiveDigital {
    int enabled;
    float initial_saturation_margin_db;    // 可配置，默认20.0
    int extra_saturation_margin_db;        // 可配置，默认2
    float gain_applier_adjacent_speech_frames_threshold; // 可配置
    float max_gain_change_db_per_second;   // 可配置，默认3.0
    float max_output_noise_level_dbfs;     // 可配置，默认-50.0
} APMConfigAdaptiveDigital;

// AGC2配置（完整版本）
typedef struct APMConfigGainController2 {
    int enabled;
    APMConfigAdaptiveDigital adaptive_digital;

    // 固定数字增益控制
    struct {
        float gain_db;
    } fixed_digital;
} APMConfigGainController2;

// 语音检测（VAD）配置
typedef struct APMConfigVoiceDetection {
    int enabled;
} APMConfigVoiceDetection;

// 短暂噪声抑制（Transient Suppression）配置
typedef struct APMConfigTransientSuppression {
    int enabled;
} APMConfigTransientSuppression;

// 残余回声检测（RED）配置
typedef struct APMConfigResidualEchoDetector {
    int enabled;
} APMConfigResidualEchoDetector;

// 电平估计（Level Estimation）配置
typedef struct APMConfigLevelEstimation {
    int enabled;
} APMConfigLevelEstimation;

// 前置放大器（PreAmplifier）配置
typedef struct APMConfigPreAmplifier {
    int enabled;
    float fixed_gain_factor;  // 倍率，1.0 为 0 dB
} APMConfigPreAmplifier;

// 语音概率估算配置
typedef struct APMConfigVoiceProbability {
    float high_confidence_threshold;    // 高置信度阈值
    float low_confidence_threshold;     // 低置信度阈值
    int use_advanced_estimation;        // 是否使用高级估算算法
} APMConfigVoiceProbability;

// 饱和检测配置
typedef struct APMConfigSaturationDetection {
    int low_level_threshold;               // 低电平阈值，小于此值认为可能饱和
    float rms_threshold_dbfs;              // RMS阈值，超过此值认为可能饱和
    int enable_multi_criteria_detection;  // 启用多重标准检测
} APMConfigSaturationDetection;

// 噪声电平估算配置
typedef struct APMConfigNoiseEstimation {
    float default_noise_level_dbfs;   // 默认噪声电平
    int estimation_window_ms;         // 估算窗口大小(毫秒)
    int enable_adaptive_estimation;   // 启用自适应估算
} APMConfigNoiseEstimation;

// 配置预设模式
typedef enum APMPresetMode {
    APM_PRESET_DEFAULT = 0,      // 默认模式
    APM_PRESET_CONFERENCE,       // 会议模式
    APM_PRESET_MUSIC,           // 音乐模式
    APM_PRESET_SPEECH,          // 语音通话模式
    APM_PRESET_LOW_LATENCY,     // 低延迟模式
    APM_PRESET_VOICE_ASSISTANT  // 语音助手模式 (新增)
} APMPresetMode;

// 实时设置类型 (新增)
typedef enum APMRuntimeSettingType {
    APM_RUNTIME_CAPTURE_PRE_GAIN,
    APM_RUNTIME_COMPRESSION_GAIN,
    APM_RUNTIME_CAPTURE_FIXED_POST_GAIN,
    APM_RUNTIME_PLAYOUT_VOLUME_CHANGE,
    APM_RUNTIME_PLAYOUT_AUDIO_DEVICE_CHANGE
} APMRuntimeSettingType;

// 实时设置结构体 (新增)
typedef struct APMRuntimeSetting {
    APMRuntimeSettingType type;
    union {
        float float_value;
        int int_value;
        struct {
            int device_id;
            int max_volume;
        } audio_device;
    } value;
} APMRuntimeSetting;

// 音频质量评估结果 (新增)
typedef struct APMAudioQuality {
    float signal_to_noise_ratio_db;     // 信噪比
    float speech_intelligibility_score; // 语音清晰度评分 [0,1]
    float distortion_level;             // 失真程度 [0,1]
    float background_noise_level_dbfs;  // 背景噪声电平
    int audio_dropouts_detected;        // 是否检测到音频丢失
    float dynamic_range_db;             // 动态范围
} APMAudioQuality;

// 回声消除详细配置 (新增)
typedef struct APMConfigEchoCancellerAdvanced {
    APMConfigEchoCanceller basic;

    // AEC3 特定配置
    struct {
        int enabled;
        float echo_audibility_low_render_limit;
        float echo_audibility_normal_render_limit;
        int enable_shadow_filter_protection;
        int enable_delay_agnostic_aec;
        int filter_adaptation_speedup_factor;
    } aec3;

    // 回声消除性能调整
    struct {
        float aggressive_factor;        // 激进程度 [0,1]
        int enable_extended_filter;     // 启用扩展滤波器
        int max_echo_path_length_ms;    // 最大回声路径长度
        int enable_refinement;          // 启用细化处理
    } performance;
} APMConfigEchoCancellerAdvanced;

// 语音检测高级配置 (新增)
typedef struct APMConfigVoiceDetectionAdvanced {
    APMConfigVoiceDetection basic;

    // RNN-VAD 配置
    struct {
        int enabled;                    // 启用RNN-VAD
        float probability_threshold;    // 概率阈值
        int use_spectral_features;      // 使用频谱特征
        int use_pitch_features;         // 使用基音特征
    } rnn_vad;

    // 语音活动检测优化
    struct {
        int smoothing_window_ms;        // 平滑窗口大小
        float voice_trigger_threshold;  // 语音触发阈值
        float silence_trigger_threshold; // 静音触发阈值
        int adaptive_threshold;         // 自适应阈值
    } optimization;
} APMConfigVoiceDetectionAdvanced;

// 多通道配置 (新增)
typedef struct APMConfigMultiChannel {
    int enable_multi_channel_processing;
    int num_channels;
    int enable_channel_mixing;
    int enable_spatial_processing;

    // 通道特定配置
    struct {
        int enabled;
        float reference_channel_weight; // 参考通道权重
        int enable_beamforming;         // 启用波束形成
        float beam_width_degrees;       // 波束宽度
    } spatial_audio;
} APMConfigMultiChannel;

// 性能优化配置 (新增)
typedef struct APMConfigPerformance {
    int enable_low_latency_mode;
    int enable_background_processing;
    int processing_priority;            // 处理优先级 [0-10]
    int enable_simd_optimizations;      // 启用SIMD优化
    int max_processing_delay_ms;        // 最大处理延迟
} APMConfigPerformance;

// 主配置结构体 (更新)
typedef struct APMConfig {
    APMConfigEchoCancellerAdvanced echo_canceller_advanced;  // 升级到高级版本
    APMConfigNoiseSuppression noise_suppression;
    APMConfigHighPassFilter high_pass_filter;
    APMConfigGainController gain_controller;
    APMConfigGainController2 gain_controller2;
    APMConfigVoiceDetectionAdvanced voice_detection_advanced;  // 升级到高级版本
    APMConfigTransientSuppression transient_suppression;
    APMConfigResidualEchoDetector residual_echo_detector;
    APMConfigLevelEstimation level_estimation;
    APMConfigPreAmplifier pre_amplifier;

    // 高级配置
    APMConfigVoiceProbability voice_probability;
    APMConfigSaturationDetection saturation_detection;
    APMConfigNoiseEstimation noise_estimation;

    // 新增配置
    APMConfigMultiChannel multi_channel;
    APMConfigPerformance performance;
} APMConfig;

// 音频统计信息结构体
typedef struct APMStatistics {
    // VAD相关
    int voice_detected;  // 0=false, 非0=true
    float voice_probability;

    // 回声相关
    int residual_echo_detected;  // 0=false, 非0=true
    float residual_echo_likelihood;
    float echo_return_loss;
    float echo_return_loss_enhancement;

    // 延迟相关
    int delay_median_ms;
    int delay_standard_deviation_ms;

    // 噪声相关
    float noise_suppression_level;

    // 增益相关
    int saturation_detected;  // 0=false, 非0=true
    int recommended_analog_level;
} APMStatistics;

// 扩展统计信息结构体 (新增)
typedef struct APMStatisticsExtended {
    APMStatistics basic;

    // 回声消除详细统计
    struct {
        float erle_db;                  // 回声返回损失增强
        float erl_db;                   // 回声返回损失
        int filter_delay_ms;            // 滤波器延迟
        int filter_diverged;            // 滤波器是否发散
        float linear_aec_quality;       // 线性AEC质量评分
    } echo_cancellation;

    // 噪声抑制详细统计
    struct {
        float noise_estimate_dbfs;      // 噪声估计
        float snr_db;                   // 信噪比
        float suppression_gain_db;      // 抑制增益
        int speech_probability_valid;   // 语音概率是否有效
    } noise_suppression;

    // 音频质量评估
    APMAudioQuality audio_quality;

    // 处理延迟统计
    struct {
        int processing_delay_ms;        // 处理延迟
        int buffer_delay_ms;            // 缓冲延迟
        int total_delay_ms;             // 总延迟
        int real_time_processing;       // 是否实时处理
    } latency;
} APMStatisticsExtended;

// 基础接口
void *webrtc_apm_create();
void webrtc_apm_destroy(void *apm);

void webrtc_apm_apply_config(void *apm, const APMConfig *config);
void webrtc_apm_prepare(void *apm, int sample_rate, int channels);
void webrtc_apm_process_reverse_stream(void *apm, const float *const *src,
                                       float *const *dest);
void webrtc_apm_process_stream(void *apm, const float *const *src,
                               float *const *dest);

// 运行期接口
void webrtc_apm_set_stream_analog_level(void *apm, int level);
int  webrtc_apm_get_stream_analog_level(void *apm);
void webrtc_apm_set_key_pressed(void *apm, int key_pressed);

// VAD 结果输出
int webrtc_apm_voice_detected(void *apm);
float webrtc_apm_get_voice_probability(void *apm);

// 延迟补偿
void webrtc_apm_set_stream_delay_ms(void *apm, int delay_ms);
int  webrtc_apm_get_stream_delay_ms(void *apm);

// 快捷开关接口
void webrtc_apm_enable_aec(void *apm, int enable);
void webrtc_apm_enable_ns(void *apm, int enable);
void webrtc_apm_enable_agc(void *apm, int enable);
void webrtc_apm_enable_vad(void *apm, int enable);

// 动态参数调节接口
void webrtc_apm_set_ns_level(void *apm, APMNsLevel level);
void webrtc_apm_set_agc_target_level(void *apm, int target_dbfs);
void webrtc_apm_set_pre_amplifier_gain(void *apm, float gain_factor);

// 统计信息接口
APMStatistics webrtc_apm_get_statistics(void *apm);
void webrtc_apm_reset_statistics(void *apm);

// 高级接口
int webrtc_apm_process_stream_with_result(void *apm, const float *const *src, float *const *dest);
int webrtc_apm_is_saturated(void *apm);
float webrtc_apm_get_speech_level_dbfs(void *apm);
float webrtc_apm_get_noise_level_dbfs(void *apm);

// 调试和监控接口
void webrtc_apm_enable_debug_recording(void *apm, const char* file_path);
void webrtc_apm_disable_debug_recording(void *apm);

// 配置管理接口
APMConfig webrtc_apm_get_default_config();
int webrtc_apm_validate_config(const APMConfig* config);
void webrtc_apm_apply_preset(void *apm, APMPresetMode mode);

// 高级接口 (新增功能)
// 实时设置
void webrtc_apm_set_runtime_setting(void *apm, const APMRuntimeSetting *setting);

// 线性AEC输出
int webrtc_apm_get_linear_aec_output(void *apm, float **output, int *length);

// 多通道支持
void webrtc_apm_set_multi_channel_config(void *apm, const APMConfigMultiChannel *config);
int webrtc_apm_process_multi_channel_stream(void *apm, const float *const *src_channels,
                                            int num_input_channels, float *const *dest_channels,
                                            int num_output_channels);

// 音频质量评估
APMAudioQuality webrtc_apm_assess_audio_quality(void *apm);
void webrtc_apm_enable_quality_monitoring(void *apm, int enable);

// 扩展统计信息
APMStatisticsExtended webrtc_apm_get_extended_statistics(void *apm);

// 性能优化
void webrtc_apm_optimize_for_platform(void *apm, const char *platform_info);
void webrtc_apm_set_performance_mode(void *apm, int low_latency, int low_power);

// 错误处理和诊断
typedef enum APMErrorCode {
    APM_ERROR_NONE = 0,
    APM_ERROR_INVALID_PARAMETER = -1,
    APM_ERROR_PROCESSING_FAILED = -2,
    APM_ERROR_INITIALIZATION_FAILED = -3,
    APM_ERROR_MEMORY_ALLOCATION = -4,
    APM_ERROR_UNSUPPORTED_OPERATION = -5
} APMErrorCode;

APMErrorCode webrtc_apm_get_last_error(void *apm);
const char* webrtc_apm_get_error_string(APMErrorCode error);

// 回调函数类型定义
typedef void (*APMAudioEventCallback)(void *user_data, int event_type, const void *event_data);
typedef void (*APMProcessingCallback)(void *user_data, const float *const *audio, int length);

// 事件监听
void webrtc_apm_set_audio_event_callback(void *apm, APMAudioEventCallback callback, void *user_data);
void webrtc_apm_set_processing_callback(void *apm, APMProcessingCallback callback, void *user_data);

// 语音助手专用功能
void webrtc_apm_set_voice_assistant_mode(void *apm, int enable);
int webrtc_apm_detect_wake_word_environment(void *apm);  // 检测是否适合唤醒词检测
float webrtc_apm_get_speech_clarity_score(void *apm);    // 获取语音清晰度评分
void webrtc_apm_optimize_for_far_field(void *apm, int enable);  // 远场优化

// 动态配置更新
void webrtc_apm_update_config_runtime(void *apm, const char *config_json);
char* webrtc_apm_export_config_json(void *apm);

// 音频流分析
typedef struct APMStreamAnalysis {
    int contains_speech;    // 0=false, 非0=true
    int contains_music;
    int contains_noise;
    float dominant_frequency_hz;
    float spectral_centroid_hz;
    float zero_crossing_rate;
    int clipping_detected;
} APMStreamAnalysis;

APMStreamAnalysis webrtc_apm_analyze_audio_stream(void *apm, const float *const *audio, int length);

// 自适应处理
void webrtc_apm_enable_adaptive_processing(void *apm, int enable);
void webrtc_apm_set_adaptation_speed(void *apm, float speed);  // 自适应速度 [0,1]

// 音频预处理链
typedef struct APMPreprocessingChain {
    int enable_dc_removal;
    int enable_wind_noise_reduction;
    int enable_click_removal;
    int enable_automatic_gain_normalization;
    struct {
        int enabled;
        float cutoff_frequency_hz;
        int order;
    } custom_high_pass;
} APMPreprocessingChain;

void webrtc_apm_set_preprocessing_chain(void *apm, const APMPreprocessingChain *chain);

// 扩展接口
float webrtc_apm_get_voice_probability_ex(void *apm, const APMConfigVoiceProbability* config);
int webrtc_apm_is_saturated_ex(void *apm, const APMConfigSaturationDetection* config);
float webrtc_apm_get_noise_level_dbfs_ex(void *apm, const APMConfigNoiseEstimation* config);

// 新增扩展接口
int webrtc_apm_detect_double_talk_ex(void *apm);  // 双讲检测
float webrtc_apm_estimate_reverberation_time_ex(void *apm);  // 混响时间估计
void webrtc_apm_get_frequency_response_ex(void *apm, float *magnitude, float *phase, int num_bins);  // 频率响应

#ifdef __cplusplus
}
#endif

#endif // WEBRTC_APM_WRAPPER_H