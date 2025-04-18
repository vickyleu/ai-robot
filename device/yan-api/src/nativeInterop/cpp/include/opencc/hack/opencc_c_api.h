#ifndef OPENCC_C_API_H
#define OPENCC_C_API_H

#ifdef __cplusplus
extern "C" {
#endif

#include <stddef.h>


typedef void *opencc_t;

opencc_t opencc_open(const char *configFileName);

int opencc_close(opencc_t handle);

size_t opencc_convert_utf8_to_buffer(opencc_t opencc,
                                     const char *input,
                                     size_t length,
                                     char *output);

char *opencc_convert_utf8(opencc_t opencc,
                          const char *input,
                          size_t length);

void opencc_convert_utf8_free(char *str);

const char *opencc_error(void);

#ifdef __cplusplus
}
#endif

#endif // OPENCC_C_API_H
