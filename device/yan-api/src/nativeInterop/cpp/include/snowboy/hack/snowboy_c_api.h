#ifndef SNOWBOY_C_API_H_
#define SNOWBOY_C_API_H_

#ifdef __cplusplus
extern "C" {
#endif
#include <stdint.h>
#include <stdbool.h>

// Opaque type for SnowboyDetect handle
typedef struct SnowboyDetectWrapper SnowboyDetectWrapper;
// Opaque type for SnowboyVad handle
typedef struct SnowboyVadWrapper   SnowboyVadWrapper;

// SnowboyDetect API functions
// Create a new SnowboyDetect instance
SnowboyDetectWrapper* snowboy_create(const char* resource_filename, const char* model_str);

// Free a SnowboyDetect instance
void snowboy_free(SnowboyDetectWrapper* wrapper);

// Reset the detection
bool snowboy_reset(SnowboyDetectWrapper* wrapper);

// Run hotword detection on string data
int snowboy_run_detection(SnowboyDetectWrapper* wrapper, const char* data, int length, bool is_end);

// Run hotword detection on float array
int snowboy_run_detection_float(SnowboyDetectWrapper* wrapper, const float* data, int array_length, bool is_end);

// Run hotword detection on int16 array
int snowboy_run_detection_int16(SnowboyDetectWrapper* wrapper, const int16_t* data, int array_length, bool is_end);

// Run hotword detection on int32 array
int snowboy_run_detection_int32(SnowboyDetectWrapper* wrapper, const int32_t* data, int array_length, bool is_end);

// Set sensitivity for loaded hotwords
void snowboy_set_sensitivity(SnowboyDetectWrapper* wrapper, const char* sensitivity_str);

// Set high sensitivity for loaded hotwords
void snowboy_set_high_sensitivity(SnowboyDetectWrapper* wrapper, const char* high_sensitivity_str);

// Get sensitivity for current hotwords
const char* snowboy_get_sensitivity(SnowboyDetectWrapper* wrapper);

// Set audio gain
void snowboy_set_audio_gain(SnowboyDetectWrapper* wrapper, float audio_gain);

// Update model
void snowboy_update_model(SnowboyDetectWrapper* wrapper);

// Get number of loaded hotwords
int snowboy_num_hotwords(SnowboyDetectWrapper* wrapper);

// Apply frontend processing
void snowboy_apply_frontend(SnowboyDetectWrapper* wrapper, bool apply_frontend);

// Get required sample rate
int snowboy_sample_rate(SnowboyDetectWrapper* wrapper);

// Get required number of channels
int snowboy_num_channels(SnowboyDetectWrapper* wrapper);

// Get required bits per sample
int snowboy_bits_per_sample(SnowboyDetectWrapper* wrapper);

// SnowboyVad API functions

// Create a new SnowboyVad instance
SnowboyVadWrapper* snowboy_vad_create(const char* resource_filename);

// Free a SnowboyVad instance
void snowboy_vad_free(SnowboyVadWrapper* wrapper);

// Reset the VAD
bool snowboy_vad_reset(SnowboyVadWrapper* wrapper);

// Run VAD on string data
int snowboy_vad_run(SnowboyVadWrapper* wrapper, const char* data, int length, bool is_end);

// Run VAD on float array
int snowboy_vad_run_float(SnowboyVadWrapper* wrapper, const float* data, int array_length, bool is_end);

// Run VAD on int16 array
int snowboy_vad_run_int16(SnowboyVadWrapper* wrapper, const int16_t* data, int array_length, bool is_end);

// Run VAD on int32 array
int snowboy_vad_run_int32(SnowboyVadWrapper* wrapper, const int32_t* data, int array_length, bool is_end);

// Set audio gain
void snowboy_vad_set_audio_gain(SnowboyVadWrapper* wrapper, float audio_gain);

// Apply frontend processing
void snowboy_vad_apply_frontend(SnowboyVadWrapper* wrapper, bool apply_frontend);

// Get required sample rate
int snowboy_vad_sample_rate(SnowboyVadWrapper* wrapper);

// Get required number of channels
int snowboy_vad_num_channels(SnowboyVadWrapper* wrapper);

// Get required bits per sample
int snowboy_vad_bits_per_sample(SnowboyVadWrapper* wrapper);

#ifdef __cplusplus
}
#endif

#endif  // SNOWBOY_C_API_H_