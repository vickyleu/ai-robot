#ifndef PIPER_C_API_H
#define PIPER_C_API_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

// Error codes
typedef enum {
    PIPER_SUCCESS = 0,
    PIPER_ERROR_INIT = -1,
    PIPER_ERROR_SYNTHESIS = -2,
    PIPER_ERROR_MODEL = -3,
    PIPER_ERROR_INVALID_PARAM = -4,
    PIPER_ERROR_OUT_OF_MEMORY = -5
} PiperStatus;

// Opaque handle to Piper instance
typedef struct PiperContext{}  PiperContext;

// Voice configuration
typedef struct {
    const char* model_path;
    const char* config_path;
    float speaker_id;
    float noise_scale;
    float length_scale;
    float noise_w;
} PiperVoiceConfig;

// Audio format
typedef struct {
    int sample_rate;
    int num_channels;
    int bits_per_sample;
} PiperAudioFormat;

// Initialize Piper with voice configuration
// Returns a handle to the Piper context or NULL on error
PiperContext* piper_init(const PiperVoiceConfig* config, PiperStatus* status);

// Synthesize text to speech
// Returns status code and fills the output buffer with audio samples
// If output_buffer is NULL, only sets output_size to required size
PiperStatus piper_synthesize_text(
        PiperContext* context,
        const char* text,
        float* output_buffer,
        size_t* output_size,
        PiperAudioFormat* format
);

// Get version string
const char* piper_version();

// Free Piper context
void piper_free(PiperContext* context);

// Set speaker ID for multi-speaker models
PiperStatus piper_set_speaker(PiperContext* context, float speaker_id);

// Set synthesis parameters
PiperStatus piper_set_params(
        PiperContext* context,
        float noise_scale,
        float length_scale,
        float noise_w
);

#ifdef __cplusplus
}
#endif

#endif // PIPER_C_API_H