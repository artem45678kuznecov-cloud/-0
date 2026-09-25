/*
 * NOX: изолированное выполнение JS во встроенном QuickJS-NG (см. nox_js.h).
 */
#include "nox_js.h"

#include <malloc.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "quickjs.h"

#define NOX_JS_ERROR_MAX 2048

typedef struct {
    char *buf;
    size_t len;
    size_t cap;
    size_t limit;
    int overflow;
    long long deadline_ms;
    int timed_out;
} NoxRun;

/*
 * Собственный учёт памяти вместо JS_SetMemoryLimit: так отказ по пределу
 * виден точно, даже если QuickJS уже не смог создать объект ошибки.
 */
typedef struct {
    size_t used;
    size_t limit;
    int oom;
} NoxAlloc;

static int alloc_allowed(NoxAlloc *a, size_t add) {
    if (a->limit && (add > a->limit || a->used > a->limit - add)) {
        a->oom = 1;
        return 0;
    }
    return 1;
}

static void *nox_calloc(void *opaque, size_t count, size_t size) {
    NoxAlloc *a = (NoxAlloc *) opaque;
    if (size && count > ((size_t) -1) / size) return NULL;
    if (!alloc_allowed(a, count * size)) return NULL;
    void *p = calloc(count, size);
    if (p) a->used += malloc_usable_size(p); else a->oom = 1;
    return p;
}

static void *nox_malloc(void *opaque, size_t size) {
    NoxAlloc *a = (NoxAlloc *) opaque;
    if (!alloc_allowed(a, size)) return NULL;
    void *p = malloc(size);
    if (p) a->used += malloc_usable_size(p); else a->oom = 1;
    return p;
}

static void nox_free(void *opaque, void *ptr) {
    NoxAlloc *a = (NoxAlloc *) opaque;
    if (!ptr) return;
    a->used -= malloc_usable_size(ptr);
    free(ptr);
}

static void *nox_realloc(void *opaque, void *ptr, size_t size) {
    NoxAlloc *a = (NoxAlloc *) opaque;
    size_t old = ptr ? malloc_usable_size(ptr) : 0;
    if (size > old && !alloc_allowed(a, size - old)) return NULL;
    void *p = realloc(ptr, size);
    if (!p) {
        if (size) a->oom = 1;
        else a->used -= old; /* realloc(ptr, 0) освобождает память */
        return NULL;
    }
    a->used = a->used - old + malloc_usable_size(p);
    return p;
}

static size_t nox_usable_size(const void *ptr) {
    return malloc_usable_size((void *) ptr);
}

static const JSMallocFunctions nox_malloc_funcs = {
    nox_calloc, nox_malloc, nox_free, nox_realloc, nox_usable_size
};

static long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long) ts.tv_sec * 1000LL + ts.tv_nsec / 1000000LL;
}

static int interrupt_handler(JSRuntime *rt, void *opaque) {
    (void) rt;
    NoxRun *run = (NoxRun *) opaque;
    if (now_ms() > run->deadline_ms) {
        run->timed_out = 1;
        return 1;
    }
    return 0;
}

static int out_append(NoxRun *run, const char *data, size_t n) {
    if (run->overflow) return -1;
    if (n > run->limit - run->len) {
        run->overflow = 1;
        return -1;
    }
    if (run->len + n + 1 > run->cap) {
        size_t cap = run->cap ? run->cap : 4096;
        while (cap < run->len + n + 1) cap *= 2;
        char *nb = (char *) realloc(run->buf, cap);
        if (!nb) {
            run->overflow = 1;
            return -1;
        }
        run->buf = nb;
        run->cap = cap;
    }
    memcpy(run->buf + run->len, data, n);
    run->len += n;
    run->buf[run->len] = '\0';
    return 0;
}

/* console.log(...args): аргументы через пробел и перевод строки, как в deno/node для строк. */
static JSValue js_console_log(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    (void) this_val;
    NoxRun *run = (NoxRun *) JS_GetContextOpaque(ctx);
    for (int i = 0; i < argc; i++) {
        size_t n = 0;
        const char *s = JS_ToCStringLen(ctx, &n, argv[i]);
        if (!s) return JS_EXCEPTION;
        int rc = (i ? out_append(run, " ", 1) : 0);
        if (rc == 0) rc = out_append(run, s, n);
        JS_FreeCString(ctx, s);
        if (rc != 0) return JS_ThrowRangeError(ctx, "NOX: output limit exceeded");
    }
    if (out_append(run, "\n", 1) != 0) return JS_ThrowRangeError(ctx, "NOX: output limit exceeded");
    return JS_UNDEFINED;
}

static char *copy_error(const char *s, size_t n) {
    if (n > NOX_JS_ERROR_MAX) n = NOX_JS_ERROR_MAX;
    char *e = (char *) malloc(n + 1);
    if (!e) return NULL;
    memcpy(e, s, n);
    e[n] = '\0';
    return e;
}

static char *exception_text(JSContext *ctx) {
    JSValue ex = JS_GetException(ctx);
    size_t n = 0;
    const char *s = JS_ToCStringLen(ctx, &n, ex);
    char *res = NULL;
    if (s) {
        res = copy_error(s, n);
        JS_FreeCString(ctx, s);
    }
    JS_FreeValue(ctx, ex);
    return res;
}

static void set_error(NoxJsResult *r, int status, const char *msg) {
    r->status = status;
    if (!r->error && msg) r->error = copy_error(msg, strlen(msg));
}

void nox_js_run(const char *code, size_t code_len, const NoxJsLimits *limits, NoxJsResult *result) {
    memset(result, 0, sizeof(*result));
    NoxRun run;
    memset(&run, 0, sizeof(run));
    run.limit = limits->output_limit;
    run.deadline_ms = now_ms() + limits->timeout_ms;

    /* JS_Eval требует нулевой байт после исходника. */
    char *src = (char *) malloc(code_len + 1);
    if (!src) {
        set_error(result, NOX_JS_NO_MEMORY, "cannot copy script");
        return;
    }
    memcpy(src, code, code_len);
    src[code_len] = '\0';

    NoxAlloc alloc;
    memset(&alloc, 0, sizeof(alloc));
    alloc.limit = limits->memory_limit;
    JSRuntime *rt = JS_NewRuntime2(&nox_malloc_funcs, &alloc);
    if (!rt) {
        free(src);
        set_error(result, NOX_JS_INTERNAL, "cannot create runtime");
        return;
    }
    if (limits->stack_limit) JS_SetMaxStackSize(rt, limits->stack_limit);
    JS_SetInterruptHandler(rt, interrupt_handler, &run);

    JSContext *ctx = JS_NewContext(rt);
    if (!ctx) {
        JS_FreeRuntime(rt);
        free(src);
        set_error(result, NOX_JS_NO_MEMORY, "cannot create context");
        return;
    }
    JS_SetContextOpaque(ctx, &run);

    JSValue global = JS_GetGlobalObject(ctx);
    JSValue console = JS_NewObject(ctx);
    JS_SetPropertyStr(ctx, console, "log", JS_NewCFunction(ctx, js_console_log, "log", 1));
    JS_SetPropertyStr(ctx, global, "console", console);
    JS_FreeValue(ctx, global);

    JSValue val = JS_Eval(ctx, src, code_len, "<nox-ejs>", JS_EVAL_TYPE_GLOBAL);
    free(src);
    char *err = NULL;
    int failed = 0;
    if (JS_IsException(val)) {
        failed = 1;
        err = exception_text(ctx);
    } else {
        /* Досчитать обещания, если скрипт их создал (ограничено тем же временем). */
        JSContext *jctx;
        int jobs = 0;
        for (;;) {
            int rc = JS_ExecutePendingJob(rt, &jctx);
            if (rc <= 0) {
                if (rc < 0) {
                    failed = 1;
                    err = exception_text(jctx);
                }
                break;
            }
            if (++jobs > 100000) {
                failed = 1;
                err = copy_error("too many pending jobs", 21);
                break;
            }
        }
    }
    JS_FreeValue(ctx, val);

    if (run.timed_out) {
        set_error(result, NOX_JS_TIMEOUT, "time limit exceeded");
    } else if (run.overflow) {
        set_error(result, NOX_JS_OUTPUT_LIMIT, "output limit exceeded");
    } else if (alloc.oom) {
        set_error(result, NOX_JS_NO_MEMORY, "memory limit exceeded");
    } else if (failed) {
        set_error(result, NOX_JS_ERROR, err ? err : "uncaught exception");
    }
    free(err);

    JS_FreeContext(ctx);
    JS_FreeRuntime(rt);

    if (result->status == NOX_JS_OK) {
        result->output = run.buf;
        result->output_len = run.len;
    } else {
        free(run.buf);
    }
}

void nox_js_free(NoxJsResult *result) {
    free(result->output);
    free(result->error);
    result->output = NULL;
    result->error = NULL;
}

const char *nox_js_engine_version(void) {
    return JS_GetVersion();
}
