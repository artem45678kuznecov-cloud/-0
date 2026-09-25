/*
 * NOX: изолированное выполнение одного JS-скрипта во встроенном QuickJS-NG.
 *
 * Скрипту доступен только стандартный ECMAScript и console.log (вывод
 * собирается в буфер). Нет файлов, сети, таймеров, модулей и Android API:
 * quickjs-libc не подключён. Каждое выполнение получает свой JSRuntime с
 * пределами памяти, стека, времени и размера вывода.
 */
#ifndef NOX_JS_H
#define NOX_JS_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

enum {
    NOX_JS_OK = 0,
    NOX_JS_ERROR = 1,       /* исключение в скрипте или ошибка разбора */
    NOX_JS_TIMEOUT = 2,     /* превышен предел времени */
    NOX_JS_NO_MEMORY = 3,   /* превышен предел памяти */
    NOX_JS_OUTPUT_LIMIT = 4,/* вывод больше разрешённого */
    NOX_JS_INTERNAL = 5     /* не удалось создать среду */
};

typedef struct {
    size_t memory_limit;   /* байт, 0 = без предела (не используется в NOX) */
    size_t stack_limit;    /* байт */
    long timeout_ms;       /* общий предел времени */
    size_t output_limit;   /* байт вывода console.log */
} NoxJsLimits;

typedef struct {
    int status;
    char *output;          /* malloc, UTF-8, может быть NULL */
    size_t output_len;
    char *error;           /* malloc, короткое описание ошибки, может быть NULL */
} NoxJsResult;

/* code не обязан оканчиваться нулём. Результат освобождается nox_js_free. */
void nox_js_run(const char *code, size_t code_len, const NoxJsLimits *limits, NoxJsResult *result);
void nox_js_free(NoxJsResult *result);
const char *nox_js_engine_version(void);

#ifdef __cplusplus
}
#endif

#endif
