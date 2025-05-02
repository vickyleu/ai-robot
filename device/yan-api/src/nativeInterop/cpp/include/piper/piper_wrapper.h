#ifndef PIPER_WRAPPER_H
#define PIPER_WRAPPER_H

#ifdef __cplusplus
extern "C" {
#endif

// C 兼容的数据结构
typedef struct PiperContext PiperContext;

// 初始化 Piper
PiperContext* piper_wrapper_init(const char* espeak_data_path, const char* model_path,
                                 const char* config_path, int speaker_id);

// 释放 Piper 资源
void piper_wrapper_terminate(PiperContext* context);

// 文本转语音
int piper_wrapper_text_to_audio(PiperContext* context, const char* text,
                                short** audio_buffer, int* audio_length);

// 释放音频缓冲区
void piper_wrapper_free_audio(short* audio_buffer);

#ifdef __cplusplus
}
#endif

#endif // PIPER_WRAPPER_H