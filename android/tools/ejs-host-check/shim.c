#include "nox_js.h"
#include <stdlib.h>
int nox_host_run(const char* code, size_t len, size_t mem, size_t stack, long timeout, size_t outlim,
                 char** out, size_t* outlen, char** err) {
  NoxJsLimits l = {mem, stack, timeout, outlim}; NoxJsResult r;
  nox_js_run(code, len, &l, &r);
  *out = r.output; *outlen = r.output_len; *err = r.error; return r.status;
}
void nox_host_free(void* p){ free(p); }
const char* nox_host_version(void){ return nox_js_engine_version(); }
