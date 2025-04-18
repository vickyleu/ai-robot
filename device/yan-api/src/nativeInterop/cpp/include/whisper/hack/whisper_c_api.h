#ifndef WHISPER_C_API_H
#define WHISPER_C_API_H

#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>

#import "whisper.h"

#ifdef __cplusplus
extern "C" {
#endif



static inline  WHISPER_API struct whisper_context * my_whisper_init_from_file_with_params  (const char * path_model,              struct whisper_context_params *  params){
    return whisper_init_from_file_with_params(path_model, *params);
}
static inline  WHISPER_API struct whisper_context * my_whisper_init_from_buffer_with_params(void * buffer, size_t buffer_size,    struct whisper_context_params *  params){
    return whisper_init_from_buffer_with_params(buffer, buffer_size, *params);
}
static inline  WHISPER_API struct whisper_context * my_whisper_init_with_params            (struct whisper_model_loader * loader, struct whisper_context_params *  params){
    return whisper_init_with_params(loader, *params);
}
static inline  WHISPER_API struct whisper_context * my_whisper_init_from_file_with_params_no_state  (const char * path_model,              struct whisper_context_params *  params){
    return whisper_init_from_file_with_params_no_state(path_model, *params);
}
static inline  WHISPER_API struct whisper_context * my_whisper_init_from_buffer_with_params_no_state(void * buffer, size_t buffer_size,    struct whisper_context_params *  params){
    return whisper_init_from_buffer_with_params_no_state(buffer, buffer_size, *params);
}
static inline  WHISPER_API struct whisper_context * my_whisper_init_with_params_no_state            (struct whisper_model_loader * loader, struct whisper_context_params *  params){
    return whisper_init_with_params_no_state(loader, *params);
}

static inline  WHISPER_API const char * my_whisper_token_to_str(struct whisper_context * ctx, whisper_token *  token){
    return whisper_token_to_str(ctx, *token);
}
static inline  WHISPER_API int my_whisper_full(
        struct whisper_context * ctx,
        struct whisper_full_params   *  params,
        const float * samples,
        int   n_samples){
    return whisper_full(ctx, *params, samples, n_samples);
}

static inline  WHISPER_API int my_whisper_full_with_state(
        struct whisper_context * ctx,
        struct whisper_state * state,
        struct whisper_full_params  *   params,
        const float * samples,
        int   n_samples){
    return whisper_full_with_state(ctx, state, *params, samples, n_samples);
}
static inline  WHISPER_API int my_whisper_full_parallel(
        struct whisper_context * ctx,
        struct whisper_full_params   *  params,
        const float * samples,
        int   n_samples,
        int   n_processors){
    return whisper_full_parallel(ctx, *params, samples, n_samples, n_processors);
}


#ifdef __cplusplus
}
#endif

#endif // WHISPER_C_API_H

