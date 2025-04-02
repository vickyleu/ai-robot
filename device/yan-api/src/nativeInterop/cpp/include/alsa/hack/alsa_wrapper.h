#ifndef ALSA_WRAPPER_H
#define ALSA_WRAPPER_H
#include <stdio.h>
#include <stdint.h>
#include "alsa/input.h"
#include "alsa/output.h"
#include "alsa/conf.h"
#include "alsa/global.h"
#include "alsa/alisp.h"
#include "alsa/asoundef.h"
#include "alsa/asoundlib.h"
#include "alsa/pcm.h"
#include "alsa/error.h"
#include "alsa/hwdep.h"
#include "alsa/control.h"
#include "alsa/control_external.h"
#include "alsa/mixer.h"
#include "alsa/mixer_abst.h"
#include "alsa/pcm_external.h"
// 补全cpp/include/alsa 目录下的所有头文件
#include "alsa/rawmidi.h"
#include "alsa/seq.h"
#include "alsa/seq_event.h"
#include "alsa/seq_midi_event.h"
#include "alsa/seq_event.h"
#include "alsa/timer.h"
#include "alsa/topology.h"
#include "alsa/use-case.h"
#include "alsa/version.h"
#include "alsa/pcm_rate.h"
#include "alsa/pcm_ioplug.h"
#include "alsa/pcm_plugin.h"
#ifdef __cplusplus
#define __PREFFIX extern "C"
#else
#define __PREFFIX
#endif
__PREFFIX  void my_snd_pcm_hw_params_alloca(snd_pcm_hw_params_t **ptr);
#endif