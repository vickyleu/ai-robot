#include "alsa/hack/alsa_wrapper.h"


#include <alloca.h>  // 用于alloca函数
#include <string.h>  // 用于memset函数
//#define snd_pcm_hw_params_alloca(ptr) __snd_alloca(ptr, snd_pcm_hw_params)

void my_snd_pcm_hw_params_alloca(snd_pcm_hw_params_t **ptr) {
//    snd_pcm_hw_params_alloca(ptr);
    __snd_alloca(ptr, snd_pcm_hw_params);
}