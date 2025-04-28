#include "piper_c_api.h"
#include <stdlib.h>
#include <string.h>

// Include Piper's C++ headers
#ifdef __cplusplus
#include "piper.hpp"
#endif

// Version information
#define PIPER_VERSION "1.0.0"

// Internal structure for PiperContext
struct PiperContext {
#ifdef __cplusplus
    // Piper C++ objects
    piper::PiperConfig config;
    piper::Voice* voice;
    std::unique_ptr<piper::Piper> piper;
#else
    // Opaque pointer for C-only builds
    void* impl;
#endif
    PiperVoiceConfig voice_config;
};

PiperContext* piper_init(const PiperVoiceConfig* config, PiperStatus* status) {
    if (!config || !config->model_path) {
        if (status) *status = PIPER_ERROR_INVALID_PARAM;
        return NULL;
    }

    PiperContext* context = (PiperContext*)malloc(sizeof(PiperContext));
    if (!context) {
        if (status) *status = PIPER_ERROR_OUT_OF_MEMORY;
        return NULL;
    }

    // Initialize with empty values
    memset(context, 0, sizeof(PiperContext));

    // Copy configuration
    context->voice_config = *config;
    context->voice_config.model_path = strdup(config->model_path);
    if (config->config_path) {
        context->voice_config.config_path = strdup(config->config_path);
    }

#ifdef __cplusplus
    try {
        // Initialize Piper configuration
        context->config.model_path = config->model_path;
        if (config->config_path) {
            context->config.config_path = config->config_path;
        }

        // Set synthesis parameters
        context->config.speaker_id = config->speaker_id;
        context->config.noise_scale = config->noise_scale;
        context->config.length_scale = config->length_scale;
        context->config.noise_w = config->noise_w;

        // Load voice
        context->voice = new piper::Voice(context->config);

        // Initialize Piper
        context->piper = std::make_unique<piper::Piper>(*context->voice);

        if (status) *status = PIPER_SUCCESS;
    } catch (const std::exception& e) {
        // Handle initialization errors
        free((void*)context->voice_config.model_path);
        if (context->voice_config.config_path) {
            free((void*)context->voice_config.config_path);
        }

        if (context->voice) {
            delete context->voice;
        }

        free(context);

        if (status) *status = PIPER_ERROR_INIT;
        return NULL;
    }
#else
    // Implement non-C++ version if needed
    if (status) *status = PIPER_ERROR_INIT;
    free(context);
    return NULL;
#endif

    return context;
}

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
        // Synthesize text to get audio samples
        std::vector<float> audio_samples;
        context->piper->synthesize(text, audio_samples);

        // Set format information
        if (format) {
            format->sample_rate = context->voice->getSampleRate();
            format->num_channels = 1; // Mono output
            format->bits_per_sample = 32; // Float samples
        }

        // Determine required buffer size
        size_t required_size = audio_samples.size() * sizeof(float);

        // If buffer is NULL, only return required size
        if (!output_buffer) {
            *output_size = required_size;
            return PIPER_SUCCESS;
        }

        // Check if provided buffer is large enough
        if (*output_size < required_size) {
            *output_size = required_size;
            return PIPER_ERROR_INVALID_PARAM;
        }

        // Copy audio samples to output buffer
        memcpy(output_buffer, audio_samples.data(), required_size);
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
    // Clean up C++ objects
    if (context->voice) {
        delete context->voice;
    }
    // Unique pointer will clean itself up
#endif

    // Free context
    free(context);
}

PiperStatus piper_set_speaker(PiperContext* context, float speaker_id) {
    if (!context) return PIPER_ERROR_INVALID_PARAM;

#ifdef __cplusplus
    try {
        context->config.speaker_id = speaker_id;
        context->voice->setSpeakerId(speaker_id);
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
        context->config.noise_scale = noise_scale;
        context->config.length_scale = length_scale;
        context->config.noise_w = noise_w;
        context->voice->setParams(noise_scale, length_scale, noise_w);
        return PIPER_SUCCESS;
    } catch (const std::exception& e) {
        return PIPER_ERROR_MODEL;
    }
#else
    return PIPER_ERROR_MODEL;
#endif
}