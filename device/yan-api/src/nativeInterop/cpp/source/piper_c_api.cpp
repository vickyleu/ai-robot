#include "piper_c_api.h"
#include <stdlib.h>
#include <string.h>
#include <limits.h>

// 定义 SIZE_MAX 如果它不存在
#ifndef SIZE_MAX
#define SIZE_MAX ((size_t)-1)
#endif

// Include Piper's C++ headers
#include "piper.hpp"

// Version information
#define PIPER_VERSION "1.0.0"

namespace piper {
    // 添加一个重载的 loadVoice 函数，将 long long 转换为 SpeakerId
    void loadVoice(PiperConfig &config, std::string modelPath,
                   std::string modelConfigPath, Voice &voice,
                   std::optional<long long> &speakerId) {
        // 如果有 speakerId，将其转换为 SpeakerId 类型
        std::optional<SpeakerId> piperSpeakerId;
        if (speakerId.has_value()) {
            piperSpeakerId = static_cast<SpeakerId>(*speakerId);
        }

        // 调用原始的 loadVoice 函数
#pragma clang diagnostic push
#pragma ide diagnostic ignored "InfiniteRecursion"
        loadVoice(config, modelPath, modelConfigPath, voice, piperSpeakerId);
#pragma clang diagnostic pop
    }
}
// Internal structure for PiperContext
struct PiperContext {
#ifdef __cplusplus
    // Piper C++ objects
    piper::PiperConfig config;
    piper::Voice voice;
#else
    // Opaque pointer for C-only builds
    void* impl;
#endif
    PiperVoiceConfig voice_config;
};

PiperContext* piper_init(const PiperVoiceConfig* config, PiperStatus* status) {
    // 只做最基本的事情，不使用C++功能
    PiperContext* context = (PiperContext*)malloc(sizeof(PiperContext));
    memset(context, 0, sizeof(PiperContext));

    // 设置一个成功状态
    if (status) *status = PIPER_SUCCESS;
    return context;
}
//
//PiperContext* piper_init(const PiperVoiceConfig* config, PiperStatus* status) {
//
//    printf("检查model_path...\n");
//    fflush(stdout);
//    printf("检查model_path开始\n");
//    fflush(stdout);
//    if (!config || !config->model_path) {
//        if (status) *status = PIPER_ERROR_INVALID_PARAM;
//        return NULL;
//    }
//    printf("检查model_path完成\n");
//    fflush(stdout);
//    PiperContext* context = (PiperContext*)malloc(sizeof(PiperContext));
//    if (!context) {
//        if (status) *status = PIPER_ERROR_OUT_OF_MEMORY;
//        return NULL;
//    }
//    printf("malloc\n");
//    fflush(stdout);
//    printf("model_path: %p\n", config->model_path);
//    if (config->model_path) {
//        printf("model_path content: '%s'\n", config->model_path);
//    }
//    printf("config_path: %p\n", config->config_path);
//    if (config->config_path) {
//        printf("config_path content: '%s'\n", config->config_path);
//    }
//    printf("eSpeakDataPath: %p\n", config->eSpeakDataPath);
//    if (config->eSpeakDataPath) {
//        printf("eSpeakDataPath content: '%s'\n", config->eSpeakDataPath);
//    }
//    // Initialize with empty values
//    memset(context, 0, sizeof(PiperContext));
//
//    // Copy configuration
//    context->voice_config = *config;
//    char* eSpeakDataPathCopy = config->eSpeakDataPath ? strdup(config->eSpeakDataPath) : NULL;
//    char* modelPathCopy = config->model_path ? strdup(config->model_path) : NULL;
//    char* configPathCopy = config->config_path ? strdup(config->config_path) : NULL;
//
//    context->config.eSpeakDataPath = eSpeakDataPathCopy;
//    context->voice_config.model_path = modelPathCopy;
//    if (config->config_path) {
//        context->voice_config.config_path = configPathCopy;
//    }
//
//#ifdef __cplusplus
//    try {
//        // Initialize Piper configuration
//        context->config.useESpeak = true;  // 默认使用 eSpeak
//
//        // 初始化 Piper
//        piper::initialize(context->config);
//
//        // 设置扬声器 ID
//        std::optional<piper::SpeakerId> speakerId;
//        if (config->speaker_id >= 0) {
//            speakerId = static_cast<piper::SpeakerId>(config->speaker_id);
//        }
//
//        // 加载声音
//        std::optional<long long> speakerIdLongLong;
//        if (config->speaker_id >= 0) {
//            speakerIdLongLong = static_cast<long long>(config->speaker_id);
//        }
//        piper::loadVoice(
//            context->config,
//            std::string(config->model_path),
//            config->config_path ? std::string(config->config_path) : std::string(""),
//            context->voice,
//            speakerIdLongLong
//        );
//
//        // 设置合成参数
//        context->voice.synthesisConfig.noiseScale = config->noise_scale;
//        context->voice.synthesisConfig.lengthScale = config->length_scale;
//        context->voice.synthesisConfig.noiseW = config->noise_w;
//
//        if (status) *status = PIPER_SUCCESS;
//    } catch (const std::exception& e) {
//        // Handle initialization errors
//        free((void*)context->voice_config.model_path);
//        if (context->voice_config.config_path) {
//            free((void*)context->voice_config.config_path);
//        }
//
//        // Clean up Piper
//        piper::terminate(context->config);
//
//        free(context);
//
//        if (status) *status = PIPER_ERROR_INIT;
//        return NULL;
//    }
//#else
//    // Implement non-C++ version if needed
//    if (status) *status = PIPER_ERROR_INIT;
//    free(context);
//    return NULL;
//#endif
//
//    return context;
//}

PiperStatus piper_synthesize_text(
        PiperContext* context,
        const char* text,
        float* output_buffer,
        size_t* output_size,
        PiperAudioFormat* format
) {
    if (!context || !text || !output_size) {
        return PIPER_ERROR_INVALID_PARAM;
    }

#ifdef __cplusplus
    try {
        // 使用 piper::textToAudio 函数进行合成
        std::vector<int16_t> audioBuffer;
        piper::SynthesisResult result;

        // 合成音频
        piper::textToAudio(
            context->config,
            context->voice,
            std::string(text),
            audioBuffer,
            result,
            std::function<void()>([](){}) // 显式转换为 std::function
        );

        // 获取音频格式信息
        if (format) {
            format->sample_rate = context->voice.synthesisConfig.sampleRate;
            format->num_channels = context->voice.synthesisConfig.channels;
            format->bits_per_sample = 32; // 我们转换为 float 类型
        }

        // 将 int16_t 样本转换为 float 样本 (范围 -1.0 到 1.0)
        std::vector<float> floatSamples(audioBuffer.size());
        for (size_t i = 0; i < audioBuffer.size(); i++) {
            floatSamples[i] = static_cast<float>(audioBuffer[i]) / 32768.0f;
        }

        // 确定所需的缓冲区大小
        size_t required_size = floatSamples.size() * sizeof(float);

        // 如果缓冲区为 NULL，只返回所需大小
        if (!output_buffer) {
            *output_size = required_size;
            return PIPER_SUCCESS;
        }

        // 检查提供的缓冲区是否足够大
        if (*output_size < required_size) {
            *output_size = required_size;
            return PIPER_ERROR_INVALID_PARAM;
        }

        // 将音频样本复制到输出缓冲区
        memcpy(output_buffer, floatSamples.data(), required_size);
        *output_size = required_size;

        return PIPER_SUCCESS;
    } catch (const std::exception& e) {
        return PIPER_ERROR_SYNTHESIS;
    }
#else
    return PIPER_ERROR_SYNTHESIS;
#endif
}

const char* piper_version() {
    return PIPER_VERSION;
}

void piper_free(PiperContext* context) {
    if (!context) return;

    // Free allocated strings
    if (context->voice_config.model_path) {
        free((void*)context->voice_config.model_path);
    }

    if (context->voice_config.config_path) {
        free((void*)context->voice_config.config_path);
    }

#ifdef __cplusplus
    // Clean up Piper
    piper::terminate(context->config);
#endif

    // Free context
    free(context);
}

PiperStatus piper_set_speaker(PiperContext* context, float speaker_id) {
    if (!context) return PIPER_ERROR_INVALID_PARAM;

#ifdef __cplusplus
    try {
        // 设置扬声器 ID
        if (speaker_id >= 0) {
            context->voice.synthesisConfig.speakerId = static_cast<piper::SpeakerId>(speaker_id);
        } else {
            context->voice.synthesisConfig.speakerId = std::nullopt;
        }

        return PIPER_SUCCESS;
    } catch (const std::exception& e) {
        return PIPER_ERROR_MODEL;
    }
#else
    return PIPER_ERROR_MODEL;
#endif
}

PiperStatus piper_set_params(
        PiperContext* context,
        float noise_scale,
        float length_scale,
        float noise_w
) {
    if (!context) return PIPER_ERROR_INVALID_PARAM;

#ifdef __cplusplus
    try {
        // 设置合成参数
        context->voice.synthesisConfig.noiseScale = noise_scale;
        context->voice.synthesisConfig.lengthScale = length_scale;
        context->voice.synthesisConfig.noiseW = noise_w;

        return PIPER_SUCCESS;
    } catch (const std::exception& e) {
        return PIPER_ERROR_MODEL;
    }
#else
    return PIPER_ERROR_MODEL;
#endif
}