// snowboy-c-api.cpp

#include "snowboy-c-api.h"
#include "snowboy-detect.h"
#include <string>
#include <cstring>

using namespace snowboy;

// Wrapper structs
struct SnowboyDetectWrapper {
    SnowboyDetect* detector;
    char* sensitivity_buffer;
};

struct SnowboyVadWrapper {
    SnowboyVad* vad;
};

// SnowboyDetect implementation

SnowboyDetectWrapper* snowboy_create(const char* resource_filename, const char* model_str) {
    SnowboyDetectWrapper* wrapper = new SnowboyDetectWrapper();

    // 转换为 std::string 对象
    std::string resource_str(resource_filename);
    std::string model_string(model_str);

    // 使用 std::string 对象创建 SnowboyDetect
    wrapper->detector = new SnowboyDetect(resource_str, model_string);
    wrapper->sensitivity_buffer = nullptr;
    return wrapper;
}

void snowboy_free(SnowboyDetectWrapper* wrapper) {
    if (wrapper) {
        delete wrapper->detector;
        if (wrapper->sensitivity_buffer) {
            delete[] wrapper->sensitivity_buffer;
        }
        delete wrapper;
    }
}

bool snowboy_reset(SnowboyDetectWrapper* wrapper) {
    return wrapper->detector->Reset();
}

int snowboy_run_detection(SnowboyDetectWrapper* wrapper, const char* data, int length, bool is_end) {
    // 显式使用带长度参数的 std::string 构造函数
    std::string data_str(data, length);
    return wrapper->detector->RunDetection(data_str, is_end);
}

int snowboy_run_detection_float(SnowboyDetectWrapper* wrapper, const float* data, int array_length, bool is_end) {
    return wrapper->detector->RunDetection(data, array_length, is_end);
}

int snowboy_run_detection_int16(SnowboyDetectWrapper* wrapper, const int16_t* data, int array_length, bool is_end) {
    return wrapper->detector->RunDetection(data, array_length, is_end);
}

int snowboy_run_detection_int32(SnowboyDetectWrapper* wrapper, const int32_t* data, int array_length, bool is_end) {
    return wrapper->detector->RunDetection(data, array_length, is_end);
}

void snowboy_set_sensitivity(SnowboyDetectWrapper* wrapper, const char* sensitivity_str) {
    // 转换为 std::string
    std::string sensitivity_string(sensitivity_str);
    wrapper->detector->SetSensitivity(sensitivity_string);
}

void snowboy_set_high_sensitivity(SnowboyDetectWrapper* wrapper, const char* high_sensitivity_str) {
    // 转换为 std::string
    std::string high_sensitivity_string(high_sensitivity_str);
    wrapper->detector->SetHighSensitivity(high_sensitivity_string);
}

const char* snowboy_get_sensitivity(SnowboyDetectWrapper* wrapper) {
    std::string sensitivity = wrapper->detector->GetSensitivity();

    // 释放之前的缓冲区（如果存在）
    if (wrapper->sensitivity_buffer) {
        delete[] wrapper->sensitivity_buffer;
    }

    // 为字符串分配新缓冲区
    wrapper->sensitivity_buffer = new char[sensitivity.length() + 1];
    strcpy(wrapper->sensitivity_buffer, sensitivity.c_str());

    return wrapper->sensitivity_buffer;
}

void snowboy_set_audio_gain(SnowboyDetectWrapper* wrapper, float audio_gain) {
    wrapper->detector->SetAudioGain(audio_gain);
}

void snowboy_update_model(SnowboyDetectWrapper* wrapper) {
    wrapper->detector->UpdateModel();
}

int snowboy_num_hotwords(SnowboyDetectWrapper* wrapper) {
    return wrapper->detector->NumHotwords();
}

void snowboy_apply_frontend(SnowboyDetectWrapper* wrapper, bool apply_frontend) {
    wrapper->detector->ApplyFrontend(apply_frontend);
}

int snowboy_sample_rate(SnowboyDetectWrapper* wrapper) {
    return wrapper->detector->SampleRate();
}

int snowboy_num_channels(SnowboyDetectWrapper* wrapper) {
    return wrapper->detector->NumChannels();
}

int snowboy_bits_per_sample(SnowboyDetectWrapper* wrapper) {
    return wrapper->detector->BitsPerSample();
}

// SnowboyVad implementation

SnowboyVadWrapper* snowboy_vad_create(const char* resource_filename) {
    // 转换为 std::string
    std::string resource_str(resource_filename);

    SnowboyVadWrapper* wrapper = new SnowboyVadWrapper();
    wrapper->vad = new SnowboyVad(resource_str);
    return wrapper;
}

void snowboy_vad_free(SnowboyVadWrapper* wrapper) {
    if (wrapper) {
        delete wrapper->vad;
        delete wrapper;
    }
}

bool snowboy_vad_reset(SnowboyVadWrapper* wrapper) {
    return wrapper->vad->Reset();
}

int snowboy_vad_run(SnowboyVadWrapper* wrapper, const char* data, int length, bool is_end) {
    // 显式使用带长度参数的 std::string 构造函数
    std::string data_str(data, length);
    return wrapper->vad->RunVad(data_str, is_end);
}

int snowboy_vad_run_float(SnowboyVadWrapper* wrapper, const float* data, int array_length, bool is_end) {
    return wrapper->vad->RunVad(data, array_length, is_end);
}

int snowboy_vad_run_int16(SnowboyVadWrapper* wrapper, const int16_t* data, int array_length, bool is_end) {
    return wrapper->vad->RunVad(data, array_length, is_end);
}

int snowboy_vad_run_int32(SnowboyVadWrapper* wrapper, const int32_t* data, int array_length, bool is_end) {
    return wrapper->vad->RunVad(data, array_length, is_end);
}

void snowboy_vad_set_audio_gain(SnowboyVadWrapper* wrapper, float audio_gain) {
    wrapper->vad->SetAudioGain(audio_gain);
}

void snowboy_vad_apply_frontend(SnowboyVadWrapper* wrapper, bool apply_frontend) {
    wrapper->vad->ApplyFrontend(apply_frontend);
}

int snowboy_vad_sample_rate(SnowboyVadWrapper* wrapper) {
    return wrapper->vad->SampleRate();
}

int snowboy_vad_num_channels(SnowboyVadWrapper* wrapper) {
    return wrapper->vad->NumChannels();
}

int snowboy_vad_bits_per_sample(SnowboyVadWrapper* wrapper) {
    return wrapper->vad->BitsPerSample();
}